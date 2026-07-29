package srv32i

import chisel3._
import chisel3.util._

class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1Addr = Input(UInt(5.W))
    val rs2Addr = Input(UInt(5.W))
    val rs1Data = Output(UInt(32.W))
    val rs2Data = Output(UInt(32.W))

    val wen   = Input(Bool())
    val waddr = Input(UInt(5.W))
    val wdata = Input(UInt(32.W))

    val debugRegs = Output(Vec(32, UInt(32.W)))
  })

  val regs = Mem(32, UInt(32.W))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, regs.read(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, regs.read(io.rs2Addr))

  when(io.wen && io.waddr =/= 0.U) {
    regs.write(io.waddr, io.wdata)
  }

  for (i <- 0 until 32) {
    io.debugRegs(i) := (if (i == 0) 0.U else regs.read(i.U))
  }
}
