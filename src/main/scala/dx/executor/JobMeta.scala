package dx.executor

import java.nio.file.{Files, Path}

import dx.{AppException, AppInternalException}
import dx.api.{
  DxAnalysis,
  DxApi,
  DxExecutable,
  DxExecution,
  DxJob,
  DxJobDescribe,
  DxProject,
  DxProjectDescribe,
  Field,
  InstanceTypeDB
}
import dx.core.Native
import dx.core.io.{DxFileAccessProtocol, DxFileDescCache, DxWorkerPaths}
import dx.core.ir.Value.VNull
import dx.core.ir.{
  ParameterLink,
  ParameterLinkDeserializer,
  ParameterLinkExec,
  ParameterLinkSerializer,
  Type,
  Value,
  ValueSerde
}
import dx.core.languages.Language
import dx.core.languages.Language.Language
import dx.core.util.CompressionUtils
import spray.json._
import wdlTools.util.{FileSourceResolver, FileUtils, JsUtils, Logger, TraceLevel}

object JobMeta {
  val inputFile = "job_input.json"
  val outputFile = "job_output.json"
  val errorFile = "job_error.json"
  val infoFile = "dnanexus-job.json"

  /**
    * Report an error, since this is called from a bash script, we can't simply
    * raise an exception. Instead, we write the error to a standard JSON file.
    * @param e the exception
    */
  def writeError(homeDir: Path, e: Throwable): Unit = {
    val jobErrorPath = homeDir.resolve(errorFile)
    val errType = e match {
      case _: AppException         => "AppError"
      case _: AppInternalException => "AppInternalError"
      case _: Throwable            => "AppInternalError"
    }
    // We are limited in what characters can be written to json, so we
    // provide a short description for json.
    //
    // Note: we sanitize this string, to be absolutely sure that
    // it does not contain problematic JSON characters.
    val errMsg = JsObject(
        "error" -> JsObject(
            "type" -> JsString(errType),
            "message" -> JsUtils.sanitizedString(e.getMessage)
        )
    ).prettyPrint
    FileUtils.writeFileContent(jobErrorPath, errMsg)
  }
}

/**
  * Encapsulates all the metadata used by the executor, including:
  * 1. metadata files on the worker
  * 2. job details
  * 3. application details
  * @param homeDir home directory
  * @param dxApi DxApi
  * @param logger Logger
  */
abstract class JobMeta(val homeDir: Path, val dxApi: DxApi, val logger: Logger) {
  def project: DxProject

  lazy val projectDesc: DxProjectDescribe = project.describe()

  def jsInputs: Map[String, JsValue]

  lazy val dxFileDescCache: DxFileDescCache = {
    val allFilesReferenced = jsInputs.flatMap {
      case (_, jsElem) => dxApi.findFiles(jsElem)
    }.toVector
    // Describe all the files and build a lookup cache
    DxFileDescCache(dxApi.fileBulkDescribe(allFilesReferenced))
  }

  lazy val inputDeserializer: ParameterLinkDeserializer =
    ParameterLinkDeserializer(dxFileDescCache, dxApi)
  lazy val inputs: Map[String, Value] = inputDeserializer.deserializeInputMap(jsInputs)

  lazy val fileResolver: FileSourceResolver = {
    val dxProtocol = DxFileAccessProtocol(dxApi, dxFileDescCache)
    val fileResolver = FileSourceResolver.create(
        localDirectories = Vector(homeDir),
        userProtocols = Vector(dxProtocol),
        logger = logger
    )
    FileSourceResolver.set(fileResolver)
    fileResolver
  }

  def writeJsOutputs(outputJs: Map[String, JsValue]): Unit

  lazy val outputSerializer: ParameterLinkSerializer = ParameterLinkSerializer(fileResolver, dxApi)

  def writeOutputs(outputs: Map[String, (Type, Value)]): Unit = {
    // write outputs, ignore null values, these could occur for optional
    // values that were not specified.
    val outputJs = outputs
      .collect {
        case (name, (t, v)) if v != VNull =>
          outputSerializer.createFields(name, t, v)
      }
      .flatten
      .toMap
    writeJsOutputs(outputJs)
  }

  def createOutputLinks(outputs: Map[String, (Type, Value)]): Map[String, ParameterLink] = {
    outputs.collect {
      case (name, (t, v)) if v != VNull =>
        name -> outputSerializer.createLink(t, v)
    }
  }

  def serializeOutputLinks(outputs: Map[String, ParameterLink]): Map[String, JsValue] = {
    outputs
      .map {
        case (name, link) => outputSerializer.createFieldsFromLink(link, name)
      }
      .flatten
      .toMap
  }

  def writeOutputLinks(outputs: Map[String, ParameterLink]): Unit = {
    writeJsOutputs(serializeOutputLinks(outputs))
  }

  def createOutputLinks(execution: DxExecution,
                        irOutputFields: Map[String, Type],
                        prefix: Option[String] = None): Map[String, ParameterLink] = {
    irOutputFields.map {
      case (name, t) =>
        val fqn = prefix.map(p => s"${p}.${name}").getOrElse(name)
        fqn -> ParameterLinkExec(execution, name, t)
    }
  }

  def serializeOutputLinks(execution: DxExecution,
                           irOutputFields: Map[String, Type]): Map[String, JsValue] = {
    createOutputLinks(execution, irOutputFields)
      .flatMap {
        case (name, link) => outputSerializer.createFieldsFromLink(link, name)
      }
      .filter {
        case (_, null | JsNull) => false
        case _                  => true
      }
  }

  def writeOutputLinks(execution: DxExecution, irOutputFields: Map[String, Type]): Unit = {
    writeJsOutputs(serializeOutputLinks(execution, irOutputFields))
  }

  def jobId: String

  def analysis: Option[DxAnalysis]

  def parentJob: Option[DxJob]

  def instanceType: Option[String]

  def getJobDetail(name: String): Option[JsValue]

  def getExecutableDetail(name: String): Option[JsValue]

  lazy val language: Option[Language] = getExecutableDetail(Native.Language) match {
    case Some(JsString(lang)) => Some(Language.withName(lang))
    case None =>
      logger.warning("This applet ws built with an old version of dxWDL - please rebuild")
      // we will attempt to detect the language/version later
      None
    case other =>
      throw new Exception(s"unexpected ${Native.Language} value ${other}")
  }

  lazy val sourceCode: String = {
    val sourceCodeEncoded = getExecutableDetail(Native.SourceCode) match {
      case Some(JsString(s)) => s
      case None =>
        logger.warning("This applet ws built with an old version of dxWDL - please rebuild")
        val JsString(s) =
          getExecutableDetail("wdlSourceCode")
            .orElse(getExecutableDetail("womSourceCode"))
            .getOrElse(
                throw new Exception("executable details does not contain source code")
            )
        s
      case other =>
        throw new Exception(s"unexpected ${Native.SourceCode} value ${other}")
    }
    CompressionUtils.base64DecodeAndGunzip(sourceCodeEncoded)
  }

  lazy val instanceTypeDb: InstanceTypeDB = getExecutableDetail(Native.InstanceTypeDb) match {
    case Some(JsString(s)) =>
      val js = CompressionUtils.base64DecodeAndGunzip(s)
      js.parseJson.convertTo[InstanceTypeDB]
    case other =>
      throw new Exception(s"unexpected ${Native.InstanceTypeDb} value ${other}")
  }

  lazy val defaultRuntimeAttrs: Map[String, Value] =
    getExecutableDetail(Native.RuntimeAttributes) match {
      case Some(JsObject(fields)) => ValueSerde.deserializeMap(fields)
      case Some(JsNull) | None    => Map.empty
      case other =>
        throw new Exception(s"unexpected ${Native.RuntimeAttributes} value ${other}")
    }

  lazy val delayWorkspaceDestruction: Option[Boolean] =
    getExecutableDetail("delayWorkspaceDestruction") match {
      case Some(JsBoolean(flag)) => Some(flag)
      case None                  => None
    }

  lazy val blockPath: Vector[Int] = getExecutableDetail("blockPath") match {
    case Some(JsArray(arr)) if arr.nonEmpty =>
      arr.map {
        case JsNumber(n) => n.toInt
        case _           => throw new Exception("Bad value ${arr}")
      }
    case None =>
      Vector.empty
    case other =>
      throw new Exception(s"Bad value ${other}")
  }

  lazy val scatterStart: Int = getJobDetail(Native.ContinueStart) match {
    case Some(JsNumber(s)) => s.toIntExact
    case Some(other) =>
      throw new Exception(s"Invalid value ${other} for  ${Native.ContinueStart}")
    case _ => 0
  }

  lazy val scatterSize: Int = getExecutableDetail(Native.ScatterChunkSize) match {
    case Some(JsNumber(n)) => n.toIntExact
    case None              => Native.JobPerScatterDefault
    case other =>
      throw new Exception(s"Invalid value ${other} for ${Native.ScatterChunkSize}")
  }

  def error(e: Throwable): Unit
}

case class WorkerJobMeta(override val homeDir: Path = DxWorkerPaths.HomeDir,
                         override val dxApi: DxApi = DxApi.get,
                         override val logger: Logger = Logger.get)
    extends JobMeta(homeDir, dxApi, logger) {
  lazy val project: DxProject = dxApi.currentProject

  private val inputPath = homeDir.resolve(JobMeta.inputFile)
  private val outputPath = homeDir.resolve(JobMeta.outputFile)
  private val infoPath = homeDir.resolve(JobMeta.infoFile)

  lazy val jsInputs: Map[String, JsValue] = {
    if (Files.exists(inputPath)) {
      JsUtils.getFields(JsUtils.jsFromFile(inputPath))
    } else {
      logger.warning(s"input meta-file ${inputPath} does not exist")
      Map.empty
    }
  }

  def writeJsOutputs(outputJs: Map[String, JsValue]): Unit = {
    JsUtils.jsToFile(JsObject(outputJs), outputPath)
  }

  private lazy val info: Map[String, JsValue] = {
    if (Files.exists(infoPath)) {
      FileUtils.readFileContent(infoPath).parseJson.asJsObject.fields
    } else {
      logger.warning(s"info meta-file ${infoPath} does not exist")
      Map.empty
    }
  }

  def jobId: String = DxApi.get.currentJob.id

  private lazy val jobDesc: DxJobDescribe = DxApi.get.currentJob.describe(
      Set(Field.Executable, Field.Details, Field.InstanceType)
  )

  private lazy val jobDetails: Map[String, JsValue] =
    jobDesc.details.map(_.asJsObject.fields).getOrElse(Map.empty)

  private lazy val executable: DxExecutable = info.get("executable") match {
    case None =>
      logger.trace(
          "executable field not found locally, performing an API call.",
          minLevel = TraceLevel.None
      )
      jobDesc.executable.get
    case Some(JsString(x)) if x.startsWith("app-") =>
      dxApi.app(x)
    case Some(JsString(x)) if x.startsWith("applet-") =>
      dxApi.applet(x)
    case Some(other) =>
      throw new Exception(s"Malformed executable field ${other} in job info")
  }

  private lazy val executableDetails: Map[String, JsValue] =
    executable.describe(Set(Field.Details)).details.get.asJsObject.fields

  def analysis: Option[DxAnalysis] = jobDesc.analysis

  def parentJob: Option[DxJob] = jobDesc.parentJob

  def instanceType: Option[String] = jobDesc.instanceType

  def getJobDetail(name: String): Option[JsValue] = jobDetails.get(name)

  def getExecutableDetail(name: String): Option[JsValue] = executableDetails.get(name)

  def error(e: Throwable): Unit = {
    JobMeta.writeError(homeDir, e)
  }
}