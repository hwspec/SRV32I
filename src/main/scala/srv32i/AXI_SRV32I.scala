package srv32i

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._
import chisel3._
import chisel3.util._

// -----------------------------------------------------------------------
// Register map (20-bit MMIO window, 0x10 spacing per register)
//
//   0x00  soft_reset   (rw) : write 1 -> soft-reset dut for reset_cycles;
//                              read   -> 1 once reset sequence is done
//   0x10  ctrl         (rw) : bit0 = enable
//   0x20  entry_addr   (rw) : PC load value (set while enable=0)
//   0x30  entry_we     (w)  : any write -> pulses entryAddr_we for 1 cycle
//   0x40  status       (r)  : bit0 running, bit1 halted,
//                              bit2 illegalInst, bit3 ecall
//   0x50  debug_pc     (r)
//   0x60  debug_cyc_lo (r)
//   0x70  debug_cyc_hi (r)
//   0x100 debug_regs[0..31] (r) : x0..x31, one register every 0x10
//         -> occupies 0x100 .. 0x2F0
//   imem_base_rw .. +4*imem_depth-1 (rw) : instruction memory, word access
//   dmem_base_rw .. +4*dmem_depth-1 (rw) : data memory, word access
//
//   imem/dmem host access is honored only while ctrl.enable == 0:
//   load program/data before starting the core, read dmem back after halt.
// -----------------------------------------------------------------------

case class SRV32IModuleParams( // Note: do not put default value here
                               // DefParams
                               soft_reset_rw     : Long,
                               // control / status
                               ctrl_rw           : Long,
                               entry_addr_rw     : Long,
                               entry_we_rw       : Long,
                               status_r          : Long,
                               debug_pc_r        : Long,
                               debug_cyc_lo_r    : Long,
                               debug_cyc_hi_r    : Long,
                               debug_regs_base_r : Long,
                               // memory windows
                               imem_base_rw      : Long,
                               dmem_base_rw      : Long,
                               imem_depth        : Int, // words
                               dmem_depth        : Int, // words
                               reset_cycles      : Int, // soft reset cycles
                             ) extends AxiModuleParams with AxiModuleDefParams {
  val moduleName = "SRV32I"
}

object SRV32IModuleParams {
  implicit val rw: ReadWriter[SRV32IModuleParams] = macroRW

  def default(imem_depth: Int = 1024, dmem_depth: Int = 1024): SRV32IModuleParams =
    new SRV32IModuleParams(
      soft_reset_rw     = 0x00,
      ctrl_rw            = 0x10,
      entry_addr_rw      = 0x20,
      entry_we_rw        = 0x30,
      status_r            = 0x40,
      debug_pc_r          = 0x50,
      debug_cyc_lo_r      = 0x60,
      debug_cyc_hi_r      = 0x70,
      debug_regs_base_r  = 0x100,
      imem_base_rw        = 0x1000,
      dmem_base_rw        = 0x2000,
      imem_depth          = imem_depth,
      dmem_depth          = dmem_depth,
      reset_cycles        = 8,
    )
}

// Assumes SRV32I (the DUT) is defined elsewhere and imported/in scope.
class AXI_SRV32I(p: SRV32IModuleParams, debugprint: Boolean = false)
  extends chisel3.Module with axi.HasAxiLite32IO {

  val bw: Int = 32
  override val S = IO(new AxiLite32IO())

  // cycle counter for convenience
  val (cycles, wrap) = Counter(true.B, 1 << 16)

  // -----------------------------
  // soft reset handling (standard chisel-axi-utils pattern)
  // -----------------------------
  val softResetReg     = RegInit(false.B)
  val softResetDoneReg = RegInit(false.B)
  val resetCounterReg  = RegInit(0.U(log2Ceil(p.reset_cycles + 1).W))

  when(resetCounterReg > 0.U) {
    resetCounterReg := resetCounterReg - 1.U
  }.otherwise {
    softResetReg     := false.B
    softResetDoneReg := true.B
  }
  val combinedReset = softResetReg || reset.asBool

  // -----------------------------
  // control regs
  // -----------------------------
  val enableReg    = RegInit(false.B)
  val entryAddrReg = RegInit(0.U(bw.W))
  val entryWeReg   = RegInit(false.B) // self-clearing pulse

  entryWeReg := false.B // default deassert; set for exactly 1 cycle on write

  val dut = withReset(combinedReset) { Module(new SRV32I) }
  dut.io.enable       := enableReg
  dut.io.softReset    := softResetReg
  dut.io.entryAddr    := entryAddrReg
  dut.io.entryAddr_we := entryWeReg

  // -----------------------------
  // instruction memory (SyncReadMem, fixed 1-cycle latency, no handshake)
  // -----------------------------
  val imemWords = p.imem_depth
  val imemAddrW = log2Ceil(imemWords)
  val imemMem   = SyncReadMem(imemWords, UInt(32.W))

  val imemIdx = (dut.io.imem.addr >> 2)(imemAddrW - 1, 0)
  dut.io.imem.inst := imemMem.read(imemIdx, true.B)

  // -----------------------------
  // data memory (SyncReadMem, byte-masked write, fixed 1-cycle latency)
  // -----------------------------
  val dmemWords = p.dmem_depth
  val dmemAddrW = log2Ceil(dmemWords)
  val dmemMem   = SyncReadMem(dmemWords, Vec(4, UInt(8.W)))

  val dmemIdx    = (dut.io.dmem.addr >> 2)(dmemAddrW - 1, 0)
  val dmemWdataV = VecInit((0 until 4).map(i => dut.io.dmem.wdata(8 * i + 7, 8 * i)))

  when(dut.io.dmem.wen) {
    dmemMem.write(dmemIdx, dmemWdataV, dut.io.dmem.wmask.asBools)
  }
  dut.io.dmem.rdata := dmemMem.read(dmemIdx, !dut.io.dmem.wen).asUInt

  // -----------------------------
  // AXI-lite regs (write path)
  // -----------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg  = Reg(UInt(bw.W))
  val wHoldValidReg  = RegInit(false.B)
  val wHoldDataReg   = Reg(UInt(32.W))
  val wHoldStrbReg   = Reg(UInt(4.W))
  val bvalidReg      = RegInit(false.B)
  val brespReg       = RegInit(0.U(2.W))

  S.AXI.awready := !awHoldValidReg && !bvalidReg
  S.AXI.wready  := !wHoldValidReg && !bvalidReg

  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire  = S.AXI.wvalid && S.AXI.wready

  when(awFire) {
    awHoldValidReg := true.B
    awHoldAddrReg  := S.AXI.awaddr(19, 0) // 1MB MMIO range
  }
  when(wFire) {
    wHoldValidReg := true.B
    wHoldDataReg  := S.AXI.wdata
    wHoldStrbReg  := S.AXI.wstrb
  }

  val doWrite = awHoldValidReg && wHoldValidReg && !bvalidReg
  val imemHi  = (p.imem_base_rw + 4L * imemWords).U(bw.W)
  val dmemHi  = (p.dmem_base_rw + 4L * dmemWords).U(bw.W)

  when(doWrite) {
    val a         = awHoldAddrReg
    val fullWrite = wHoldStrbReg === "b1111".U
    val hostMemOk = !enableReg // imem/dmem writable only while core is disabled
    val bresp     = WireDefault(OKAY.U)

    when(!fullWrite) {
      bresp := SLVERR.U
    }.elsewhen(a === p.soft_reset_rw.U) {
      softResetReg     := true.B
      resetCounterReg  := p.reset_cycles.U
      softResetDoneReg := false.B
    }.elsewhen(a === p.ctrl_rw.U) {
      enableReg := wHoldDataReg(0)
    }.elsewhen(a === p.entry_addr_rw.U) {
      entryAddrReg := wHoldDataReg
    }.elsewhen(a === p.entry_we_rw.U) {
      entryWeReg := true.B
    }.elsewhen(hostMemOk && a >= p.imem_base_rw.U && a < imemHi) {
      imemMem.write((a - p.imem_base_rw.U) >> 2, wHoldDataReg)
    }.elsewhen(hostMemOk && a >= p.dmem_base_rw.U && a < dmemHi) {
      val wv = VecInit((0 until 4).map(i => wHoldDataReg(8 * i + 7, 8 * i)))
      dmemMem.write((a - p.dmem_base_rw.U) >> 2, wv)
    }.otherwise {
      bresp := SLVERR.U
    }

    brespReg       := bresp
    bvalidReg      := true.B
    awHoldValidReg := false.B
    wHoldValidReg  := false.B
  }
  when(bvalidReg && S.AXI.bready) {
    bvalidReg := false.B
  }

  S.AXI.bvalid := bvalidReg
  S.AXI.bresp  := brespReg

  // -----------------------------
  // Read path: AR -> R
  //
  // Plain register/status reads resolve in 1 AXI cycle, same as the
  // template. imem/dmem reads go through SyncReadMem.read(), which has
  // its own 1-cycle output latency — an extra WAIT_MEM state accounts
  // for that so rdata isn't sampled a cycle early.
  // -----------------------------
  val rdataReg = Reg(UInt(32.W))
  val rrespReg = RegInit(0.U(2.W))

  object RState extends ChiselEnum {
    val READY2READ, WAIT_MEM, COMPLETED = Value
  }
  val rstateReg = RegInit(RState.READY2READ)

  S.AXI.arready := rstateReg === RState.READY2READ
  S.AXI.rvalid  := rstateReg === RState.COMPLETED
  S.AXI.rdata   := rdataReg
  S.AXI.rresp   := rrespReg

  val arFire = S.AXI.arvalid && S.AXI.arready

  val memAddrW        = math.max(imemAddrW, dmemAddrW)
  val hostRdEnReg      = RegInit(false.B)
  val hostRdIsImemReg  = RegInit(false.B)
  val hostRdIdxReg     = Reg(UInt(memAddrW.W))

  val hostImemRdata = imemMem.read(hostRdIdxReg(imemAddrW - 1, 0), hostRdEnReg && hostRdIsImemReg)
  val hostDmemRdata = dmemMem.read(hostRdIdxReg(dmemAddrW - 1, 0), hostRdEnReg && !hostRdIsImemReg).asUInt

  hostRdEnReg := false.B // one-shot; re-armed below when a mem read is issued

  when(arFire) {
    if (debugprint) printf("%d: arFire: %x\n", cycles, S.AXI.araddr)
    val araddr = S.AXI.araddr(19, 0)
    rrespReg := OKAY.U
    val rstate = WireDefault(RState.READY2READ)

    when(araddr === p.soft_reset_rw.U) {
      rdataReg := softResetDoneReg
      rstate   := RState.COMPLETED
    }.elsewhen(araddr === p.ctrl_rw.U) {
      rdataReg := enableReg
      rstate   := RState.COMPLETED
    }.elsewhen(araddr === p.entry_addr_rw.U) {
      rdataReg := entryAddrReg
      rstate   := RState.COMPLETED
    }.elsewhen(araddr === p.status_r.U) {
      rdataReg := Cat(0.U(28.W),
        dut.io.debugStatus.ecall,
        dut.io.debugStatus.illegalInst,
        dut.io.debugStatus.halted,
        dut.io.debugStatus.running)
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.debug_pc_r.U) {
      rdataReg := dut.io.debugPC
      rstate   := RState.COMPLETED
    }.elsewhen(araddr === p.debug_cyc_lo_r.U) {
      rdataReg := dut.io.debugCycles(31, 0)
      rstate   := RState.COMPLETED
    }.elsewhen(araddr === p.debug_cyc_hi_r.U) {
      rdataReg := dut.io.debugCycles(63, 32)
      rstate   := RState.COMPLETED
    }.elsewhen(araddr >= p.debug_regs_base_r.U &&
      araddr < (p.debug_regs_base_r + 0x10L * 32).U &&
      (araddr - p.debug_regs_base_r.U)(3, 0) === 0.U) {
      val idx = (araddr - p.debug_regs_base_r.U) >> 4
      rdataReg := dut.io.debugRegs(idx)
      rstate   := RState.COMPLETED
    }.elsewhen(!enableReg && araddr >= p.imem_base_rw.U && araddr < imemHi) {
      hostRdEnReg     := true.B
      hostRdIsImemReg := true.B
      hostRdIdxReg    := (araddr - p.imem_base_rw.U) >> 2
      rstate          := RState.WAIT_MEM
    }.elsewhen(!enableReg && araddr >= p.dmem_base_rw.U && araddr < dmemHi) {
      hostRdEnReg     := true.B
      hostRdIsImemReg := false.B
      hostRdIdxReg    := (araddr - p.dmem_base_rw.U) >> 2
      rstate          := RState.WAIT_MEM
    }.otherwise {
      if (debugprint) printf("%d: bad read req %x\n", cycles, araddr)
      rdataReg := 0xbad00000L.U | S.AXI.araddr(31, 0)
      rstate   := RState.COMPLETED
    }
    rstateReg := rstate
  }

  when(rstateReg === RState.WAIT_MEM) {
    rdataReg  := Mux(hostRdIsImemReg, hostImemRdata, hostDmemRdata)
    rrespReg  := OKAY.U
    rstateReg := RState.COMPLETED
  }

  when(rstateReg === RState.COMPLETED && S.AXI.rready) {
    rstateReg := RState.READY2READ
  }
}

object AXI_SRV32I extends App {
  val p = checkParamEnv(
    SRV32IModuleParams.default(),
    "SRV32I_MODULE_PARAMS")
  EmitVerilog.generate(new AXI_SRV32I(p, debugprint = true), p)
}
