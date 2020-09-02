package dx.core.languages.wdl

import wdlTools.types.{TypedAbstractSyntax => TAT, Utils => TUtil}

// TODO: I think this is redundant with wdlTools.generators.code.WdlV1Generator
object PrettyPrintApprox {
  def applyWorkflowElement(node: TAT.WorkflowElement, indent: String): String = {
    node match {
      case TAT.Scatter(varName, expr, body, _) =>
        val collection = TUtil.prettyFormatExpr(expr)
        val innerBlock = body
          .map { node =>
            applyWorkflowElement(node, indent + "  ")
          }
          .mkString("\n")
        s"""|${indent}scatter (${varName} in ${collection}) {
            |${innerBlock}
            |${indent}}
            |""".stripMargin

      case TAT.Conditional(expr, body, _) =>
        val innerBlock =
          body
            .map { node =>
              applyWorkflowElement(node, indent + "  ")
            }
            .mkString("\n")
        s"""|${indent}if (${TUtil.prettyFormatExpr(expr)}) {
            |${innerBlock}
            |${indent}}
            |""".stripMargin

      case call: TAT.Call =>
        val inputNames = call.inputs
          .map {
            case (key, expr) =>
              s"${key} = ${TUtil.prettyFormatExpr(expr)}"
          }
          .mkString(",")
        val inputs =
          if (inputNames.isEmpty) ""
          else s"{ input: ${inputNames} }"
        call.alias match {
          case None =>
            s"${indent}call ${call.fullyQualifiedName} ${inputs}"
          case Some(al) =>
            s"${indent}call ${call.fullyQualifiedName} as ${al} ${inputs}"
        }

      case TAT.Declaration(_, wdlType, None, _) =>
        s"${indent} ${TUtil.prettyFormatType(wdlType)}"
      case TAT.Declaration(_, wdlType, Some(expr), _) =>
        s"${indent} ${TUtil.prettyFormatType(wdlType)} = ${TUtil.prettyFormatExpr(expr)}"
    }
  }

  private def applyInput(iDef: TAT.InputDefinition): String = {
    iDef match {
      case TAT.RequiredInputDefinition(iName, wdlType, _) =>
        s"${TUtil.prettyFormatType(wdlType)} ${iName}"

      case TAT.OverridableInputDefinitionWithDefault(iName, wdlType, defaultExpr, _) =>
        s"${TUtil.prettyFormatType(wdlType)} ${iName} = ${TUtil.prettyFormatExpr(defaultExpr)}"

      case TAT.OptionalInputDefinition(iName, wdlType, _) =>
        s"${TUtil.prettyFormatType(wdlType)} ${iName}"
    }
  }

  def graphInputs(inputDefs: Seq[TAT.InputDefinition]): String = {
    inputDefs.map(applyInput).mkString("\n")
  }

  def graphOutputs(outputs: Seq[TAT.OutputDefinition]): String = {
    outputs
      .map {
        case TAT.OutputDefinition(name, wdlType, expr, _) =>
          s"${TUtil.prettyFormatType(wdlType)} ${name} = ${TUtil.prettyFormatExpr(expr)}"
      }
      .mkString("\n")
  }

  def block(block: Block): String = {
    block.nodes
      .map { applyWorkflowElement(_, "    ") }
      .mkString("\n")
  }

  def apply(elements: Vector[TAT.WorkflowElement]): String = {
    elements.map(x => applyWorkflowElement(x, "  ")).mkString("\n")
  }
}
