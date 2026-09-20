# SRV32I

SRV32I is a small RISC-V RV32I core in Chisel, used as a **reference example for the
GarageWorks FPGA test framework**.

This core is not built for performance. Wrapped behind AXI, it can be used as a
lightweight **test scheduler inside an AXI dispatcher**: something that can
run a short program, signal completion, and be polled/reset over the same
AXI register map everything else in the dispatcher already speaks.

See [NOTES.md](NOTES.md) for the core's ISA coverage, pipeline design, and
known limitations.

## Requirements

- A recent Linux distribution (tested with Ubuntu 24, Fedora 42, and Rocky Linux 9.7)
- Verilator (tested with 5.044)
- GCC (tested with 11.1.0 and 15.2.1)
- Java (tested with OpenJDK 17.0.7 and 21.0.11)
- sbt (tested with 1.9.2 and 2.0.8)
- [optional] RV32 cross compiler (tested with riscv32-linux-gnu-gcc 15.2.1) to run non-hex tests
- [optional] Vivado 2025.1 to compile FPGA firmware and program


## Build and run tests on Verilator

The command below prepares environment, build, and run the default test (tests/tb_loop_hex.py).
```
python ./gwscript.py
```

To run other tests
```
source chisel-axi-utils/.venv/bin/activate
cd tests
make rlmiwi
```
Note: to run loop_asm, sum_c, RV32 cross compiler is needed.


## FPGA tests

- Supported FPGA platform : AMD Alveo V80 FPGA with AVED

If you want to run FPGA tests on the V80 AVED stack, set up the environment
to build the V80 AVED stack first:

```
export XILINXD_LICENSE_FILE=...
source $INSTDIR/2025.1/Vitis/settings64.sh
export PATH="$INSTDIR/2025.1/gnu/armr5/lin/gcc-arm-none-eabi/bin:$PATH"
```

Edit .gwconfig
```
fpgatest=true
```

Then,
```
python ./gwscript.py
```


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




