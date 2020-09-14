package dx.translator

import java.nio.file.Path

import dx.api.{DxApi, DxFile, DxProject}
import dx.core.io.{DxFileAccessProtocol, DxFileDescCache}
import dx.core.ir.Type._
import dx.core.ir.{Bundle, Callable, Parameter, ParameterLinkSerializer, Type, Value, ValueSerde}
import dx.core.languages.Language.Language
import spray.json.{JsArray, JsNull, JsObject, JsString, JsValue}
import wdlTools.util.{FileSourceResolver, Logger}

/**
  * Tracks which keys are accessed in a map and ensures all keys are accessed exactly once.
  */
case class ExactlyOnce(name: String, fields: Map[String, JsValue], logger: Logger) {
  private var retrievedKeys: Set[String] = Set.empty

  def get(fqn: String): Option[JsValue] = {
    fields.get(fqn) match {
      case None =>
        logger.trace(s"getExactlyOnce ${fqn} => None")
        None
      case Some(v: JsValue) if retrievedKeys.contains(fqn) =>
        logger.trace(
            s"getExactlyOnce ${fqn} => Some(${v}); value already retrieved so returning None"
        )
        None
      case Some(v: JsValue) =>
        logger.trace(s"getExactlyOnce ${fqn} => Some(${v})")
        retrievedKeys += fqn
        Some(v)
    }
  }

  def checkAllUsed(): Unit = {
    val unused = fields.keySet -- retrievedKeys
    if (unused.nonEmpty) {
      throw new Exception(s"Could not map all ${name} fields. These were left: ${unused}")
    }
  }
}

abstract class InputFile(fields: Map[String, JsValue],
                         parameterLinkSerializer: ParameterLinkSerializer,
                         logger: Logger) {
  private val fieldsExactlyOnce = ExactlyOnce("input", fields, logger)
  private var irFields: Map[String, JsValue] = Map.empty

  protected def translateInput(parameter: Parameter, jsv: JsValue): Value

  // If WDL variable fully qualified name [fqn] was provided in the
  // input file, set [stage.cvar] to its JSON value
  def checkAndBind(fqn: String, dxName: String, parameter: Parameter): Unit = {
    fieldsExactlyOnce.get(fqn) match {
      case None      => ()
      case Some(jsv) =>
        // Do not assign the value to any later stages.
        // We found the variable declaration, the others
        // are variable uses.
        logger.trace(s"checkAndBind, found: ${fqn} -> ${dxName}")
        val irValue = translateInput(parameter, jsv)
        irFields ++= parameterLinkSerializer.createFields(
            dxName,
            parameter.dxType,
            irValue,
            encodeDots = false
        )
    }
  }

  def serialize: JsObject = {
    fieldsExactlyOnce.checkAllUsed()
    JsObject(irFields)
  }
}

/**
  * Translates a document in a supported workflow language to a Bundle.
  */
abstract class DocumentTranslator(fileResolver: FileSourceResolver = FileSourceResolver.get,
                                  dxApi: DxApi = DxApi.get) {

  private def extractDxFiles(t: Type, jsv: JsValue): Vector[JsValue] = {
    (t, jsv) match {
      case (TOptional(_), JsNull)   => Vector.empty
      case (TOptional(inner), _)    => extractDxFiles(inner, jsv)
      case (TFile, jsv)             => Vector(jsv)
      case _ if Type.isPrimitive(t) => Vector.empty

      case (TArray(elementType, _), JsArray(array)) =>
        array.flatMap(element => extractDxFiles(elementType, element))

      // Maps may be serialized as an object with a keys array and a values array.
      case (TMap(keyType, valueType), JsObject(fields)) if ValueSerde.isMapObject(jsv) =>
        val keys = fields("keys") match {
          case JsArray(keys) => keys.flatMap(k => extractDxFiles(keyType, k))
          case other         => throw new Exception(s"invalid map keys ${other}")
        }
        val values = fields("values") match {
          case JsArray(keys) => keys.flatMap(v => extractDxFiles(valueType, v))
          case other         => throw new Exception(s"invalid map keys ${other}")
        }
        keys ++ values

      // Maps with String keys may also be serialized as an object
      case (TMap(keyType, valueType), JsObject(fields)) =>
        val keys = fields.keys.flatMap(k => extractDxFiles(keyType, JsString(k))).toVector
        val values = fields.values.flatMap(v => extractDxFiles(valueType, v)).toVector
        keys ++ values

      case (TSchema(name, members), JsObject(fields)) =>
        members.flatMap {
          case (memberName, memberType) =>
            fields.get(memberName) match {
              case Some(jsv)                           => extractDxFiles(memberType, jsv)
              case None if Type.isOptional(memberType) => Vector.empty
              case _ =>
                throw new Exception(s"missing value for struct ${name} member ${memberName}")
            }
        }.toVector

      case (THash, JsObject(_)) =>
        // anonymous objects will never result in file-typed members, so just skip these
        Vector.empty

      case _ =>
        throw new Exception(s"value ${jsv} cannot be deserialized to ${t}")
    }
  }

  protected def fileResolverWithCachedFiles(
      bundle: Bundle,
      inputs: Map[String, JsValue],
      project: DxProject
  ): FileSourceResolver = {
    val fileJs = bundle.allCallables.values.toVector.flatMap { callable: Callable =>
      callable.inputVars.flatMap { param =>
        val fqn = s"${callable.name}.${param.name}"
        inputs
          .get(fqn)
          .map(jsv => extractDxFiles(param.dxType, jsv))
          .getOrElse(Vector.empty)
      }
    }
    val (dxFiles, dxPaths) = fileJs.foldLeft((Vector.empty[DxFile], Vector.empty[String])) {
      case ((files, paths), obj: JsObject) =>
        (files :+ DxFile.fromJson(dxApi, obj), paths)
      case ((files, paths), JsString(path)) =>
        (files, paths :+ path)
      case other =>
        throw new Exception(s"invalid file ${other}")
    }
    val resolvedPaths = dxApi
      .resolveBulk(dxPaths, project)
      .map {
        case (key, dxFile: DxFile) => key -> dxFile
        case (_, dxobj) =>
          throw new Exception(s"Scanning the input file produced ${dxobj} which is not a file")
      }
    // lookup platform files in bulk
    val described = dxApi.fileBulkDescribe(dxFiles ++ resolvedPaths.values)
    val dxProtocol = DxFileAccessProtocol(dxApi, DxFileDescCache(described))
    fileResolver.replaceProtocol[DxFileAccessProtocol](dxProtocol)
  }

  def bundle: Bundle

  def inputFiles: Map[Path, InputFile]
}

trait DocumentTranslatorFactory {
  def create(sourceFile: Path,
             language: Option[Language],
             inputs: Vector[Path],
             defaults: Option[Path],
             locked: Boolean,
             defaultRuntimeAttrs: Map[String, Value],
             reorgAttrs: ReorgAttributes,
             project: DxProject,
             fileResolver: FileSourceResolver): Option[DocumentTranslator]
}