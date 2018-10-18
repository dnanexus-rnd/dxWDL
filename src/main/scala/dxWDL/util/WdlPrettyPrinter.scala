/**
  *  Print a WDL class as a valid, human readable,
  *  textual string. The output is then palatable to
  *  the WDL parser. The printing process is configurable. For example:
  *  to print fully qualified names use:
  *
  *  val pp = new WdlPrettyPrinter(true)
  *  pp.apply(x)
  *
  * TODO: for an unknown reason, the pretty printer mangles workflow
  * outputs. The work around is to pass the original workflow outputs
  * unmodified.
  */
package dxWDL

import scala.util.{Failure, Success}
import Utils.{COMMAND_DEFAULT_BRACKETS, COMMAND_HEREDOC_BRACKETS}
import wdl.draft2.model._
import wdl.draft2.model.AstTools.EnhancedAstNode
import wdl.draft2.model.command.{WdlCommandPart, ParameterCommandPart, StringCommandPart}


case class WdlPrettyPrinter(fqnFlag: Boolean) {
    private val I_STEP = 4

    // Create an indentation of [n] spaces
    private def genNSpaces(n: Int) = {
        s"${" " * n}"
    }

    private def escapeChars(buf: String) : String = {
        // TODO: this global search-replace will not work for
        // all expressions.
        buf.replaceAll("""\\""", """\\\\""")
    }

    private def orgExpression(expr: WdlExpression) : String = {
        try {
            escapeChars(expr.toWomString)
        } catch {
            case e: Throwable =>
                throw new Exception(s"""|Error converting an expression to a string
                                        |
                                        |${expr}
                                        |
                                        |${e.getMessage}
                                        |""".stripMargin)
                //throw e
        }
    }

    // indent a line by [level] steps
    def indentLine(line: String, indentLevel: Int) = {
        if (line == "\n") {
            line
        } else {
            val spaces = genNSpaces(indentLevel * I_STEP)
            spaces + line
        }
    }

    // All blocks except for task command.
    //
    // The top and bottom lines are indented, the middle lines must
    // already have the correct indentation.
    def buildBlock(top: String,
                   middle: Vector[String],
                   level: Int,
                   force: Boolean = false) : Vector[String] = {
        if (force || !middle.isEmpty) {
            val firstLine = indentLine(s"${top} {", level)
            val endLine = indentLine("}", level)
            firstLine +: middle :+ endLine
        } else {
            Vector.empty
        }
    }

    // The command block is special because spaces and tabs must be
    // faithfully preserved. There are shell commands that are
    // sensitive to white space and tabs.
    //
    def buildCommandBlock(commandTemplate: Seq[WdlCommandPart],
                          bracketSymbols: (String,String),
                          level: Int) : Vector[String] = {
        val command: String = commandTemplate.map {part =>
            part match  {
                case x:ParameterCommandPart => x.toString()
                case x:StringCommandPart => x.toString()
            }
        }.mkString("")

        // remove empty lines; we are not sure how they are generated, but they mess
        // up pretty printing downstream.
        val nonEmptyLines: Vector[String] =
            command
                .split("\n")
                .filter(l => !l.trim().isEmpty)
                .toVector

        val firstLine = indentLine(s"command {", level)
        val endLine = indentLine("}", level)
        firstLine +: nonEmptyLines :+ endLine
    }


    def apply(tso: TaskOutput, level: Int): Vector[String] = {
        val ln = s"""|${tso.womType.toDisplayString} ${tso.unqualifiedName} =
                     |${orgExpression(tso.requiredExpression)}"""
            .stripMargin.replaceAll("\n", " ").trim
        Vector(indentLine(ln, level))
    }

    private def buildTask(task: CallableTaskDefinition,
                          level:Int) : Vector[String] = {
        // We are only dealing with vanilla WDL tasks. Make sure that
        // is the case.
        assert(task.adHocFileCreation.isEmpty)
        assert(task.prefixSeparator == ".")
        assert(task.commandPartSeparator == "")
        assert(task.stdinRedirection == None)
        assert(task.stdoutOverride == None)
        assert(task.stderrOverride == None)
        assert(task.additionalGlob == None)
        assert(task.homeOverride == None)
        assert(task.dockerOutputDirectory == None)


                                        commandTemplateBuilder: WomEvaluatedCallInputs => ErrorOr[Seq[CommandPart]],
                                        runtimeAttributes: RuntimeAttributes,
                                        meta: Map[String, String],
                                        parameterMeta: Map[String, String],
                                        outputs: List[Callable.OutputDefinition],
                                        inputs: List[_ <: Callable.InputDefinition],
                                        environmentExpressions: Map[String, WomExpression],

        val decls = task.declarations.map(x => apply(x, level + 1)).flatten.toVector
        val runtime = task.runtimeAttributes.attrs.map{ case (key, expr) =>
            indentLine(s"${key}: ${orgExpression(expr)}", level + 2)
        }.toVector
        val outputs = task.outputs.map(x => apply(x, level + 2)).flatten.toVector
        val paramMeta = task.parameterMeta.map{ case (x,y) =>
            indentLine(s"""${x}: "${y}" """, level + 2)
        }.toVector
        val meta = task.meta.map{ case (x,y) =>
            indentLine(s"""${x}: "${y}" """, level + 2)
        }.toVector
        val body = decls ++
            buildCommandBlock(task.commandTemplate, level + 1) ++
            buildBlock("runtime", runtime, level + 1) ++
            buildBlock("output", outputs, level + 1) ++
            buildBlock("parameter_meta", paramMeta, level + 1) ++
            buildBlock("meta", meta, level + 1)

        buildBlock(s"task ${task.name}", body, level)
    }

    def apply(task: CallableTaskDefinition) : String = {
        val lines = buildTask(task, 0)
        lines.mkString("\n")
    }
}
