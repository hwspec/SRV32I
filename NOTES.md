# SRV32I core notes

## ISA coverage

- RV32I base ISA: ALU ops, branches, loads/stores, LUI/AUIPC/JAL/JALR
- `FENCE`/`FENCE.I` decoded as NOP; `EBREAK` halts (tracked separately from `ECALL`)
- `ECALL` is the halt instruction, no CSRs, no interrupts, no privilege modes
- `custom-0` opcode reserved as an extension point (`CustomOp` object) for
  adding non-standard instructions without touching decode/hazard plumbing

## Pipeline

2-stage (IF/EX): 1-cycle throughput for most instructions, a 1-cycle bubble
on taken branches/jumps, and a 1-cycle stall only when an instruction
immediately consumes the previous load's result.

`SRV32I.scala` is the DUT. `AxiSRV32I.scala` wraps it behind an AXI4-Lite
register map (control/status registers, instruction and data memory access);
this is the interface both the sim and FPGA testbenches actually drive.
The control interface exposes `enable` / `softReset` / `entryAddr`, plus
debug outputs for all 32 registers, PC, cycle count, and
halt/illegal/ecall/ebreak status.

## Extending with custom-0

`custom-0` (opcode `0b0001011`) decodes with the same rd/rs1/rs2/funct3/funct7
fields as R-type, dispatched by funct3 in the `CustomOp` object. Adding an
instruction there writes back through the normal regfile path with no other
changes needed. The regfile also exposes a third (read-only) port keyed on
rd, for read-modify-write ops that need rd's *current* value as a source
(e.g. an insert/merge instruction), alongside rs1Data/rs2Data.

The current `RLWIMI` slot (a PPC rlwimi-style rotate/mask/insert) is decoded
but stubbed (passthrough of rd, no computation). A real implementation needs
a dedicated immediate field for shift amount and mask bounds; PPC's SH/MB/ME
(15 bits total) don't fit in funct7's 7 bits, so this can't reuse the
existing immI/immS/etc.

## Known limitations

- No CSR/Zicsr, no M-extension (mul/div go through libgcc softcalls)
- `custom-0`/`RLWIMI` stubbed, see above
- Single in-flight load hazard only checked one instruction ahead (matches
  the pipeline's own load-to-writeback distance, so this is exhaustive, not
  a shortcut)
