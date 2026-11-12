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
RTL and test code. Unlike the other tests, `sum_c` performs
a CPU-executed memory store. Once triggered, the failure persisted
across subsequent runs, suggesting a possible timing or
memory-aliasing issue. Its cause remains under investigation,
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
