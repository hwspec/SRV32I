package srv32i

import chisel3._
import chisel3.util._

object ALU {
  val ADD  = 0.U(4.W)
  val SUB  = 1.U(4.W)
  val SLL  = 2.U(4.W)
  val SLT  = 3.U(4.W)
  val SLTU = 4.U(4.W)
  val XOR  = 5.U(4.W)
  val SRL  = 6.U(4.W)
  val SRA  = 7.U(4.W)
  val OR   = 8.U(4.W)
  val AND  = 9.U(4.W)

  def compute(op: UInt, a: UInt, b: UInt): UInt = {
    val shamt = b(4, 0)
    MuxLookup(op, 0.U)(Seq(
      ADD  -> (a + b),
      SUB  -> (a - b),
      SLL  -> (a << shamt)(31, 0),
      SLT  -> (a.asSInt < b.asSInt).asUInt,
      SLTU -> (a < b),
      XOR  -> (a ^ b),
      SRL  -> (a >> shamt),
      SRA  -> (a.asSInt >> shamt).asUInt,
      OR   -> (a | b),
      AND  -> (a & b)
    ))
  }
}
