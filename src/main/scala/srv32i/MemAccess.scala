package srv32i

import chisel3._
import chisel3.util._

object MemAccess {
  def storeMask(funct3: UInt): UInt = MuxLookup(funct3, "b1111".U)(Seq(
    "b000".U -> "b0001".U, // SB
    "b001".U -> "b0011".U, // SH
    "b010".U -> "b1111".U  // SW
  ))

  def loadData(funct3: UInt, rdata: UInt): UInt = MuxLookup(funct3, rdata)(Seq(
    "b000".U -> Cat(Fill(24, rdata(7)), rdata(7, 0)),    // LB
    "b001".U -> Cat(Fill(16, rdata(15)), rdata(15, 0)),  // LH
    "b010".U -> rdata,                                    // LW
    "b100".U -> Cat(0.U(24.W), rdata(7, 0)),              // LBU
    "b101".U -> Cat(0.U(16.W), rdata(15, 0))              // LHU
  ))
}
