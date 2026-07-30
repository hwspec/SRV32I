package srv32i

import axi._
import axi.AxiModuleParamsHelper._
import upickle.default._

case class SRV32IModuleParams( // Note: do not put default value here
                                    // DefParams
                                    soft_reset_rw: Long,
                                  ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "SRV32I"
}

object SRV32IModuleParams {
  implicit val rw: ReadWriter[SRV32IModuleParams] = macroRW

  def default() : SRV32IModuleParams =
    new SRV32IModuleParams(soft_reset_rw = 0x0)
}


object SRV32ITop extends App {
  import axi.EmitVerilog

  val p = checkParamEnv(
    SRV32IModuleParams.default(),
    "SRV32I_MODULE_PARAMS")
  EmitVerilog.generate(new SRV32I, p)
}
