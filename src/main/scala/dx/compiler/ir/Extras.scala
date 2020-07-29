package dx.compiler.ir

// Place to put any extra options, equivalent to Cromwell workflowOptions.
// Also, allows dnanexus specific configuration per task.

import java.nio.file.Path

import dx.api.{DxAccessLevel, DxApi, DxApp, DxApplet, Field}
import dx.core.io.DxFileAccessProtocol
import spray.json.DefaultJsonProtocol._
import spray.json._
import wdlTools.util.{JsUtils, Logger}

case class DxExecPolicy(restartOn: Option[Map[String, Int]], maxRestarts: Option[Int]) {
  def toJson: Map[String, JsValue] = {
    val restartOnFields = restartOn match {
      case None => Map.empty
      case Some(m) =>
        val jsm = m.map { case (name, i) => name -> JsNumber(i) }
        Map("restartOn" -> JsObject(jsm))
    }
    val maxRestartsFields = maxRestarts match {
      case None    => Map.empty
      case Some(n) => Map("maxRestarts" -> JsNumber(n))
    }
    val m = restartOnFields ++ maxRestartsFields
    if (m.isEmpty) {
      Map.empty
    } else {
      Map("executionPolicy" -> JsObject(m.toMap))
    }
  }
}

case class DxTimeout(days: Option[Int], hours: Option[Int], minutes: Option[Int]) {
  def toJson: Map[String, JsValue] = {
    val daysField: Map[String, JsValue] = days match {
      case None    => Map.empty
      case Some(d) => Map("days" -> JsNumber(d))
    }
    val hoursField: Map[String, JsValue] = hours match {
      case None    => Map.empty
      case Some(d) => Map("hours" -> JsNumber(d))
    }
    val minutesField: Map[String, JsValue] = minutes match {
      case None    => Map.empty
      case Some(d) => Map("minutes" -> JsNumber(d))
    }
    val timeoutFields = daysField ++ hoursField ++ minutesField
    if (timeoutFields.isEmpty) {
      Map.empty
    } else {
      Map("timeoutPolicy" -> JsObject("*" -> JsObject(timeoutFields)))
    }
  }
}

case class DxAccess(network: Option[Vector[String]],
                    project: Option[DxAccessLevel],
                    allProjects: Option[DxAccessLevel],
                    developer: Option[Boolean],
                    projectCreation: Option[Boolean]) {
  def toJson: Map[String, JsValue] = {
    val networkField: Map[String, JsValue] = network match {
      case None => Map.empty
      case Some(hostsAndNetworks) =>
        Map("network" -> JsArray(hostsAndNetworks.map(x => JsString(x))))
    }
    val projectField: Map[String, JsValue] = project match {
      case None    => Map.empty
      case Some(x) => Map("project" -> JsString(x.name))
    }
    val allProjectsField: Map[String, JsValue] = allProjects match {
      case None    => Map.empty
      case Some(x) => Map("allProjects" -> JsString(x.name))
    }
    val developerField: Map[String, JsValue] = developer match {
      case None    => Map.empty
      case Some(x) => Map("developer" -> JsBoolean(x))
    }
    val projectCreationField: Map[String, JsValue] = projectCreation match {
      case None    => Map.empty
      case Some(x) => Map("projectCreation" -> JsBoolean(x))
    }
    networkField ++ projectField ++ allProjectsField ++ developerField ++ projectCreationField
  }

  // Merge with an additional set of access requirments.
  // Take the maximum for each field, and merge the network.
  def merge(dxa: DxAccess): DxAccess = {
    def mergeAccessLevels(al1: Option[DxAccessLevel],
                          al2: Option[DxAccessLevel]): Option[DxAccessLevel] = {
      Vector(al1, al2).flatten match {
        case Vector(x)    => Some(x)
        case Vector(x, y) => Some(Vector(x, y).max)
        case _            => None
      }
    }
    def mergeBooleans(a: Option[Boolean], b: Option[Boolean]): Option[Boolean] = {
      Vector(a, b).flatten match {
        case Vector(x)    => Some(x)
        case Vector(x, y) => Some(x || y)
        case _            => None
      }
    }

    val networkMrg = Vector(network, dxa.network).flatten match {
      case Vector(x)    => Some(x)
      case Vector(x, y) => Some((x.toSet ++ y.toSet).toVector)
      case _            => None
    }
    val projectMrg = mergeAccessLevels(project, dxa.project)
    val allProjectsMrg = mergeAccessLevels(allProjects, dxa.allProjects)
    val developerMrg = mergeBooleans(developer, dxa.developer)
    val projectCreationMrg = mergeBooleans(projectCreation, dxa.projectCreation)

    DxAccess(networkMrg, projectMrg, allProjectsMrg, developerMrg, projectCreationMrg)
  }
}

object DxAccess {
  val empty: DxAccess = DxAccess(None, None, None, None, None)
}

case class DxRunSpec(access: Option[DxAccess],
                     execPolicy: Option[DxExecPolicy],
                     restartableEntryPoints: Option[String],
                     timeoutPolicy: Option[DxTimeout]) {
  // The access field DOES NOT go into the run-specification in the API
  // call. It does fit, however, in dxapp.json.
  def toRunSpecJson: Map[String, JsValue] = {
    val execPolicyFields = execPolicy match {
      case None => Map.empty
      case Some(execPolicy) =>
        execPolicy.toJson
    }
    val restartableEntryPointsFields = restartableEntryPoints match {
      case None                => Map.empty
      case Some(value: String) => Map("restartableEntryPoints" -> JsString(value))
    }
    val timeoutFields = timeoutPolicy match {
      case None             => Map.empty
      case Some(execPolicy) => execPolicy.toJson
    }
    (execPolicyFields ++ restartableEntryPointsFields).toMap ++ timeoutFields
  }
}

case class DxLicense(name: String,
                     repoUrl: String,
                     version: String,
                     license: String,
                     licenseUrl: String,
                     author: String)

case class DxDetails(upstreamProjects: Option[Vector[DxLicense]]) {
  def toDetailsJson: Map[String, JsValue] = {
    val upstreamProjectVec: Vector[JsObject] = upstreamProjects match {
      case None => Vector.empty
      case Some(x) =>
        x.map { dxLicense: DxLicense =>
          JsObject(
              "name" -> JsString(dxLicense.name),
              "repoUrl" -> JsString(dxLicense.repoUrl),
              "version" -> JsString(dxLicense.version),
              "license" -> JsString(dxLicense.license),
              "licenseUrl" -> JsString(dxLicense.licenseUrl),
              "author" -> JsString(dxLicense.author)
          )
        }
    }
    Map("upstreamProjects" -> upstreamProjectVec.toJson)
  }
}

// The available fields are:
//    dx_instance_type
//    memory
//    disks
//    cpu
//    docker
case class RuntimeAttributes(value: Map[String, Value])

// support automatic conversion to/from JsValue
object RuntimeAttributes extends DefaultJsonProtocol {
  lazy val empty: RuntimeAttributes = RuntimeAttributes(Map.empty)

  implicit object RuntimeAttributesFormat extends RootJsonFormat[RuntimeAttributes] {
    def read(jsValues: JsValue): RuntimeAttributes = {
      val m = jsValues.asJsObject.fields.map { case (k, v) => k -> Value.fromJsPrimitive(v) }
      RuntimeAttributes(m)
    }

    def write(irValues: RuntimeAttributes): JsValue = {
      val fields = irValues.value.map {
        case (k, v) =>
          k -> Value.toJsPrimitive(v)
      }
      JsObject(fields)
    }
  }
}

case class DxAppJson(runSpec: Option[DxRunSpec], details: Option[DxDetails]) {
  def getRunSpecJson: Map[String, JsValue] = {
    val runSpecJson: Map[String, JsValue] = runSpec match {
      case None          => Map.empty
      case Some(runSpec) => runSpec.toRunSpecJson
    }
    runSpecJson
  }

  def getDetailsJson: Map[String, JsValue] = {
    val detailsJson: Map[String, JsValue] = details match {
      case None          => Map.empty
      case Some(details) => details.toDetailsJson
    }
    detailsJson
  }
}

case class DockerRegistry(registry: String, username: String, credentials: String)

case class CustomReorgAttributes(appUri: String, reorgConfigFile: Option[String])

case class Extras(defaultRuntimeAttributes: RuntimeAttributes,
                  defaultTaskDxAttributes: Option[DxAppJson],
                  perTaskDxAttributes: Map[String, DxAppJson],
                  dockerRegistry: Option[DockerRegistry],
                  customReorgAttributes: Option[CustomReorgAttributes],
                  ignoreReuse: Option[Boolean],
                  delayWorkspaceDestruction: Option[Boolean]) {
  def getDefaultAccess: DxAccess = {
    defaultTaskDxAttributes match {
      case None => DxAccess.empty
      case Some(dta) =>
        dta.runSpec match {
          case None => DxAccess.empty
          case Some(runSpec) =>
            runSpec.access match {
              case None         => DxAccess.empty
              case Some(access) => access
            }
        }
    }
  }

  def getTaskAccess(taskName: String): DxAccess = {
    perTaskDxAttributes.get(taskName) match {
      case None => DxAccess.empty
      case Some(dta) =>
        dta.runSpec match {
          case None => DxAccess.empty
          case Some(runSpec) =>
            runSpec.access match {
              case None         => DxAccess.empty
              case Some(access) => access
            }
        }
    }
  }
}

case class ExtrasParser(dxApi: DxApi = DxApi.get, logger: Logger = Logger.get) {
  private val DOCKER_REGISTRY_ATTRS = Set("username", "registry", "credentials")
  private val CUSTOM_REORG_ATTRS = Set("app_id", "conf")
  private val EXTRA_ATTRS = Set(
      "default_runtime_attributes",
      "default_task_dx_attributes",
      "per_task_dx_attributes",
      "docker_registry",
      "custom_reorg",
      "ignoreReuse",
      "delayWorkspaceDestruction"
  )
  private val RUNTIME_ATTRS =
    Set("dx_instance_type", "memory", "disks", "cpu", "docker", "docker_registry", "custom_reorg")
  private val RUN_SPEC_ATTRS =
    Set("access", "executionPolicy", "restartableEntryPoints", "timeoutPolicy")
  private val RUN_SPEC_ACCESS_ATTRS =
    Set("network", "project", "allProjects", "developer", "projectCreation")
  private val RUN_SPEC_TIMEOUT_ATTRS = Set("days", "hours", "minutes")
  private val RUN_SPEC_EXEC_POLICY_ATTRS = Set("restartOn", "maxRestarts")
  private val RUN_SPEC_EXEC_POLICY_RESTART_ON_ATTRS = Set("ExecutionError",
                                                          "UnresponsiveWorker",
                                                          "JMInternalError",
                                                          "AppInternalError",
                                                          "JobTimeoutExceeded",
                                                          "*")
  private val TASK_DX_ATTRS = Set("runSpec", "details")
  private val DX_DETAILS_ATTRS = Set("upstreamProjects")
  private val PER_TASK_KEY = "per_task_dx_attributes"

  private def checkedParseIntField(fields: Map[String, JsValue], fieldName: String): Option[Int] = {
    fields.get(fieldName) match {
      case None                => None
      case Some(JsNumber(bnm)) => Some(bnm.intValue)
      case Some(other)         => throw new Exception(s"Malformed ${fieldName} (${other})")
    }
  }

  private def checkedParseStringField(fields: Map[String, JsValue],
                                      fieldName: String): Option[String] = {
    fields.get(fieldName) match {
      case None                => None
      case Some(JsString(str)) => Some(str)
      case Some(other)         => throw new Exception(s"Malformed ${fieldName} (${other})")
    }
  }

  private def checkedParseStringFieldReplaceNull(fields: Map[String, JsValue],
                                                 fieldName: String): Option[String] = {
    fields.get(fieldName) match {
      case None                => None
      case Some(JsString(str)) => Some(str)
      case Some(JsNull)        => Some("")
      case Some(other)         => throw new Exception(s"Malformed ${fieldName} (${other})")
    }
  }

  private def checkedParseStringArrayField(fields: Map[String, JsValue],
                                           fieldName: String): Option[Vector[String]] = {
    fields.get(fieldName) match {
      case None => None
      case Some(JsArray(vec)) =>
        val v = vec.map {
          case JsString(str) => str
          case other         => throw new Exception(s"Malformed ${fieldName} (${other})")
        }
        Some(v)
      case Some(other) => throw new Exception(s"Malformed ${fieldName} (${other})")
    }
  }

  private def checkedParseBooleanField(fields: Map[String, JsValue],
                                       fieldName: String): Option[Boolean] = {
    fields.get(fieldName) match {
      case None               => None
      case Some(JsBoolean(b)) => Some(b)
      case Some(other)        => throw new Exception(s"Malformed ${fieldName} (${other})")
    }
  }

  private def checkedParseAccessLevelField(fields: Map[String, JsValue],
                                           fieldName: String): Option[DxAccessLevel] = {
    checkedParseStringField(fields, fieldName) match {
      case None    => None
      case Some(s) => Some(DxAccessLevel.withName(s))
    }
  }

  private def checkedParseObjectField(fields: Map[String, JsValue], fieldName: String): JsValue = {
    fields.get(fieldName) match {
      case None                   => JsNull
      case Some(JsObject(fields)) => JsObject(fields)
      case Some(other)            => throw new Exception(s"Malformed ${fieldName} (${other})")
    }
  }

  private def checkedParseMapStringInt(fields: Map[String, JsValue],
                                       fieldName: String): Option[Map[String, Int]] = {
    val m = checkedParseObjectField(fields, fieldName)
    if (m == JsNull) {
      return None
    }
    val m1 = m.asJsObject.fields.map {
      case (name, JsNumber(nmb)) => name -> nmb.intValue
      case (name, other)         => throw new Exception(s"Malformed ${fieldName} (${name} -> ${other})")
    }
    Some(m1)
  }

  private def parseWdlRuntimeAttrs(jsv: JsValue): RuntimeAttributes = {
    if (jsv == JsNull)
      return RuntimeAttributes(Map.empty)
    val fields = jsv.asJsObject.fields
    for (k <- fields.keys) {
      if (!(RUNTIME_ATTRS contains k))
        logger.warning(
            s"""|Unsupported runtime attribute ${k},
                |we currently support ${RUNTIME_ATTRS}
                |""".stripMargin.replaceAll("\n", "")
        )
    }

    def wdlValueFromJsValue(jsValue: JsValue): Value = {
      Value.fromJsPrimitive(jsValue)
    }
    val attrs = fields
      .filter { case (key, _) => RUNTIME_ATTRS contains key }
      .map {
        case (name, jsValue) =>
          name -> wdlValueFromJsValue(jsValue)
      }
    RuntimeAttributes(attrs)
  }

  private def parseExecutionPolicy(jsv: JsValue): Option[DxExecPolicy] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !RUN_SPEC_EXEC_POLICY_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported runSpec.access attribute(s) ${invalid},
                              |we currently support ${RUN_SPEC_EXEC_POLICY_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))
    }
    val restartOn = checkedParseMapStringInt(fields, "restartOn")
    restartOn match {
      case None => ()
      case Some(restartOnPolicy) =>
        for (k <- restartOnPolicy.keys)
          if (!(RUN_SPEC_EXEC_POLICY_RESTART_ON_ATTRS contains k))
            throw new Exception(s"unknown field ${k} in restart policy")
    }
    val maxRestarts = checkedParseIntField(fields, "maxRestarts")
    Some(DxExecPolicy(restartOn, maxRestarts))
  }

  private def parseAccess(jsv: JsValue): Option[DxAccess] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !RUN_SPEC_ACCESS_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported runSpec.access attribute(s) ${invalid},
                              |we currently support ${RUN_SPEC_ACCESS_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))
    }
    Some(
        DxAccess(
            checkedParseStringArrayField(fields, "network"),
            checkedParseAccessLevelField(fields, "project"),
            checkedParseAccessLevelField(fields, "allProjects"),
            checkedParseBooleanField(fields, "developer"),
            checkedParseBooleanField(fields, "projectCreation")
        )
    )
  }

  // Only timeouts of the following format are supported:
  //  "timeoutPolicy": {
  //    "*": {
  //      "hours": 12
  //    }
  //  }
  private def parseTimeoutPolicy(jsv: JsValue): Option[DxTimeout] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    if (fields.keys.size != 1) {
      throw new Exception("Exactly one entry-point timeout can be specified")
    }
    val key = fields.keys.head
    if (key != "*") {
      throw new Exception("""Only a general timeout for all entry points is supported ("*")""")
    }
    val subObj = checkedParseObjectField(fields, "*")
    if (subObj == JsNull) {
      return None
    }
    val subFields = subObj.asJsObject.fields
    val invalid = subFields.keys.collect {
      case k if !RUN_SPEC_TIMEOUT_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported runSpec.timeoutPolicy attribute(s) ${invalid},
                              |we currently support ${RUN_SPEC_TIMEOUT_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))
    }
    Some(
        DxTimeout(checkedParseIntField(subFields, "days"),
                  checkedParseIntField(subFields, "hours"),
                  checkedParseIntField(subFields, "minutes"))
    )
  }

  private def parseRunSpec(jsv: JsValue): Option[DxRunSpec] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !RUN_SPEC_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported runSpec attribute(s) ${invalid},
                              |we currently support ${RUN_SPEC_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))

    }
    val restartable: Option[String] =
      checkedParseStringField(fields, "restartableEntryPoints") match {
        case None                                           => None
        case Some(str) if Set("all", "master") contains str => Some(str)
        case Some(str)                                      => throw new Exception(s"Unsupported restartableEntryPoints value ${str}")
      }
    Some(
        DxRunSpec(
            parseAccess(checkedParseObjectField(fields, "access")),
            parseExecutionPolicy(checkedParseObjectField(fields, "executionPolicy")),
            restartable,
            parseTimeoutPolicy(checkedParseObjectField(fields, "timeoutPolicy"))
        )
    )
  }

  private def parseUpstreamProjects(jsv: Option[JsValue]): Option[Vector[DxLicense]] = {
    jsv match {
      case None         => None
      case Some(JsNull) => None
      case Some(other) =>
        implicit val dxLicenseFormat: RootJsonFormat[DxLicense] = jsonFormat6(DxLicense)
        Some(other.convertTo[Vector[DxLicense]])
    }
  }

  private def parseDxDetails(jsv: JsValue): Option[DxDetails] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !DX_DETAILS_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported details attribute(s) ${invalid},
                              |we currently support ${DX_DETAILS_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))
    }
    Some(DxDetails(parseUpstreamProjects(fields.get("upstreamProjects"))))
  }

  private def parseTaskDxAttrs(jsv: JsValue): Option[DxAppJson] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !TASK_DX_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported runtime attribute(s) ${invalid},
                              |we currently support ${TASK_DX_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))
    }
    val runSpec = parseRunSpec(checkedParseObjectField(fields, "runSpec"))
    val details = parseDxDetails(checkedParseObjectField(fields, "details"))
    Some(DxAppJson(runSpec, details))
  }

  private def parseDockerRegistry(jsv: JsValue): Option[DockerRegistry] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !DOCKER_REGISTRY_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported docker registry attribute(s) ${invalid},
                              |we currently support ${DOCKER_REGISTRY_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))

    }
    def getSome(fieldName: String): String = {
      checkedParseStringField(fields, fieldName) match {
        case None    => throw new Exception(s"${fieldName} must be specified in the docker section")
        case Some(x) => x
      }
    }
    val registry = getSome("registry")
    val username = getSome("username")
    val credentials = getSome("credentials")
    Some(DockerRegistry(registry, username, credentials))
  }

  def parseCustomReorgAttrs(jsv: JsValue): Option[CustomReorgAttributes] = {
    if (jsv == JsNull) {
      return None
    }
    val fields = jsv.asJsObject.fields
    val invalid = fields.keys.collect {
      case k if !CUSTOM_REORG_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new IllegalArgumentException(s"""|Unsupported custom reorg attribute(s) ${invalid},
                                             |we currently support ${CUSTOM_REORG_ATTRS}
                                             |""".stripMargin.replaceAll("\n", ""))
    }
    val reorgAppId: String = checkedParseStringField(fields, "app_id") match {
      case None =>
        throw new IllegalArgumentException("app_id must be specified in the custom_reorg section.")
      case Some(x) => x
    }
    val access: Option[JsValue] =
      try {
        dxApi.getObject(reorgAppId) match {
          case app: DxApp =>
            val desc = app.describe(Set(Field.Access))
            desc.access
          case applet: DxApplet =>
            val desc = applet.describe(Set(Field.Access))
            desc.access
          case _ =>
            throw new Exception(s"Object ${reorgAppId} is not an app or applet")
        }
      } catch {
        case t: Throwable =>
          throw new IllegalArgumentException(s"Invalid reorg app(let) ID ${reorgAppId}", t)
      }
    val hasAccess = access match {
      case Some(JsObject(x)) =>
        JsObject(x).fields.get("project") match {
          case Some(JsString("CONTRIBUTE")) | Some(JsString("ADMINISTER")) => true
          case _                                                           => false
        }
      case _ => false
    }
    if (!hasAccess) {
      throw new dx.PermissionDeniedException(
          s"""ERROR: App(let) for custom reorg stage ${reorgAppId} does not 
             |have CONTRIBUTOR or ADMINISTRATOR access and this is required.""".stripMargin
      )
    }
    val reorgConf: Option[String] = checkedParseStringFieldReplaceNull(fields, "conf") match {
      case Some(uri) if uri.trim.isEmpty                                        => None
      case Some(uri) if uri.trim.startsWith(DxFileAccessProtocol.DX_URI_PREFIX) =>
        // if provided, check that the fileID is valid and present
        // format dx file ID
        val reorgFileID: String = uri.trim.replace(DxFileAccessProtocol.DX_URI_PREFIX, "")
        // if input file ID is invalid, DxFile.getInstance will thow an IllegalArgumentException
        // if reorgFileID cannot be found, describe will throw a ResourceNotFoundException
        logger.ignore(dxApi.file(reorgFileID).describe())
        Some(uri)
      case _ =>
        throw new IllegalArgumentException(
            """In the 'custom_reorg' section of extras, 'conf' must be specified as 
              |a valid DNAnexus file in the form 'dx://file-XXX'. Please set the value 
              |to null if there is no configuration file.""".stripMargin
        )
    }
    logger.trace(
        s"""|Writing your own applet for reorganization purposes is tricky. If you are not careful,
            |it may misplace or outright delete files.
            |
            |The applet ${reorgAppId} requires CONTRIBUTE project access so it can move files and
            |folders. The applet must be idempotent, so that if the instance it runs on crashes, 
            |it can safely restart. It must also be careful about inputs that are also outputs: 
            |generally, these should not be moved. It should use bulk object operations, so as
            |not to overload the API server.
            |
            |You may refer to this example:
            |https://github.com/dnanexus/dxWDL/blob/master/doc/ExpertOptions.md#use-your-own-applet
            """.stripMargin.replaceAll("\n", " ")
    )
    Some(ReorgOptions(reorgAppId, reorgConf))
  }

  def parse(path: Path): Extras = {
    parse(JsUtils.jsFromFile(path))
  }

  def parse(jsv: JsValue): Extras = {
    val fields = jsv match {
      case JsObject(fields) => fields
      case _                => throw new Exception(s"malformed extras JSON ${jsv}")
    }
    val invalid = fields.keys.collect {
      case k if !EXTRA_ATTRS.contains(k) => k
    }
    if (invalid.nonEmpty) {
      throw new Exception(s"""|Unsupported extra option(s) ${invalid},
                              |we currently support ${EXTRA_ATTRS}
                              |""".stripMargin.replaceAll("\n", ""))
    }
    // parse the individual task dx attributes
    val perTaskDxAttrs: Map[String, DxAppJson] =
      checkedParseObjectField(fields, PER_TASK_KEY) match {
        case JsNull =>
          Map.empty
        case jsObj =>
          val fields = jsObj.asJsObject.fields
          fields.foldLeft(Map.empty[String, DxAppJson]) {
            case (accu, (name, jsValue)) =>
              parseTaskDxAttrs(jsValue) match {
                case None =>
                  accu
                case Some(attrs) =>
                  accu + (name -> attrs)
              }
          }
      }

    Extras(
        parseWdlRuntimeAttrs(
            checkedParseObjectField(fields, "default_runtime_attributes")
        ),
        parseTaskDxAttrs(checkedParseObjectField(fields, "default_task_dx_attributes")),
        perTaskDxAttrs,
        parseDockerRegistry(checkedParseObjectField(fields, "docker_registry")),
        parseCustomReorgAttrs(
            checkedParseObjectField(fields, fieldName = "custom_reorg")
        ),
        checkedParseBooleanField(fields, fieldName = "ignoreReuse"),
        checkedParseBooleanField(fields, fieldName = "delayWorkspaceDestruction")
    )
  }
}