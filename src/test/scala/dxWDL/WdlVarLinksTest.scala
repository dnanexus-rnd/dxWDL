package dxWDL

import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import spray.json._
import wdl4s.wdl.types._

class WdlVarLinksTest extends FlatSpec with BeforeAndAfterEach {

    it should "import JSON values" in {
        val x = WdlVarLinks.importFromDxExec(WdlBooleanType, DeclAttrs.empty, JsBoolean(true))
        //System.err.println(x)

        val y = WdlVarLinks.importFromDxExec(WdlArrayType(WdlIntegerType),
                                  DeclAttrs.empty,
                                  JsArray(Vector(JsNumber(1), JsNumber(2.3))))
        //System.err.println(y)

        val z = WdlVarLinks.importFromDxExec(WdlArrayType(WdlStringType),
                                  DeclAttrs.empty,
                                  JsArray(Vector(JsString("hello"),
                                                 JsString("sunshine"),
                                                 JsString("ride"))))
        //System.err.println(z)
    }
}
