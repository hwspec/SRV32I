package srv32i
import chisel3._
import chisel3.util._

class DecodedInst extends Bundle {
  val rd     = UInt(5.W)
  val rs1    = UInt(5.W)
  val rs2    = UInt(5.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)

  val immI = UInt(32.W)
  val immS = UInt(32.W)
  val immB = UInt(32.W)
  val immU = UInt(32.W)
  val immJ = UInt(32.W)

  val isRType  = Bool()
  val isIType  = Bool()
  val isLoad   = Bool()
  val isStore  = Bool()
  val isBranch = Bool()
  val isLUI    = Bool()
  val isAUIPC  = Bool()
  val isJAL    = Bool()
  val isJALR   = Bool()
  val isECALL  = Bool()
  val illegal  = Bool()
}

// Pure combinational decode of a 32-bit instruction word.
class Decoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val out  = Output(new DecodedInst)
  })

  val inst   = io.inst
  val opcode = inst(6, 0)

  io.out.rd     := inst(11, 7)
  io.out.rs1    := inst(19, 15)
  io.out.rs2    := inst(24, 20)
  io.out.funct3 := inst(14, 12)
  io.out.funct7 := inst(31, 25)

  io.out.immI := Cat(Fill(20, inst(31)), inst(31, 20))
  io.out.immS := Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  io.out.immB := Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  io.out.immU := Cat(inst(31, 12), 0.U(12.W))
  io.out.immJ := Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  val isRType  = opcode === "b0110011".U
  val isIType  = opcode === "b0010011".U
  val isLoad   = opcode === "b0000011".U
  val isStore  = opcode === "b0100011".U
  val isBranch = opcode === "b1100011".U
  val isLUI    = opcode === "b0110111".U
  val isAUIPC  = opcode === "b0010111".U
  val isJAL    = opcode === "b1101111".U
  val isJALR   = opcode === "b1100111".U
  val isECALL  = opcode === "b1110011".U && io.out.funct3 === 0.U && io.out.immI === 0.U

  io.out.isRType  := isRType
  io.out.isIType  := isIType
  io.out.isLoad   := isLoad
  io.out.isStore  := isStore
  io.out.isBranch := isBranch
  io.out.isLUI    := isLUI
  io.out.isAUIPC  := isAUIPC
  io.out.isJAL    := isJAL
  io.out.isJALR   := isJALR
  io.out.isECALL  := isECALL

  val legal = isRType || isIType || isLoad || isStore || isBranch ||
    isLUI || isAUIPC || isJAL || isJALR || isECALL
  io.out.illegal := !legal
}
