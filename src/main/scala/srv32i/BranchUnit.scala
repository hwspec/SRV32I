package srv32i

import chisel3._
import chisel3.util._

class BranchUnit extends Module {
  val io = IO(new Bundle {
    val funct3   = Input(UInt(3.W))
    val rs1Data  = Input(UInt(32.W))
    val rs2Data  = Input(UInt(32.W))
    val isBranch = Input(Bool())
    val taken    = Output(Bool())
  })

  val taken = WireDefault(false.B)
  when(io.isBranch) {
    switch(io.funct3) {
      is("b000".U) { taken := io.rs1Data === io.rs2Data }               // BEQ
      is("b001".U) { taken := io.rs1Data =/= io.rs2Data }               // BNE
      is("b100".U) { taken := io.rs1Data.asSInt < io.rs2Data.asSInt }   // BLT
      is("b101".U) { taken := io.rs1Data.asSInt >= io.rs2Data.asSInt }  // BGE
      is("b110".U) { taken := io.rs1Data < io.rs2Data }                 // BLTU
      is("b111".U) { taken := io.rs1Data >= io.rs2Data }                // BGEU
    }
  }
  io.taken := taken
}
