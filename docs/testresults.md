# RISC-V Processor: SRV32I

SRV32I ([https://github.com/hwspec/SRV32I](https://github.com/hwspec/SRV32I))
is an open-source, Chisel-based implementation of a 32-bit
RISC-V processor supporting the RV32I base integer instruction set.
Developed from scratch for GarageWorks,
it omits privileged-mode features and is not performance-optimized.
It provides an
end-to-end template for processor development, including compiler
integration, program loading, execution, and verification in both
RTL simulation and FPGA testing.

A Chisel-based GWCD exposes control registers, instruction and data
memories, and processor state through AXI4-Lite. The same GWCD and
shared cocotb/Python testbench are used for RTL simulation and
FPGA testing. The workflow also integrates the RISC-V software
toolchain, allowing assembly and C programs to be compiled,
loaded, executed, and verified automatically.

The shared SRV32I testbench was evaluated using Verilator-based
RTL simulation and FPGA execution on an AMD Alveo V80.
Claude Code orchestrated two distributed `gws --mcp` servers:
`gws-local` for simulation and `gws-alveo`
for hardware execution through SSH to the Alveo board.

For each test, the MCP client invoked the `run` tool
with the target stage and FPGA option to launch a background
job, then polled `job` until completion and extracted
the stage time from the execution log. The first five targets
were each executed five times on both backends; the long-running
`longloop` target was run once per backend. Jobs were
serialized within each server, while simulation and FPGA
executions could overlap.

The table below reports GWS stage times.
The cocotb stage time excludes the initial Verilator build, while
the FPGA stage reuses an already-programmed bitstream.
The separate FPGA build (Vivado 2025.1) takes approximately 48 minutes,
including synthesis, place-and-route, and bitstream generation.
The complete V80 implementation, including AVED infrastructure,
uses less than 2% of available LUTs, registers, and block RAM
resources. The SRV32I core runs at 100 MHz; both the 100 and
250 MHz clock domains meet timing.
These figures characterize the demonstration platform, not an
optimized processor implementation.
The `longloop` test executes roughly six million core cycles
and takes 1093.5 s in cocotb versus 0.7 s on FPGA, a roughly
1560-fold reduction in GWS stage time. These stage times include
cocotb testbench overhead rather than representing standalone
Verilator performance; FPGA times also include host-side
orchestration and communication rather than core execution alone.

The `sieve_c` test is a Sieve of Eratosthenes (N=700, primality
flags packed into a 32-bit-word bitmask in data memory and built
at runtime) wrapped in an outer repeat loop (50 repetitions) to
inflate the total instruction count into the same multi-million-cycle
range as `longloop`, giving a second, algorithmically distinct
data point for the cocotb-versus-FPGA speedup comparison. Each
repetition recomputes the sieve from scratch and writes the final
count of primes below 700 (125) to a fixed data-memory location,
which the testbench checks independently of the cycle count. A
single run executes 4,654,172 core cycles.

The `sum_c` test exposed a hardware-specific failure: the
processor sometimes halted with an illegal-instruction trap rather
than completing via `ecall`. This behavior appeared on FPGA
hardware but not in Verilator/cocotb simulation with identical
RTL and test code. Once triggered, the failure persisted
across subsequent runs. The root cause — an unreset register file that
let FPGA test runs inherit stale architectural register state from
whatever test ran before them on the same bitstream — was tracked down by
reproducing the failure on demand and is described in detail below,
illustrating the value of FPGA-in-the-loop verification.

## Table: GWS-reported cocotb (Verilator) and FPGA (AMD Alveo V80) stage times

Values are mean ± sample standard deviation over five consecutive runs
per target and backend, except as noted.

| Target | Description | cocotb stage (s) | FPGA stage (s) |
|---|---|---|---|
| loop_hex | Loop in machine code (hex) | 14.48 ± 0.04 | 0.18 ± 0.05 |
| loop_asm | Loop in RISC-V assembly | 16.76 ± 0.13 | 0.22 ± 0.05 |
| rlwimieq | PowerPC rlwimi equivalent using RV32I | 14.46 ± 0.05 | 0.12 ± 0.05 |
| rlwimi | Custom SRV32I instruction implementing rlwimi | 14.70 ± 0.51 | 0.20 ± 0.00 |
| sum_c | Summation program written in C | 16.46 ± 0.29 | 0.23 ± 0.06* |
| longloop | 2,000,000-iteration loop in RISC-V assembly | 1093.5† | 0.7† |
| sieve_c | Sieve of Eratosthenes (N=700) × 50 reps in C | 347.52 ± 17.47 | 0.42 ± 0.38 |

\* For `sum_c` on FPGA, only three of five runs passed (P,P,P,F,F); statistics
use the three successful runs.

† The `longloop` values are single runs (no standard deviation), covering
approximately 6×10⁶ core clock cycles.

## Root cause of the `sum_c` FPGA-only illegal-instruction failure

The intermittent `sum_c` failure was reproduced on demand by re-running the
FPGA `testhw` stage repeatedly against an already-programmed bitstream, and
diagnosed by extending the test bridge to dump the processor's debug PC,
architectural registers, and cycle count whenever the status register
reported `ST_ILLEGAL`. A representative failing run read back
`status=0x6` (`ST_HALTED | ST_ILLEGAL`), `pc=0x0000135a`, and
`x1=0x00001356` — both values two bytes short of 4-byte alignment, even
though the core implements no compressed-instruction (RVC) support and
every legitimate branch/jump target in RV32I code is word-aligned. Since
`x1` (the return-address register, `ra`) held the same misaligned value the
core had just faulted on, the failure was a genuine architectural
corruption of register state during program execution, not an artifact of
the debug-readback path.

The register file (`RegFile` in `SRV32I.scala`) backed its 32 architectural
registers with a plain Chisel `Mem(32, UInt(32.W))`. In Chisel, `Mem`
contents are never touched by reset — only `Reg`/`RegInit` respond to
`reset`/`withReset`, a distinction Chisel preserves even though the entire
core is instantiated via `withReset(combinedReset)` in the enclosing GWCD.
Issuing a soft reset therefore cleared the processor's control/status state
but silently left all 32 general-purpose registers holding whatever values
they last held.

This gap is invisible in Verilator/cocotb simulation because each cocotb
test elaborates a brand-new DUT instance, so every register starts at its
Chisel-specified zero value regardless of the reset bug. On the FPGA,
however, a single programmed bitstream persists across many sequential
`gws` test invocations within one session: softReset runs before each test,
but the register file is never actually cleared, so `sum_c` can inherit
leftover register contents from whichever test happened to run immediately
before it. This explains all three observed symptoms at once: the failure
is FPGA-only (simulation always starts clean), intermittent (it depends on
test execution order/history, not on `sum_c` alone), and persistent once
triggered within a run (nothing in the normal control flow ever clears the
stale value).

The fix replaces the unreset `Mem` with an explicitly resettable register
array:

```scala
// before
val regs = Mem(32, UInt(32.W))

// after
val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
```

`RegInit`-backed storage is a true Chisel `Reg`, so it responds correctly
to `combinedReset`/`softReset`, guaranteeing all 32 general-purpose
registers are zeroed on every soft reset regardless of what ran
previously on the same bitstream. Read/write port behavior and priority
between the two existing write ports (`wen` and `wenLoad`) are preserved
unchanged; only the reset semantics of the underlying storage changed.

Hardware re-validation confirmed the fix. After regenerating the RTL,
rebuilding the FPGA bitstream (Vivado 2025.1, ~50 minutes), and
reprogramming the Alveo V80 with the new bitstream, `sum_c` was re-run
five consecutive times against the same programmed bitstream. All five
runs passed (P,P,P,P,P; FPGA stage time 0.32 ± 0.04 s), compared with the
original P,P,P,F,F pattern, confirming the illegal-instruction failure no
longer reproduces.

## Two RTL bugs found while developing `sieve_c`

Developing `sieve_c` — the first test program to exercise sub-word
loads/stores (`sieve.c`'s packed bitmask) together with tight,
register-reusing ALU chains right after a load — surfaced two
distinct, previously-latent RTL bugs. Both were root-caused by static
inspection of the RTL and confirmed with an independent, from-scratch,
non-pipelined RV32I instruction-set simulator written purely as a
ground-truth oracle, decoupled from any RTL bug: hand-assembled repro
sequences were run through both the simulator and real `cocotb`
hardware and the two sources of truth compared directly. Both bugs
were fixed directly in the RTL (not worked around in software) and
re-verified end-to-end: full local `cocotb` regression, then FPGA
rebuild (Vivado 2025.1, ~50 minutes), reprogramming, and FPGA
re-validation, all passing.

### Bug 1: byte-lane addressing ignored for SB/LB/LBU/SH/LH/LHU

`sieve.c` packs 700 primality flags into a 32-bit-word bitmask, so
every access is a full-word `lw`/`sw` and this bug does not actually
manifest in the `sieve_c` benchmark itself — it was found by
inspection while investigating the pipeline bug below, since both
trace through the same data-memory access path. `MemAccess` computed
the store byte-enable mask and sign/zero-extended load data purely
from `funct3`, never consulting the address's low two bits:

```scala
// before
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
```

Every `SB`/`SH` therefore always wrote byte lane 0 of its target word
regardless of the actual address, and every `LB`/`LBU`/`LH`/`LHU`
always read byte lane 0 back, silently corrupting any sub-word access
at a non-zero byte offset. The fix threads the address's low two bits
(`addrLow`, i.e. `memAddr(1,0)`) through `storeMask`, a new
`storeData`, and `loadData`, shifting the byte-enable mask, the write
data, and the read data into/out of the correct lane:

```scala
// after
def storeMask(funct3: UInt, addrLow: UInt): UInt = {
  val baseMask = MuxLookup(funct3, "b1111".U)(Seq(
    "b000".U -> "b0001".U, // SB
    "b001".U -> "b0011".U, // SH
    "b010".U -> "b1111".U  // SW
  ))
  (baseMask << addrLow)(3, 0)
}

def storeData(wdata: UInt, addrLow: UInt): UInt = wdata << (addrLow << 3)

def loadData(funct3: UInt, rdata: UInt, addrLow: UInt): UInt = {
  val aligned = rdata >> (addrLow << 3)
  MuxLookup(funct3, aligned)(Seq(
    "b000".U -> Cat(Fill(24, aligned(7)), aligned(7, 0)),    // LB
    "b001".U -> Cat(Fill(16, aligned(15)), aligned(15, 0)),  // LH
    "b010".U -> rdata,                                        // LW
    "b100".U -> Cat(0.U(24.W), aligned(7, 0)),                // LBU
    "b101".U -> Cat(0.U(16.W), aligned(15, 0))                // LHU
  ))
}
```

`io.dmem.wdata`/`io.dmem.wmask` are driven from `storeData`/`storeMask`
with `memAddr(1,0)`, and the load-side write port
(`regFile.io.wdataLoad`) is driven from `loadData` using a new pipeline
register, `loadPendAddrLReg`, that captures `memAddr(1,0)` for the
completing load so the correct lane is selected a cycle later in
writeback (see `loadPendAddrLReg` below).

### Bug 2: load-use stall didn't squash the following instruction

Early `sieve_c` runs produced silently wrong numeric results — not a
crash or an illegal-instruction trap, but plausible-looking garbage
(for example, an expected count of 4 came back as 168 for a small
test case). The symptom only appeared for specific instruction
sequences (two chained ALU instructions that both touched the same
register right after a `lw`, or a `lw` immediately followed by a
`sw`/other instruction), which pointed at the processor's load-use
hazard handling rather than at the test programs themselves.

SRV32I's execution pipeline has only two stages, IF and EX, with no
explicit IF/EX latch: imem's own one-cycle synchronous-read output
*is* the pipeline register (`pcFetchReg`/`pcExReg` in
`SRV32I.scala`). A load's result is not available until the cycle
after it leaves EX, so a true load-use hazard (the very next
instruction reading the register a `lw` is about to write) is
detected explicitly:

```scala
// SRV32I.scala:378-382
val stall = !bubbleReg && loadPendReg && loadPendRdReg =/= 0.U &&
  (dec.rs1 === loadPendRdReg || dec.rs2 === loadPendRdReg ||
    ((writesRd || dec.isLoad) && dec.rd === loadPendRdReg))

val instrValid = !bubbleReg && !stall
```

Setting `instrValid := false` correctly keeps the hazardous
instruction from executing with a stale operand for one cycle, and a
second register-file write port (`wenLoad`/`waddrLoad`/`wdataLoad`)
lets it pick up the freshly-loaded value when it is re-issued. The bug
was in the PC bookkeeping that is supposed to make that re-issue
happen:

```scala
// before (SRV32I.scala, "Pipeline advance")
pcFetchReg := Mux(stall, pcExReg,
  Mux(flushRedirect, redirectTarget, pcFetchReg +% 4.U))
pcExReg   := pcFetchReg
bubbleReg := flushRedirect // next cycle's instruction is a bubble iff this cycle flushed
```

`pcFetchReg` was correctly held at the stalling instruction's own
address during a stall, so imem kept re-fetching the same word.
However, `pcExReg := pcFetchReg` was unconditional — on a stall cycle
it still advanced to whatever was already one cycle ahead in the
fetch stage, i.e. the instruction *after* the stalling one. And
`bubbleReg`, the mechanism that correctly squashes an already-fetched,
now-stale instruction after a taken branch, had no equivalent term for
`stall`. The net effect: the instruction immediately following a
stalling load-consumer was phantom-executed one cycle early with
pre-hazard operands, and because `pcExReg`/`pcFetchReg` never actually
walked back, that same instruction was then fetched and executed a
second time once the stalled instruction's correct, delayed replay had
gone through. This single defect explained every observed symptom: an
extra `addi` application, an extra `slli` doubling, and the original
`lw`/`sw` failures (where the phantom-executed instruction immediately
after the store was often `ecall`, which latched `haltedReg` early and
silently dropped the real store that should have executed one cycle
later).

The fix adds `stall` as a second bubble-inducing condition, so the
instruction speculatively fetched behind a stalling load-consumer is
squashed exactly like a branch-flushed one, and adds
`loadPendAddrLReg` (used by the byte-lane fix above) to the same
load-pending bookkeeping that already tracked the load's destination
register and `funct3`:

```scala
// after (SRV32I.scala, "Pipeline advance")
pcFetchReg := Mux(stall, pcExReg,
  Mux(flushRedirect, redirectTarget, pcFetchReg +% 4.U))
pcExReg   := pcFetchReg
bubbleReg := flushRedirect || stall

loadPendReg      := instrValid && dec.isLoad
loadPendRdReg    := dec.rd
loadPendF3Reg    := dec.funct3
loadPendAddrLReg := memAddr(1, 0)
```

Both fixes landed together in a single RTL change (commit `8a7055e`,
"Fix byte-lane addressing and load-use stall squash bugs in SRV32I"),
and were validated by: the full local `cocotb` regression
(`loop_hex`, `loop_asm`, `rlwimieq`, `rlwimi`, `sum_c`, `sieve_c`, all
passing); a fresh FPGA bitstream build, programming, and five
consecutive FPGA `sieve_c` runs (all passing, matching the local
`cocotb` cycle count of 4,654,172 exactly); and five consecutive FPGA
`sum_c` runs (all passing), confirming the earlier register-file reset
fix was unaffected.
