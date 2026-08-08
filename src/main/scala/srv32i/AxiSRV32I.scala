// =============================================================================
// AXI4-Lite bridge for SRV32I
// Package: srv32i
//
// Register Map (all offsets within 1MB MMIO window, spaced 0x10 apart):
//
//  0x0000  soft_reset_rw       W: trigger soft reset (loads reset_cycles counter)
//                              R: softResetDoneReg (1 = reset complete)
//  0x0010  enable_rw           R/W: io.enable
//  0x0020  entry_addr_rw       R/W: io.entryAddr
//  0x0030  entry_addr_we_rw    W: pulse io.entryAddr_we for one cycle
//                              R: always 0
//  0x0040  running_r           R: io.running
//  0x0050  debug_pc_r          R: io.debugPC
//  0x0060  debug_cycles_lo_r   R: io.debugCycles[31:0]
//  0x0070  debug_cycles_hi_r   R: io.debugCycles[63:32]
//  0x0080  debug_status_r      R: {28'd0, ecall, illegalInst, halted, running}
//
//  Debug register file (32 registers, read-only):
//  0x0100 + i*0x10  debug_regs_base_r[i]  R: debugRegs[i], i=0..31
//
//  Instruction memory window (host-accessible when enable=0):
//  0x1000 + i*0x10  imem_base_rw[i]       R/W: imem[i], i=0..imem_depth-1
//
//  Data memory window (host-accessible when enable=0):
//  0x5000 + i*0x10  dmem_base_rw[i]       R/W: dmem[i], i=0..dmem_depth-1
//
// =============================================================================

package srv32i

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._

case class SRV32IModuleParams(
  // addresses (suffixed _r, _w, or _rw)
  soft_reset_rw      : Long,
  enable_rw          : Long,
  entry_addr_rw      : Long,
  entry_addr_we_rw   : Long,
  running_r          : Long,
  debug_pc_r         : Long,
  debug_cycles_lo_r  : Long,
  debug_cycles_hi_r  : Long,
  debug_status_r     : Long,
  debug_regs_base_r  : Long,   // 32 regs × 0x10 = 0x200 bytes
  imem_base_rw       : Long,   // imem_depth regs × 0x10
  dmem_base_rw       : Long,   // dmem_depth regs × 0x10
  // non-address params
  imem_depth         : Int,
  dmem_depth         : Int,
  reset_cycles       : Int,
) extends AxiModuleParams with AxiModuleDefParams {
  val moduleName = "SRV32I"
}

object SRV32IModuleParams {
  implicit val rw: ReadWriter[SRV32IModuleParams] = macroRW

  def default(
    imem_depth: Int = 1024,
    dmem_depth: Int = 1024,
  ): SRV32IModuleParams = new SRV32IModuleParams(
    soft_reset_rw     = 0x0000L,
    enable_rw         = 0x0010L,
    entry_addr_rw     = 0x0020L,
    entry_addr_we_rw  = 0x0030L,
    running_r         = 0x0040L,
    debug_pc_r        = 0x0050L,
    debug_cycles_lo_r = 0x0060L,
    debug_cycles_hi_r = 0x0070L,
    debug_status_r    = 0x0080L,
    debug_regs_base_r = 0x0100L,  // 0x0100 .. 0x01F0  (32 regs)
    imem_base_rw      = 0x1000L,  // 0x1000 .. 0x1000 + imem_depth*0x10 - 0x10
    dmem_base_rw      = 0x5000L,  // 0x5000 .. 0x5000 + dmem_depth*0x10 - 0x10
    imem_depth        = imem_depth,
    dmem_depth        = dmem_depth,
    reset_cycles      = 16,
  )
}

class AxiSRV32I(p: SRV32IModuleParams, debugprint: Boolean = false)
    extends chisel3.Module with axi.HasAxiLite32IO {

  override val S = IO(new AxiLite32IO())

  // -------------------------------------------------------------------------
  // Cycle counter (convenience)
  // -------------------------------------------------------------------------
  val (cycles, _) = Counter(true.B, 1 << 30)

  // -------------------------------------------------------------------------
  // Soft-reset logic
  // -------------------------------------------------------------------------
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

  // -------------------------------------------------------------------------
  // Control registers
  // -------------------------------------------------------------------------
  val enableReg    = RegInit(false.B)
  val entryAddrReg = RegInit(0.U(32.W))

  // -------------------------------------------------------------------------
  // Instruction memory (host-side SyncReadMem, word-addressed)
  // -------------------------------------------------------------------------
  val imem = SyncReadMem(p.imem_depth, Vec(4, UInt(8.W)))

  // -------------------------------------------------------------------------
  // Data memory (host-side SyncReadMem, word-addressed)
  // -------------------------------------------------------------------------
  val dmem = SyncReadMem(p.dmem_depth, Vec(4, UInt(8.W)))

  // -------------------------------------------------------------------------
  // DUT instantiation
  // -------------------------------------------------------------------------
  val dut = withReset(combinedReset) { Module(new SRV32I) }

  dut.io.enable       := enableReg
  dut.io.softReset    := softResetReg
  dut.io.entryAddr    := entryAddrReg
  dut.io.entryAddr_we := false.B   // pulsed from write path

  // ---- Instruction memory connections ----
  // DUT reads imem (word-addressed, combinational address, data next cycle)
  // We expose the same SyncReadMem to the host for loading programs.
  val imemWordAddr = dut.io.imem.addr >> 2
  val imemDutRen   = !enableReg  // only read by DUT when running; we always allow
  // Actually we always let the DUT read; the SyncReadMem read port is shared.
  // DUT read port
  val imemDutRdata = imem.read(imemWordAddr)
  dut.io.imem.inst := imemDutRdata.asUInt

  // ---- Data memory connections ----
  val dmemWordAddr = dut.io.dmem.addr >> 2
  // DUT write port
  when(dut.io.dmem.wen) {
    val wdataVec = VecInit((0 until 4).map(i => dut.io.dmem.wdata(8 * i + 7, 8 * i)))
    dmem.write(dmemWordAddr, wdataVec, dut.io.dmem.wmask.asBools)
  }
  // DUT read port (always enabled; data valid next cycle = sMem state)
  val dmemDutRdata = dmem.read(dmemWordAddr)
  dut.io.dmem.rdata := dmemDutRdata.asUInt

  // -------------------------------------------------------------------------
  // AXI-Lite write path
  // -------------------------------------------------------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg  = Reg(UInt(32.W))
  val wHoldValidReg  = RegInit(false.B)
  val wHoldDataReg   = Reg(UInt(32.W))
  val wHoldStrbReg   = Reg(UInt(4.W))

  val bvalidReg = RegInit(false.B)
  val brespReg  = RegInit(0.U(2.W))

  S.AXI.awready := !awHoldValidReg && !bvalidReg
  S.AXI.wready  := !wHoldValidReg  && !bvalidReg

  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire  = S.AXI.wvalid  && S.AXI.wready

  when(awFire) {
    awHoldValidReg := true.B
    awHoldAddrReg  := S.AXI.awaddr(19, 0)
  }
  when(wFire) {
    wHoldValidReg := true.B
    wHoldDataReg  := S.AXI.wdata
    wHoldStrbReg  := S.AXI.wstrb
  }

  val doWrite = awHoldValidReg && wHoldValidReg && !bvalidReg

  when(doWrite) {
    val a         = awHoldAddrReg
    val wdata     = wHoldDataReg
    val fullWrite = wHoldStrbReg === "b1111".U
    val bresp     = WireDefault(OKAY.U)

    when(!fullWrite) {
      bresp := SLVERR.U
    }.elsewhen(a === p.soft_reset_rw.U) {
      softResetReg     := true.B
      resetCounterReg  := p.reset_cycles.U
      softResetDoneReg := false.B
    }.elsewhen(a === p.enable_rw.U) {
      enableReg := wdata(0)
    }.elsewhen(a === p.entry_addr_rw.U) {
      entryAddrReg := wdata
    }.elsewhen(a === p.entry_addr_we_rw.U) {
      // pulse entryAddr_we; entryAddr must already be set
      dut.io.entryAddr_we := true.B
    }.elsewhen(a >= p.imem_base_rw.U &&
               a <  (p.imem_base_rw + p.imem_depth.toLong * 0x10L).U) {
      // host write to instruction memory (word-addressed)
      val offset   = a - p.imem_base_rw.U
      val wordIdx  = offset >> 4   // divide by 0x10
      val wdataVec = VecInit((0 until 4).map(i => wdata(8 * i + 7, 8 * i)))
      imem.write(wordIdx, wdataVec, "b1111".U.asBools)
    }.elsewhen(a >= p.dmem_base_rw.U &&
               a <  (p.dmem_base_rw + p.dmem_depth.toLong * 0x10L).U) {
      // host write to data memory (word-addressed)
      val offset   = a - p.dmem_base_rw.U
      val wordIdx  = offset >> 4
      val wdataVec = VecInit((0 until 4).map(i => wdata(8 * i + 7, 8 * i)))
      dmem.write(wordIdx, wdataVec, "b1111".U.asBools)
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

  // -------------------------------------------------------------------------
  // AXI-Lite read path
  // -------------------------------------------------------------------------
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

  // Registers to hold the pending memory read type and index
  val memReadIsImem = RegInit(false.B)
  val memReadIdx    = Reg(UInt(32.W))

  // Host-side SyncReadMem read ports (registered; data valid one cycle later)
  // We issue the read in READY2READ and capture in WAIT_MEM -> COMPLETED.
  val imemHostRen  = WireDefault(false.B)
  val imemHostIdx  = WireDefault(0.U(32.W))
  val dmemHostRen  = WireDefault(false.B)
  val dmemHostIdx  = WireDefault(0.U(32.W))

  val imemHostRdata = imem.read(imemHostIdx, imemHostRen)
  val dmemHostRdata = dmem.read(dmemHostIdx, dmemHostRen)

  when(arFire) {
    if (debugprint) printf("%d: arFire: %x\n", cycles, S.AXI.araddr)
    val araddr = S.AXI.araddr(19, 0)
    rrespReg := OKAY.U

    val nextState = WireDefault(RState.COMPLETED)

    when(araddr === p.soft_reset_rw.U) {
      rdataReg := softResetDoneReg.asUInt

    }.elsewhen(araddr === p.enable_rw.U) {
      rdataReg := enableReg.asUInt

    }.elsewhen(araddr === p.entry_addr_rw.U) {
      rdataReg := entryAddrReg

    }.elsewhen(araddr === p.entry_addr_we_rw.U) {
      rdataReg := 0.U

    }.elsewhen(araddr === p.running_r.U) {
      rdataReg := dut.io.running.asUInt

    }.elsewhen(araddr === p.debug_pc_r.U) {
      rdataReg := dut.io.debugPC

    }.elsewhen(araddr === p.debug_cycles_lo_r.U) {
      rdataReg := dut.io.debugCycles(31, 0)

    }.elsewhen(araddr === p.debug_cycles_hi_r.U) {
      rdataReg := dut.io.debugCycles(63, 32)

    }.elsewhen(araddr === p.debug_status_r.U) {
      rdataReg := Cat(0.U(28.W),
                      dut.io.debugStatus.ecall,
                      dut.io.debugStatus.illegalInst,
                      dut.io.debugStatus.halted,
                      dut.io.debugStatus.running)

    }.elsewhen(araddr >= p.debug_regs_base_r.U &&
               araddr <  (p.debug_regs_base_r + 32L * 0x10L).U) {
      // debug register file: 32 entries
      val offset  = araddr - p.debug_regs_base_r.U
      val regIdx  = offset >> 4
      // Mux over all 32 debug registers (combinational Mem read)
      rdataReg := MuxLookup(regIdx, 0.U)(
        (0 until 32).map(i => i.U -> dut.io.debugRegs(i))
      )

    }.elsewhen(araddr >= p.imem_base_rw.U &&
               araddr <  (p.imem_base_rw + p.imem_depth.toLong * 0x10L).U) {
      val offset = araddr - p.imem_base_rw.U
      val idx    = offset >> 4
      imemHostRen := true.B
      imemHostIdx := idx
      memReadIsImem := true.B
      memReadIdx    := idx
      nextState := RState.WAIT_MEM

    }.elsewhen(araddr >= p.dmem_base_rw.U &&
               araddr <  (p.dmem_base_rw + p.dmem_depth.toLong * 0x10L).U) {
      val offset = araddr - p.dmem_base_rw.U
      val idx    = offset >> 4
      dmemHostRen := true.B
      dmemHostIdx := idx
      memReadIsImem := false.B
      memReadIdx    := idx
      nextState := RState.WAIT_MEM

    }.otherwise {
      if (debugprint) printf("%d: bad read req %x\n", cycles, araddr)
      rdataReg := 0xbad00000L.U | S.AXI.araddr(31, 0)
    }

    rstateReg := nextState
  }

  // WAIT_MEM: SyncReadMem data is available this cycle (issued last cycle)
  when(rstateReg === RState.WAIT_MEM) {
    when(memReadIsImem) {
      rdataReg := imemHostRdata.asUInt
    }.otherwise {
      rdataReg := dmemHostRdata.asUInt
    }
    rstateReg := RState.COMPLETED
  }

  when(rstateReg === RState.COMPLETED && S.AXI.rready) {
    rstateReg := RState.READY2READ
  }
}

object AxiSRV32I extends App {
  val p = checkParamEnv(SRV32IModuleParams.default(), "SRV32I_MODULE_PARAMS")
  EmitVerilog.generate(new AxiSRV32I(p, debugprint = true), p)
}
