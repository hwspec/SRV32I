// =============================================================================
// AXI4-Lite Bridge for SRV32I
// Package: srv32i
//
// Register Map (all offsets relative to MMIO base, 1MB window via addr[19:0]):
//
//  0x0000  soft_reset_rw     [RW] Write 1 to trigger soft reset; read 1 when done
//  0x0010  enable_rw         [RW] CPU enable (1 = running, 0 = halted/idle)
//  0x0020  entry_addr_rw     [RW] Entry address (PC loaded when enable=0 and we written)
//  0x0030  entry_addr_we_rw  [RW] Write 1 to latch entry_addr into PC (auto-clears)
//  0x0040  status_r          [R]  Status bits:
//                                   [0] running
//                                   [1] halted
//                                   [2] illegalInst
//                                   [3] ecall
//  0x0050  debug_pc_r        [R]  Current PC
//  0x0060  debug_cycles_lo_r [R]  Cycle counter [31:0]
//  0x0070  debug_cycles_hi_r [R]  Cycle counter [63:32]
//
//  Debug registers (x0..x31):
//  0x0200 + i*0x10  debug_reg[i]_r  [R]  GPR x<i> value  (i = 0..31)
//
//  Instruction memory window (imem):
//  0x1000 + i*0x10  imem[i]_rw  [RW]  Instruction word at word index i
//                                      (accessible only while enable=0)
//                                      Depth = imem_depth words
//
//  Data memory window (dmem):
//  0x5000 + i*0x10  dmem[i]_rw  [RW]  Data word at word index i
//                                      (accessible only while enable=0)
//                                      Depth = dmem_depth words
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
  soft_reset_rw     : Long,
  enable_rw         : Long,
  entry_addr_rw     : Long,
  entry_addr_we_rw  : Long,
  status_r          : Long,
  debug_pc_r        : Long,
  debug_cycles_lo_r : Long,
  debug_cycles_hi_r : Long,
  debug_regs_base_r : Long,   // base of 32 debug-register slots (0x10 each)
  imem_base_rw      : Long,   // base of imem window
  dmem_base_rw      : Long,   // base of dmem window
  // memory geometry
  imem_depth        : Int,
  dmem_depth        : Int,
  // soft-reset
  reset_cycles      : Int,
) extends AxiModuleParams with AxiModuleDefParams {
  val moduleName = "SRV32I"
}

object SRV32IModuleParams {
  implicit val rw: ReadWriter[SRV32IModuleParams] = macroRW

  def default(): SRV32IModuleParams = new SRV32IModuleParams(
    soft_reset_rw     = 0x0000L,
    enable_rw         = 0x0010L,
    entry_addr_rw     = 0x0020L,
    entry_addr_we_rw  = 0x0030L,
    status_r          = 0x0040L,
    debug_pc_r        = 0x0050L,
    debug_cycles_lo_r = 0x0060L,
    debug_cycles_hi_r = 0x0070L,
    debug_regs_base_r = 0x0200L,  // 0x0200 .. 0x03F0  (32 regs × 0x10)
    imem_base_rw      = 0x1000L,  // 0x1000 .. 0x1FF0  (up to 512 words × 0x10)
    dmem_base_rw      = 0x5000L,  // 0x5000 .. 0x5FF0  (up to 256 words × 0x10)
    imem_depth        = 512,
    dmem_depth        = 256,
    reset_cycles      = 8,
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
  val enableReg       = RegInit(false.B)
  val entryAddrReg    = RegInit(0.U(32.W))
  val entryAddrWeReg  = RegInit(false.B)   // single-cycle pulse, auto-clears

  // -------------------------------------------------------------------------
  // Instruction memory (SyncReadMem, byte-maskable)
  // -------------------------------------------------------------------------
  val imem = SyncReadMem(p.imem_depth, Vec(4, UInt(8.W)))

  // -------------------------------------------------------------------------
  // Data memory (SyncReadMem, byte-maskable)
  // -------------------------------------------------------------------------
  val dmem = SyncReadMem(p.dmem_depth, Vec(4, UInt(8.W)))

  // -------------------------------------------------------------------------
  // DUT instantiation
  // -------------------------------------------------------------------------
  val dut = withReset(combinedReset) { Module(new SRV32I) }

  dut.io.enable       := enableReg
  dut.io.softReset    := softResetReg
  dut.io.entryAddr    := entryAddrReg
  dut.io.entryAddr_we := entryAddrWeReg

  // Auto-clear entryAddr_we after one cycle
  when(entryAddrWeReg) { entryAddrWeReg := false.B }

  // -------------------------------------------------------------------------
  // Connect imem to DUT
  // -------------------------------------------------------------------------
  // The DUT presents imem.addr combinationally; we use SyncReadMem so the
  // data is available the next cycle (matches the DUT's sFetch -> sExec
  // pipeline: address stable in sFetch, data consumed in sExec).
  val imemWordAddr = dut.io.imem.addr >> 2
  val imemRdVec    = imem.read(imemWordAddr)
  dut.io.imem.inst := imemRdVec.asUInt

  // -------------------------------------------------------------------------
  // Connect dmem to DUT
  // -------------------------------------------------------------------------
  val dmemWordAddr = dut.io.dmem.addr >> 2
  // Write path
  val dmemWdataVec = VecInit((0 until 4).map(i => dut.io.dmem.wdata(8 * i + 7, 8 * i)))
  when(dut.io.dmem.wen) {
    dmem.write(dmemWordAddr, dmemWdataVec, dut.io.dmem.wmask.asBools)
  }
  // Read path (registered; DUT reads in sMem state, one cycle after sExec)
  val dmemRdVec = dmem.read(dmemWordAddr, !dut.io.dmem.wen)
  dut.io.dmem.rdata := dmemRdVec.asUInt

  // -------------------------------------------------------------------------
  // AXI-Lite Write Path
  // -------------------------------------------------------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg  = Reg(UInt(20.W))
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
      entryAddrWeReg := wdata(0)
    }.elsewhen(a >= p.imem_base_rw.U &&
               a < (p.imem_base_rw + p.imem_depth.toLong * 0x10L).U) {
      // imem write: only when CPU is disabled
      when(!enableReg) {
        val idx      = (a - p.imem_base_rw.U) >> 4
        val wdataVec = VecInit((0 until 4).map(i => wdata(8 * i + 7, 8 * i)))
        imem.write(idx, wdataVec, wHoldStrbReg.asBools)
      }.otherwise {
        bresp := SLVERR.U
      }
    }.elsewhen(a >= p.dmem_base_rw.U &&
               a < (p.dmem_base_rw + p.dmem_depth.toLong * 0x10L).U) {
      // dmem write: only when CPU is disabled
      when(!enableReg) {
        val idx      = (a - p.dmem_base_rw.U) >> 4
        val wdataVec = VecInit((0 until 4).map(i => wdata(8 * i + 7, 8 * i)))
        dmem.write(idx, wdataVec, wHoldStrbReg.asBools)
      }.otherwise {
        bresp := SLVERR.U
      }
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
  // AXI-Lite Read Path
  // -------------------------------------------------------------------------
  val rdataReg = Reg(UInt(32.W))
  val rrespReg = RegInit(0.U(2.W))

  object RState extends ChiselEnum {
    val READY2READ, WAIT_MEM, COMPLETED = Value
  }

  val rstateReg = RegInit(RState.READY2READ)

  // Registers to remember what kind of memory read is pending
  val pendingImem = RegInit(false.B)
  val pendingDmem = RegInit(false.B)

  S.AXI.arready := rstateReg === RState.READY2READ
  S.AXI.rvalid  := rstateReg === RState.COMPLETED
  S.AXI.rdata   := rdataReg
  S.AXI.rresp   := rrespReg

  val arFire = S.AXI.arvalid && S.AXI.arready

  // SyncReadMem read enables for the host-side read path
  val hostImemRen  = WireDefault(false.B)
  val hostDmemRen  = WireDefault(false.B)
  val hostImemIdx  = Wire(UInt(log2Ceil(p.imem_depth).W))
  val hostDmemIdx  = Wire(UInt(log2Ceil(p.dmem_depth).W))
  hostImemIdx := 0.U
  hostDmemIdx := 0.U

  // Separate SyncReadMem read ports for host access
  val hostImemRdVec = imem.read(hostImemIdx, hostImemRen)
  val hostDmemRdVec = dmem.read(hostDmemIdx, hostDmemRen)

  when(arFire) {
    if (debugprint) printf("%d: arFire: %x\n", cycles, S.AXI.araddr)
    val araddr = S.AXI.araddr(19, 0)
    rrespReg := OKAY.U

    val rstate = WireDefault(RState.COMPLETED)

    when(araddr === p.soft_reset_rw.U) {
      rdataReg := softResetDoneReg
    }.elsewhen(araddr === p.enable_rw.U) {
      rdataReg := enableReg
    }.elsewhen(araddr === p.entry_addr_rw.U) {
      rdataReg := entryAddrReg
    }.elsewhen(araddr === p.entry_addr_we_rw.U) {
      rdataReg := entryAddrWeReg
    }.elsewhen(araddr === p.status_r.U) {
      rdataReg := Cat(0.U(28.W),
                      dut.io.debugStatus.ecall,
                      dut.io.debugStatus.illegalInst,
                      dut.io.debugStatus.halted,
                      dut.io.debugStatus.running)
    }.elsewhen(araddr === p.debug_pc_r.U) {
      rdataReg := dut.io.debugPC
    }.elsewhen(araddr === p.debug_cycles_lo_r.U) {
      rdataReg := dut.io.debugCycles(31, 0)
    }.elsewhen(araddr === p.debug_cycles_hi_r.U) {
      rdataReg := dut.io.debugCycles(63, 32)
    }.elsewhen(araddr >= p.debug_regs_base_r.U &&
               araddr < (p.debug_regs_base_r + 32L * 0x10L).U) {
      val idx = (araddr - p.debug_regs_base_r.U) >> 4
      rdataReg := dut.io.debugRegs(idx)
    }.elsewhen(araddr >= p.imem_base_rw.U &&
               araddr < (p.imem_base_rw + p.imem_depth.toLong * 0x10L).U) {
      val idx = (araddr - p.imem_base_rw.U) >> 4
      hostImemIdx := idx
      hostImemRen := true.B
      pendingImem := true.B
      pendingDmem := false.B
      rstate      := RState.WAIT_MEM
    }.elsewhen(araddr >= p.dmem_base_rw.U &&
               araddr < (p.dmem_base_rw + p.dmem_depth.toLong * 0x10L).U) {
      val idx = (araddr - p.dmem_base_rw.U) >> 4
      hostDmemIdx := idx
      hostDmemRen := true.B
      pendingImem := false.B
      pendingDmem := true.B
      rstate      := RState.WAIT_MEM
    }.otherwise {
      if (debugprint) printf("%d: bad read req %x\n", cycles, araddr)
      rdataReg := 0xbad00000L.U | S.AXI.araddr(31, 0)
    }

    rstateReg := rstate
  }

  // One cycle after issuing the SyncReadMem read, capture the result
  when(rstateReg === RState.WAIT_MEM) {
    when(pendingImem) {
      rdataReg := hostImemRdVec.asUInt
    }.elsewhen(pendingDmem) {
      rdataReg := hostDmemRdVec.asUInt
    }
    pendingImem := false.B
    pendingDmem := false.B
    rstateReg   := RState.COMPLETED
  }

  when(rstateReg === RState.COMPLETED && S.AXI.rready) {
    rstateReg := RState.READY2READ
  }
}

object AxiSRV32I extends App {
  val p = checkParamEnv(SRV32IModuleParams.default(), "SRV32I_MODULE_PARAMS")
  EmitVerilog.generate(new AxiSRV32I(p, debugprint = true), p)
}
