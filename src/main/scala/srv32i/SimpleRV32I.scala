package srv32i

import axi._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._

class SimpleRV32I extends Module {
  val io = IO(new Bundle {
    val enable       = Input(Bool())
    val softReset    = Input(Bool())
    val entryAddr    = Input(UInt(32.W))
    val entryAddr_we = Input(Bool()) // when enable is low, loads PC := entryAddr
    val running      = Output(Bool())

    val imem = new Bundle {
      val addr = Output(UInt(32.W))
      val inst = Input(UInt(32.W))
    }
    val dmem = new Bundle {
      val addr  = Output(UInt(32.W))
      val wdata = Output(UInt(32.W))
      val wen   = Output(Bool())
      val wmask = Output(UInt(4.W))
      val rdata = Input(UInt(32.W))
    }

    val debugRegs   = Output(Vec(32, UInt(32.W)))
    val debugPC     = Output(UInt(32.W))
    val debugCycles = Output(UInt(64.W))
    val debugStatus = Output(new Bundle {
      val running     = Bool()
      val halted      = Bool()
      val illegalInst = Bool()
      val ecall       = Bool()
    })
  })

  // ---------------- FSM state ----------------
  // pc only changes when returning to sFetch, so the address presented to
  // imem/dmem is stable across sExec/sMem -- matches fixed-1-cycle SyncReadMem
  // latency with no handshake: address issued one state, data valid the next.
  val sFetch :: sExec :: sMem :: Nil = Enum(3)
  val state = RegInit(sFetch)

  val pcReg      = RegInit(0.U(32.W))
  val haltedReg  = RegInit(false.B)
  val illegalReg = RegInit(false.B)
  val ecallReg   = RegInit(false.B)
  val cycleReg   = RegInit(0.U(64.W))

  val active = io.enable && !haltedReg
  io.running := active

  // ---------------- Instruction fetch ----------------
  io.imem.addr := pcReg

  val decoder = Module(new Decoder)
  decoder.io.inst := io.imem.inst
  val dec = decoder.io.out

  // ---------------- Register file ----------------
  val regFile = Module(new RegFile)
  regFile.io.rs1Addr := dec.rs1
  regFile.io.rs2Addr := dec.rs2
  val rs1Data = regFile.io.rs1Data
  val rs2Data = regFile.io.rs2Data
  io.debugRegs := regFile.io.debugRegs

  // ---------------- ALU ----------------
  val aluOp2 = Mux(dec.isStore, dec.immS, Mux(dec.isIType || dec.isLoad, dec.immI, rs2Data))

  val aluCtrl = Wire(UInt(4.W))
  aluCtrl := ALU.ADD
  when(dec.isRType || dec.isIType) {
    switch(dec.funct3) {
      is("b000".U) { aluCtrl := Mux(dec.isRType && dec.funct7(5), ALU.SUB, ALU.ADD) }
      is("b001".U) { aluCtrl := ALU.SLL }
      is("b010".U) { aluCtrl := ALU.SLT }
      is("b011".U) { aluCtrl := ALU.SLTU }
      is("b100".U) { aluCtrl := ALU.XOR }
      is("b101".U) { aluCtrl := Mux(dec.funct7(5), ALU.SRA, ALU.SRL) }
      is("b110".U) { aluCtrl := ALU.OR }
      is("b111".U) { aluCtrl := ALU.AND }
    }
  }
  val aluOut = ALU.compute(aluCtrl, rs1Data, aluOp2)

  // ---------------- Branch ----------------
  val branchUnit = Module(new BranchUnit)
  branchUnit.io.funct3   := dec.funct3
  branchUnit.io.rs1Data  := rs1Data
  branchUnit.io.rs2Data  := rs2Data
  branchUnit.io.isBranch := dec.isBranch
  val branchTaken = branchUnit.io.taken

  // ---------------- Data memory ----------------
  val memAddr = rs1Data + Mux(dec.isStore, dec.immS, dec.immI)
  io.dmem.addr  := memAddr
  io.dmem.wdata := rs2Data
  io.dmem.wen   := false.B // set true only in sExec for stores
  io.dmem.wmask := MemAccess.storeMask(dec.funct3)

  // ---------------- Next PC / writeback values ----------------
  val pcPlus4      = pcReg + 4.U
  val branchTarget = pcReg + dec.immB
  val jalTarget    = pcReg + dec.immJ
  val jalrTarget   = (rs1Data + dec.immI) & ~1.U(32.W)

  val nextPCExec = MuxCase(pcPlus4, Seq(
    (dec.isBranch && branchTaken) -> branchTarget,
    dec.isJAL  -> jalTarget,
    dec.isJALR -> jalrTarget
  ))

  val wbData = MuxCase(aluOut, Seq(
    dec.isLUI   -> dec.immU,
    dec.isAUIPC -> (pcReg + dec.immU),
    (dec.isJAL || dec.isJALR) -> pcPlus4
  ))

  val regWen   = WireDefault(false.B)
  val regWdata = WireDefault(wbData)
  regFile.io.wen   := regWen
  regFile.io.waddr := dec.rd
  regFile.io.wdata := regWdata

  // ---------------- Control ----------------
  when(io.enable) {
    when(!haltedReg) {
      cycleReg := cycleReg + 1.U

      switch(state) {
        is(sFetch) {
          state := sExec
        }
        is(sExec) {
          when(dec.illegal) {
            illegalReg := true.B
            haltedReg  := true.B
          }.elsewhen(dec.isECALL) {
            ecallReg  := true.B
            haltedReg := true.B
          }.elsewhen(dec.isStore) {
            io.dmem.wen := true.B
            pcReg := nextPCExec
            state := sFetch
          }.elsewhen(dec.isLoad) {
            state := sMem
          }.otherwise {
            regWen := dec.isRType || dec.isIType || dec.isLUI || dec.isAUIPC || dec.isJAL || dec.isJALR
            pcReg  := nextPCExec
            state  := sFetch
          }
        }
        is(sMem) {
          regWen   := true.B
          regWdata := MemAccess.loadData(dec.funct3, io.dmem.rdata)
          pcReg    := nextPCExec // == pcPlus4 for loads
          state    := sFetch
        }
      }
    }
  }.otherwise {
    when(io.entryAddr_we) { pcReg := io.entryAddr }
    state := sFetch
  }

  // ---------------- Soft reset (overrides above) ----------------
  when(io.softReset) {
    pcReg      := 0.U
    haltedReg  := false.B
    illegalReg := false.B
    ecallReg   := false.B
    cycleReg   := 0.U
    state      := sFetch
  }

  // ---------------- Debug ----------------
  io.debugPC     := pcReg
  io.debugCycles := cycleReg
  io.debugStatus.running     := io.running
  io.debugStatus.halted      := haltedReg
  io.debugStatus.illegalInst := illegalReg
  io.debugStatus.ecall       := ecallReg
}


case class SimpleRV32IModuleParams( // Note: do not put default value here
                               // DefParams
                               soft_reset_rw: Long,
                             ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "SimpleRV32I"
}

object SimpleRV32IModuleParams {
  implicit val rw: ReadWriter[SimpleRV32IModuleParams] = macroRW

  def default() : SimpleRV32IModuleParams =
    new SimpleRV32IModuleParams(soft_reset_rw = 0x0)
}


object SimpleRV32I extends App {
    import axi.EmitVerilog

  val p = checkParamEnv(
    SimpleRV32IModuleParams.default(),
    "SIMPLERV32I_MODULE_PARAMS")
  EmitVerilog.generate(new SimpleRV32I, p)
}
