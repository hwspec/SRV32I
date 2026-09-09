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
    MuxLookup(op, 0.U(32.W))(Seq(
      ADD  -> (a +% b),          // fixed-width wrapping add, stays 32-bit
      SUB  -> (a -% b),          // fixed-width wrapping sub, stays 32-bit
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
  val isEBREAK = Bool()
  val isFence  = Bool()
  val isCustom = Bool()
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
  val isSystem = opcode === "b1110011".U && io.out.funct3 === 0.U
  val isECALL  = isSystem && io.out.immI === 0.U
  val isEBREAK = isSystem && io.out.immI === 1.U
  // FENCE / FENCE.I both decode to opcode 0b0001111; treated as a NOP since
  // this core is single-issue, in-order, non-caching -- there's nothing for
  // either fence variant to actually order or invalidate.
  val isFence  = opcode === "b0001111".U
  // RISC-V reserves opcode 0b0001011 ("custom-0") for non-standard extensions.
  // Reused here as the demo hook for a PPC rlwimi-style rotate/mask/insert op;
  // it decodes with the same rd/rs1/rs2/funct3/funct7 fields as R-type, so
  // adding another custom op needs no new decode logic -- see CustomOp below.
  val isCustom = opcode === "b0001011".U

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
  io.out.isEBREAK := isEBREAK
  io.out.isFence  := isFence
  io.out.isCustom := isCustom

  val legal = isRType || isIType || isLoad || isStore || isBranch ||
    isLUI || isAUIPC || isJAL || isJALR || isECALL || isEBREAK || isFence || isCustom
  io.out.illegal := !legal
}

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

// Dispatch table for custom-0 opcode instructions, keyed by funct3 (funct7 is
// also available in SRV32I for sub-selecting variants if 8 funct3 slots aren't
// enough). To add a new custom instruction:
//   1. pick an unused funct3 code below
//   2. add a case to `compute` that derives the result from the three operands
//      SRV32I already wires in: rs1Data, rs2Data, and rdData (rd's *current*
//      value, read before this instruction's writeback -- needed for any
//      read-modify-write op like insert/merge instructions)
//   3. if the op needs bits beyond rs1/rs2/rd/funct3/funct7 (e.g. rlwimi's
//      SH/MB/ME fields), extend DecodedInst/Decoder with a dedicated custom
//      immediate rather than overloading immI/immS/etc.
// No other file needs to change: SRV32I wires isCustom into regWen/wbData
// generically, so any op added here writes back automatically, and the same
// rs1/rs2/rd fields already participate in the pipeline's hazard checks.
object CustomOp {
  val RLWIMI = 0.U(3.W) // funct3 = 000: rotate-left rs1 by shamt, insert into rd under mask

  def compute(funct3: UInt, rs1Data: UInt, rs2Data: UInt, rdData: UInt): UInt = {
    MuxLookup(funct3, rdData)(Seq(
      // TODO: real rlwimi semantics -- result = (rotl(rs1Data, shamt) & mask) | (rdData & ~mask).
      // shamt/mask need a dedicated immediate field (PPC packs SH/MB/ME in 15 bits,
      // which doesn't fit funct7's 7 bits); stubbed as passthrough of rd until that
      // encoding is defined, so the datapath/hazard plumbing below is exercised as-is.
      RLWIMI -> rdData
    ))
  }
}

class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1Addr = Input(UInt(5.W))
    val rs2Addr = Input(UInt(5.W))
    val rs1Data = Output(UInt(32.W))
    val rs2Data = Output(UInt(32.W))

    // Third read port exposing rd's *current* value, addressed by rdAddr.
    // Unused by the base ISA (which only ever writes rd), but read-modify-write
    // custom ops (e.g. rlwimi-style insert/merge) need rd as a source too.
    val rdAddr = Input(UInt(5.W))
    val rdData = Output(UInt(32.W))

    // Primary write port: the instruction currently completing in EX.
    val wen   = Input(Bool())
    val waddr = Input(UInt(5.W))
    val wdata = Input(UInt(32.W))

    // Second write port: a load's result, which lands one cycle after the
    // load itself was in EX (dmem's registered-read latency) -- see
    // loadPendReg in SRV32I. A plain 32-entry regfile with combinational
    // reads supports a second write port cheaply; this keeps the pipeline
    // free of a write-port arbiter/stall for the (common) case where the
    // instruction after a load doesn't depend on it.
    val wenLoad   = Input(Bool())
    val waddrLoad = Input(UInt(5.W))
    val wdataLoad = Input(UInt(32.W))

    val debugRegs = Output(Vec(32, UInt(32.W)))
  })

  val regs = Mem(32, UInt(32.W))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, regs.read(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, regs.read(io.rs2Addr))
  io.rdData  := Mux(io.rdAddr === 0.U, 0.U, regs.read(io.rdAddr))

  when(io.wenLoad && io.waddrLoad =/= 0.U) {
    regs.write(io.waddrLoad, io.wdataLoad)
  }
  when(io.wen && io.waddr =/= 0.U) {
    regs.write(io.waddr, io.wdata)
  }

  for (i <- 0 until 32) {
    io.debugRegs(i) := (if (i == 0) 0.U else regs.read(i.U))
  }
}

// ---------------------------------------------------------------------------
// 2-stage (IF / EX) pipeline.
//
// There's no explicit instruction latch between fetch and execute: imem is a
// 1-cycle-latency synchronous-read memory, so its own registered output IS
// the IF/EX pipeline register. pcFetchReg is "the address applied to imem
// this cycle"; pcExReg mirrors pcFetchReg one cycle later, i.e. it labels
// whichever instruction io.imem.inst is currently showing.
//
// Because the regfile write commits synchronously while reads are
// combinational, a normal producer->consumer pair (instruction i writes,
// instruction i+1 reads) needs NO forwarding: by the time i+1 reaches EX and
// reads the regfile, i's write already landed. The two cases that don't fall
// out for free:
//   - taken branch/JAL/JALR: the next instruction was already speculatively
//     fetched under a not-taken/PC+4 assumption and must be squashed
//     (bubbleReg) -- 1-cycle penalty.
//   - load-use: dmem also has 1-cycle read latency, so a load's result isn't
//     available until one cycle *after* it's in EX -- one cycle later than a
//     normal ALU op. loadPendReg carries that result into the following
//     cycle's regfile write; if the very next instruction actually reads (or
//     writes) that same register, `stall` re-issues it for one extra cycle.
// Everything else -- ALU ops, not-taken branches, stores, custom-0 ops,
// FENCE -- is 1 cycle per instruction.
// ---------------------------------------------------------------------------
class SRV32I extends Module {
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
      val ebreak      = Bool()
    })
  })

  // ---------------- Pipeline registers ----------------
  val pcFetchReg = RegInit(0.U(32.W)) // address applied to imem this cycle
  val pcExReg    = RegInit(0.U(32.W)) // PC of the instruction currently in EX
  val bubbleReg  = RegInit(true.B)    // true: EX holds no valid instruction (warmup/squashed)

  val haltedReg  = RegInit(false.B)
  val illegalReg = RegInit(false.B)
  val ecallReg   = RegInit(false.B)
  val ebreakReg  = RegInit(false.B)
  val cycleReg   = RegInit(0.U(64.W))

  // Outstanding load: set when a load completes EX; consumed one cycle later
  // to (a) write its result and (b) let the hazard check see it.
  val loadPendReg     = RegInit(false.B)
  val loadPendRdReg   = Reg(UInt(5.W))
  val loadPendF3Reg   = Reg(UInt(3.W))

  val active = io.enable && !haltedReg
  io.running := active

  // ---------------- Instruction fetch ----------------
  io.imem.addr := pcFetchReg

  val decoder = Module(new Decoder)
  decoder.io.inst := io.imem.inst
  val dec = decoder.io.out

  // ---------------- Hazard check ----------------
  // Only case that needs a stall: the instruction now in EX reads (or would
  // clobber, via the second write port) the register a pending load is about
  // to write. Loads two or more instructions apart never trigger this, since
  // the write always lands exactly one cycle after the load leaves EX.
  val writesRd = dec.isRType || dec.isIType || dec.isLUI || dec.isAUIPC ||
    dec.isJAL || dec.isJALR || dec.isCustom
  val stall = !bubbleReg && loadPendReg && loadPendRdReg =/= 0.U &&
    (dec.rs1 === loadPendRdReg || dec.rs2 === loadPendRdReg ||
      ((writesRd || dec.isLoad) && dec.rd === loadPendRdReg))

  val instrValid = !bubbleReg && !stall

  // ---------------- Register file ----------------
  val regFile = Module(new RegFile)
  regFile.io.rs1Addr := dec.rs1
  regFile.io.rs2Addr := dec.rs2
  regFile.io.rdAddr  := dec.rd
  val rs1Data = regFile.io.rs1Data
  val rs2Data = regFile.io.rs2Data
  val rdDataCur = regFile.io.rdData // rd's pre-writeback value, for custom read-modify-write ops
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

  // ---------------- Custom-0 ops ----------------
  val customOut = CustomOp.compute(dec.funct3, rs1Data, rs2Data, rdDataCur)

  // ---------------- Branch ----------------
  val branchUnit = Module(new BranchUnit)
  branchUnit.io.funct3   := dec.funct3
  branchUnit.io.rs1Data  := rs1Data
  branchUnit.io.rs2Data  := rs2Data
  branchUnit.io.isBranch := dec.isBranch
  val branchTaken = branchUnit.io.taken

  // ---------------- Data memory ----------------
  val memAddr = rs1Data +% Mux(dec.isStore, dec.immS, dec.immI)
  io.dmem.addr  := memAddr
  io.dmem.wdata := rs2Data
  io.dmem.wen   := instrValid && dec.isStore
  io.dmem.wmask := MemAccess.storeMask(dec.funct3)

  // ---------------- Next PC / writeback values ----------------
  val pcPlus4      = pcExReg +% 4.U(32.W)
  val branchTarget = pcExReg +% dec.immB
  val jalTarget    = pcExReg +% dec.immJ
  val jalrTarget   = (rs1Data +% dec.immI) & ~1.U(32.W)

  // A flush only fires for a real, completing (non-bubble, non-stalled)
  // taken branch/jump -- the instruction already fetched under the
  // not-taken/PC+4 prediction is what gets squashed via bubbleReg below.
  val flushRedirect = instrValid && ((dec.isBranch && branchTaken) || dec.isJAL || dec.isJALR)
  val redirectTarget = MuxCase(pcPlus4, Seq(
    (dec.isBranch && branchTaken) -> branchTarget,
    dec.isJAL  -> jalTarget,
    dec.isJALR -> jalrTarget
  ))

  val wbData = MuxCase(aluOut, Seq(
    dec.isLUI   -> dec.immU,
    dec.isAUIPC -> (pcExReg +% dec.immU),
    (dec.isJAL || dec.isJALR) -> pcPlus4,
    dec.isCustom -> customOut
  ))

  val regWen = instrValid && writesRd
  regFile.io.wen   := regWen
  regFile.io.waddr := dec.rd
  regFile.io.wdata := wbData

  regFile.io.wenLoad   := loadPendReg
  regFile.io.waddrLoad := loadPendRdReg
  regFile.io.wdataLoad := MemAccess.loadData(loadPendF3Reg, io.dmem.rdata)

  // ---------------- Pipeline advance ----------------
  when(io.enable) {
    when(!haltedReg) {
      cycleReg := cycleReg + 1.U

      // pcExReg always mirrors pcFetchReg one cycle later -- what changes is
      // what we point pcFetchReg at:
      //   stall -> re-issue the current (stalled) instruction's own address,
      //            so it's the one that shows up again next cycle
      //   flush -> jump to the resolved branch/jump target
      //   else  -> keep fetching straight-line (PC+4)
      pcFetchReg := Mux(stall, pcExReg,
        Mux(flushRedirect, redirectTarget, pcFetchReg +% 4.U))
      pcExReg   := pcFetchReg
      bubbleReg := flushRedirect // next cycle's instruction is a bubble iff this cycle flushed

      loadPendReg   := instrValid && dec.isLoad
      loadPendRdReg := dec.rd
      loadPendF3Reg := dec.funct3

      when(instrValid) {
        when(dec.illegal)      { illegalReg := true.B; haltedReg := true.B }
          .elsewhen(dec.isECALL)  { ecallReg  := true.B; haltedReg := true.B }
          .elsewhen(dec.isEBREAK) { ebreakReg := true.B; haltedReg := true.B }
      }
    }
  }.otherwise {
    when(io.entryAddr_we) { pcFetchReg := io.entryAddr }
    bubbleReg     := true.B
    loadPendReg   := false.B
  }

  // ---------------- Soft reset (overrides above) ----------------
  when(io.softReset) {
    pcFetchReg  := 0.U
    pcExReg     := 0.U
    bubbleReg   := true.B
    loadPendReg := false.B
    haltedReg   := false.B
    illegalReg  := false.B
    ecallReg    := false.B
    ebreakReg   := false.B
    cycleReg    := 0.U
  }

  // ---------------- Debug ----------------
  io.debugPC     := pcExReg
  io.debugCycles := cycleReg
  io.debugStatus.running     := io.running
  io.debugStatus.halted      := haltedReg
  io.debugStatus.illegalInst := illegalReg
  io.debugStatus.ecall       := ecallReg
  io.debugStatus.ebreak      := ebreakReg
}
