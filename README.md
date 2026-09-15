# SRV32I

A small RISC-V RV32I core in Chisel, used here mainly as a **reference example for the
GarageWorks FPGA test framework**: one testbench, written once against GarageWorks'
AXI-only DUT interface, runs unmodified against both a cocotb simulation and real
hardware on an AMD Alveo V80.

This core is not built for performance. Wrapped behind AXI, it's useful as a
lightweight **test scheduler inside an AXI dispatcher**: something that can
run a short program, signal completion, and be polled/reset over the same
AXI register map everything else in the dispatcher already speaks.

See [NOTES.md](NOTES.md) for the core's ISA coverage, pipeline design, and
known limitations.

## Why this is a GarageWorks example

GarageWorks testbenches never poke DUT signals directly; every command goes
over AXI. That constraint is what makes a testbench portable: the same
cocotb test, run against `AxiSRV32I.scala`'s register map, works whether the
"AXI slave" underneath is a Verilator/cocotb simulation or an actual AXI4-Lite
endpoint on the V80. `chisel-axi-utils`' `conv_cocotb_to_fpga` tool handles the
sim to FPGA rebasing, so `run_on_fpga.sh` and the `tests/Makefile` target
drive the exact same `tb_*.py` sources; one GarageWorks-style test source,
two run targets.

## GarageWorks status

GarageWorks will be open-sourced soon. For now, use `chisel-axi-utils`,
which has everything needed to build and run this repo's tests:

- [`hwspec/chisel-axi-utils`](https://github.com/hwspec/chisel-axi-utils.git):
  the `COCOTB_Bridge` base class, the AXI-bridge/testbench generation
  approach, and `conv_cocotb_to_fpga` for the sim-to-FPGA rebasing described
  above
- `gwscript.py`: currently local to this repo; it'll move into GarageWorks
  once that's released

## Repo layout

```
gwscript.py                  # entry point; drives tb_*.py against sim or FPGA
src/main/scala/srv32i/
  SRV32I.scala          # core: Decoder, ALU, BranchUnit, RegFile, CustomOp
  AxiSRV32I.scala        # AXI4-Lite wrapper (register map, imem/dmem backing)
src/test/scala/src32i/
  SRV32ISpec.scala       # Scala-level ChiselSim spec (unit-level, not GarageWorks/cocotb)
tests/
  tb_loop.py              # loop test
  tb_rlwimi.py             # custom rlwimi extension test
  tb_rlwimieq.py           # rlwimi-equivalent sequence of plain RV32I instructions
  srv32i_bridge.py         # SRV32I-specific COCOTB_Bridge subclass
  Makefile                 # cocotb simulation target
  run_on_fpga.sh            # runs the same tests against the V80
```

## Supported FPGA platform

- AMD Alveo V80 FPGA with AVED

## Running

If you want to run FPGA tests on the V80 AVED stack, set up the environment
to build the V80 AVED stack first:

```
export XILINXD_LICENSE_FILE=...
source $INSTDIR/2025.1/Vitis/settings64.sh
export PATH="$INSTDIR/2025.1/gnu/armr5/lin/gcc-arm-none-eabi/bin:$PATH"
```

Then, simply:

```
python ./gwscript.py
```

`gwscript.py` lives at the repo top (no `cd` needed) and drives the same
`tb_*.py` sources whether the target is simulation or the V80; it loads a
program via the AXI register map, sets `entryAddr`, pulses `softReset`,
raises `enable`, and polls the debug status register for halt.
