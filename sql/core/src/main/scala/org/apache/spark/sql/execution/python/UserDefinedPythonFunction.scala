/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.python

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, EOFException}
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.HashMap

import scala.collection.JavaConverters._

import net.razorvine.pickle.Pickler

import org.apache.spark.{JobArtifactSet, SparkEnv, SparkException}
import org.apache.spark.api.python.{PythonEvalType, PythonFunction, PythonWorkerUtils, SpecialLengths}
import org.apache.spark.internal.config.BUFFER_SIZE
import org.apache.spark.internal.config.Python._
import org.apache.spark.sql.{Column, DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.catalyst.expressions.{Expression, FunctionTableSubqueryArgumentExpression, NamedArgumentExpression, PythonUDAF, PythonUDF, PythonUDTF, UnresolvedPolymorphicPythonUDTF}
import org.apache.spark.sql.catalyst.plans.logical.{Generate, LogicalPlan, OneRowRelation}
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, StructType}

/**
 * A user-defined Python function. This is used by the Python API.
 */
case class UserDefinedPythonFunction(
    name: String,
    func: PythonFunction,
    dataType: DataType,
    pythonEvalType: Int,
    udfDeterministic: Boolean) {

  def builder(e: Seq[Expression]): Expression = {
    if (pythonEvalType == PythonEvalType.SQL_GROUPED_AGG_PANDAS_UDF) {
      PythonUDAF(name, func, dataType, e, udfDeterministic)
    } else {
      PythonUDF(name, func, dataType, e, pythonEvalType, udfDeterministic)
    }
  }

  /** Returns a [[Column]] that will evaluate to calling this UDF with the given input. */
  def apply(exprs: Column*): Column = {
    fromUDFExpr(builder(exprs.map(_.expr)))
  }

  /**
   * Returns a [[Column]] that will evaluate the UDF expression with the given input.
   */
  def fromUDFExpr(expr: Expression): Column = {
    expr match {
      case udaf: PythonUDAF => Column(udaf.toAggregateExpression())
      case _ => Column(expr)
    }
  }
}

/**
 * A user-defined Python table function. This is used by the Python API.
 */
case class UserDefinedPythonTableFunction(
    name: String,
    func: PythonFunction,
    returnType: Option[StructType],
    pythonEvalType: Int,
    udfDeterministic: Boolean) {

  def this(
      name: String,
      func: PythonFunction,
      returnType: StructType,
      pythonEvalType: Int,
      udfDeterministic: Boolean) = {
    this(name, func, Some(returnType), pythonEvalType, udfDeterministic)
  }

  def this(
      name: String,
      func: PythonFunction,
      pythonEvalType: Int,
      udfDeterministic: Boolean) = {
    this(name, func, None, pythonEvalType, udfDeterministic)
  }

  def builder(exprs: Seq[Expression]): LogicalPlan = {
    val udtf = returnType match {
      case Some(rt) =>
        PythonUDTF(
          name = name,
          func = func,
          elementSchema = rt,
          children = exprs,
          evalType = pythonEvalType,
          udfDeterministic = udfDeterministic)
      case _ =>
        // Check which argument is a table argument here since it will be replaced with
        // `UnresolvedAttribute` to construct lateral join.
        val tableArgs = exprs.map {
          case _: FunctionTableSubqueryArgumentExpression => true
          case NamedArgumentExpression(_, _: FunctionTableSubqueryArgumentExpression) => true
          case _ => false
        }
        UnresolvedPolymorphicPythonUDTF(
          name = name,
          func = func,
          children = exprs,
          evalType = pythonEvalType,
          udfDeterministic = udfDeterministic,
          resolveElementSchema = UserDefinedPythonTableFunction.analyzeInPython(_, _, tableArgs))
    }
    Generate(
      udtf,
      unrequiredChildIndex = Nil,
      outer = false,
      qualifier = None,
      generatorOutput = Nil,
      child = OneRowRelation()
    )
  }

  /** Returns a [[DataFrame]] that will evaluate to calling this UDTF with the given input. */
  def apply(session: SparkSession, exprs: Column*): DataFrame = {
    val udtf = builder(exprs.map(_.expr))
    Dataset.ofRows(session, udtf)
  }
}

object UserDefinedPythonTableFunction {

  private[this] val workerModule = "pyspark.sql.worker.analyze_udtf"

  /**
   * Runs the Python UDTF's `analyze` static method.
   *
   * When the Python UDTF is defined without a static return type,
   * the analyzer will call this while resolving table-valued functions.
   *
   * This expects the Python UDTF to have `analyze` static method that take arguments:
   *
   * - The number and order of arguments are the same as the UDTF inputs
   * - Each argument is an `AnalyzeArgument`, containing:
   *   - data_type: DataType
   *   - value: Any: if the argument is foldable; otherwise None
   *   - is_table: bool: True if the argument is TABLE
   *
   * and that return an `AnalyzeResult`.
   *
   * It serializes/deserializes the data types via JSON,
   * and the values for the case the argument is foldable are pickled.
   *
   * `AnalysisException` with the error class "TABLE_VALUED_FUNCTION_FAILED_TO_ANALYZE_IN_PYTHON"
   * will be thrown when an exception is raised in Python.
   */
  def analyzeInPython(
      func: PythonFunction, exprs: Seq[Expression], tableArgs: Seq[Boolean]): StructType = {
    val env = SparkEnv.get
    val bufferSize: Int = env.conf.get(BUFFER_SIZE)
    val authSocketTimeout = env.conf.get(PYTHON_AUTH_SOCKET_TIMEOUT)
    val reuseWorker = env.conf.get(PYTHON_WORKER_REUSE)
    val localdir = env.blockManager.diskBlockManager.localDirs.map(f => f.getPath()).mkString(",")
    val simplifiedTraceback: Boolean = SQLConf.get.pysparkSimplifiedTraceback
    val workerMemoryMb = SQLConf.get.pythonUDTFAnalyzerMemory

    val jobArtifactUUID = JobArtifactSet.getCurrentJobArtifactState.map(_.uuid)

    val envVars = new HashMap[String, String](func.envVars)
    val pythonExec = func.pythonExec
    val pythonVer = func.pythonVer
    val pythonIncludes = func.pythonIncludes.asScala.toSet
    val broadcastVars = func.broadcastVars.asScala.toSeq
    val maybeAccumulator = Option(func.accumulator).map(_.copyAndReset())

    envVars.put("SPARK_LOCAL_DIRS", localdir)
    if (reuseWorker) {
      envVars.put("SPARK_REUSE_WORKER", "1")
    }
    if (simplifiedTraceback) {
      envVars.put("SPARK_SIMPLIFIED_TRACEBACK", "1")
    }
    workerMemoryMb.foreach { memoryMb =>
      envVars.put("PYSPARK_UDTF_ANALYZER_MEMORY_MB", memoryMb.toString)
    }
    envVars.put("SPARK_AUTH_SOCKET_TIMEOUT", authSocketTimeout.toString)
    envVars.put("SPARK_BUFFER_SIZE", bufferSize.toString)

    envVars.put("SPARK_JOB_ARTIFACT_UUID", jobArtifactUUID.getOrElse("default"))

    EvaluatePython.registerPicklers()
    val pickler = new Pickler(/* useMemo = */ true,
      /* valueCompare = */ false)

    val (worker: Socket, _) =
      env.createPythonWorker(pythonExec, workerModule, envVars.asScala.toMap)
    var releasedOrClosed = false
    try {
      val dataOut =
        new DataOutputStream(new BufferedOutputStream(worker.getOutputStream, bufferSize))
      val dataIn = new DataInputStream(new BufferedInputStream(worker.getInputStream, bufferSize))

      PythonWorkerUtils.writePythonVersion(pythonVer, dataOut)
      PythonWorkerUtils.writeSparkFiles(jobArtifactUUID, pythonIncludes, dataOut)
      PythonWorkerUtils.writeBroadcasts(broadcastVars, worker, env, dataOut)

      // Send Python UDTF
      dataOut.writeInt(func.command.length)
      dataOut.write(func.command.toArray)

      // Send arguments
      dataOut.writeInt(exprs.length)
      exprs.zip(tableArgs).foreach { case (expr, is_table) =>
        PythonWorkerUtils.writeUTF(expr.dataType.json, dataOut)
        if (expr.foldable) {
          dataOut.writeBoolean(true)
          val obj = pickler.dumps(EvaluatePython.toJava(expr.eval(), expr.dataType))
          dataOut.writeInt(obj.length)
          dataOut.write(obj)
        } else {
          dataOut.writeBoolean(false)
        }
        dataOut.writeBoolean(is_table)
      }

      dataOut.writeInt(SpecialLengths.END_OF_STREAM)
      dataOut.flush()

      // Receive the schema
      val schema = dataIn.readInt() match {
        case length if length >= 0 =>
          val obj = new Array[Byte](length)
          dataIn.readFully(obj)
          DataType.fromJson(new String(obj, StandardCharsets.UTF_8)).asInstanceOf[StructType]

        case SpecialLengths.PYTHON_EXCEPTION_THROWN =>
          val exLength = dataIn.readInt()
          val obj = new Array[Byte](exLength)
          dataIn.readFully(obj)
          val msg = new String(obj, StandardCharsets.UTF_8)
          throw QueryCompilationErrors.tableValuedFunctionFailedToAnalyseInPythonError(msg)
      }

      PythonWorkerUtils.receiveAccumulatorUpdates(maybeAccumulator, dataIn)
      Option(func.accumulator).foreach(_.merge(maybeAccumulator.get))

      dataIn.readInt() match {
        case SpecialLengths.END_OF_STREAM if reuseWorker =>
          env.releasePythonWorker(pythonExec, workerModule, envVars.asScala.toMap, worker)
        case _ =>
          env.destroyPythonWorker(pythonExec, workerModule, envVars.asScala.toMap, worker)
      }
      releasedOrClosed = true

      schema
    } catch {
      case eof: EOFException =>
        throw new SparkException("Python worker exited unexpectedly (crashed)", eof)
    } finally {
      if (!releasedOrClosed) {
        // An error happened. Force to close the worker.
        env.destroyPythonWorker(pythonExec, workerModule, envVars.asScala.toMap, worker)
      }
    }
  }
}
