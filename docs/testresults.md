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
