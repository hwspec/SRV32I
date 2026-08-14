#!/usr/bin/env python3
"""
benchgen.py

Given a Chisel DUT (e.g. SRV32I.scala), uses the Anthropic API to generate,
in the current directory:
  1. Axi<Prefix>.scala  -- an AXI4-Lite bridge, following chisel-axi-utils
     conventions (worked example pulled live from the repo: Cmd or TestQ)
  2. tb_<prefix_lower>.py + Makefile -- a cocotb testbench for that bridge

Usage:
  export ANTHROPIC_API_KEY=...
  python benchgen.py path/to/SRV32I.scala
  python benchgen.py path/to/SRV32I.scala --dut-prefix Core --example testq
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import anthropic

MODEL = "claude-sonnet-4-6"  # update to your current model string if needed
REPO_URL = "https://github.com/kazutomo/chisel-axi-utils.git"

client = anthropic.Anthropic(api_key=os.environ.get("ANTHROPIC_API_KEY"))


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def read(path) -> str:
    return Path(path).read_text()


def write(path, content: str) -> None:
    p = Path(path)
    p.write_text(content)
    print(f"wrote {p}")


def parse_package(dut_src: str) -> str:
    m = re.search(r"^\s*package\s+(\S+)", dut_src, re.MULTILINE)
    if not m:
        raise ValueError("could not find a `package <name>` line in the DUT file")
    return m.group(1)


def detect_example(dut_src: str) -> str:
    """Picks the closer-shaped worked example based on the DUT's own IO:
    Decoupled/Valid-Ready streaming ports look more like TestQ (a queue)
    than Cmd (plain registers)."""
    if re.search(r"\bDecoupled(IO)?\s*\(|\bValid\s*\(|Flipped\s*\(\s*Decoupled", dut_src):
        return "testq"
    return "cmd"


def fetch_example(example: str) -> dict[str, str]:
    """Clones chisel-axi-utils and returns the Cmd or TestQ worked example
    (bridge scala source, cocotb testbench, Makefile)."""
    names = {"cmd": "Cmd", "testq": "TestQ"}
    hwname = names[example]
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            ["git", "clone", "--depth", "1", REPO_URL, tmp],
            check=True, capture_output=True,
        )
        root = Path(tmp)
        bridge = root / "src/main/scala/axi_examples" / f"Axi4Lite32{hwname}.scala"
        tb = root / "tests" / hwname / f"tb_{hwname.lower()}.py"
        makefile = root / "tests" / hwname / "Makefile"
        return {
            "bridge": bridge.read_text(),
            "tb": tb.read_text(),
            "makefile": makefile.read_text(),
        }


def extract_files(response_text: str) -> dict[str, str]:
    """Parses '### FILE: <name>\\n```lang\\n<content>\\n```' blocks."""
    pattern = re.compile(
        r"###\s*FILE:\s*(\S+)\s*\n```[a-zA-Z0-9]*\n(.*?)```",
        re.DOTALL,
    )
    files = {name.strip(): content.strip() + "\n"
             for name, content in pattern.findall(response_text)}
    if not files:
        raise ValueError(
            "No '### FILE: <name>' code blocks found in model output. "
            "Raw response:\n" + response_text
        )
    return files


def call_claude(system: str, user: str, max_tokens: int = 8000) -> str:
    resp = client.messages.create(
        model=MODEL,
        max_tokens=max_tokens,
        temperature=0,
        system=system,
        messages=[{"role": "user", "content": user}],
    )
    return "".join(block.text for block in resp.content if block.type == "text")


# ---------------------------------------------------------------------------
# Step 1: DUT -> AXI bridge
# ---------------------------------------------------------------------------

BRIDGE_SYSTEM_PROMPT = """\
You generate Chisel AXI4-Lite bridge modules that follow the chisel-axi-utils
framework conventions (https://github.com/kazutomo/chisel-axi-utils).

Conventions you MUST follow, based on the worked example:

- The bridge is a `class Axi<Name>(p: <Name>ModuleParams, debugprint: Boolean = false)
  extends chisel3.Module with axi.HasAxiLite32IO`, exposing `override val S = IO(new AxiLite32IO())`.
- A companion `case class <Name>ModuleParams(...) extends AxiModuleParams with AxiModuleDefParams`
  holds every MMIO register address as a `Long` field (suffixed `_r`, `_w`, or `_rw`),
  plus any memory window base/depth params and `reset_cycles: Int`. It has
  `val moduleName = "<Name>"` and a `default(...)` factory, with
  `implicit val rw: ReadWriter[<Name>ModuleParams] = macroRW` via upickle.
- Register addresses are spaced by 0x10 by convention; a debug-register array
  (e.g. one entry per DUT register) occupies a contiguous block, one register
  every 0x10 bytes, starting at a `_base_r` address.
- Standard soft-reset pattern: `softResetReg`, `softResetDoneReg`,
  `resetCounterReg` counting down from `p.reset_cycles`, and
  `combinedReset = softResetReg || reset.asBool`. The DUT is instantiated
  under `withReset(combinedReset) { Module(new <DUT>) }` AND is also given
  an explicit `softReset` input if the DUT itself exposes one. This part
  is a fixed convention regardless of DUT type.
- AXI-Lite write path: `awHoldValidReg`/`wHoldValidReg` hold address/data
  until both arrive, `doWrite` fires the register match `when/elsewhen` chain,
  `bvalidReg`/`brespReg` complete the response. Full-word writes only
  (`wHoldStrbReg === "b1111".U`), else `SLVERR`.
- AXI-Lite read path: an `RState` ChiselEnum with `READY2READ`, `COMPLETED`,
  and (if any DUT memory is exposed via SyncReadMem) `WAIT_MEM` to absorb the
  extra 1-cycle read latency, since `SyncReadMem.read()` is registered.
  Address matching uses `araddr(19,0)` (1MB MMIO window). Unmapped addresses
  return `0xbad00000 | araddr` with `OKAY` (not `SLVERR`, per convention).

The remaining conventions below apply ONLY when the DUT actually has the
corresponding kind of port. Map whatever the DUT exposes -- do not invent
imem/dmem, entry points, or halt/status semantics for a DUT that has none
of that; map its actual ports instead (see the two IO-shape cases below).

- If the DUT has raw memory-like ports (a combinational address output
  paired with a data input, fixed 1-cycle latency, no handshake -- the
  `imem`/`dmem` pattern), give each one a host-accessible read/write window
  in the register map, typically gated to be writable/readable only while
  the DUT is disabled if the DUT has an enable/start control. Byte-maskable
  memories MUST be declared `SyncReadMem(depth, Vec(4, UInt(8.W)))`, never
  `SyncReadMem(depth, UInt(32.W))`. `SyncReadMem.write(addr, data, mask)`
  requires `data: Vec[T]` so each mask bit lines up with one element -- a
  flat `UInt` will not compile with a mask argument. Slice the write data
  into bytes before writing, and reassemble with `.asUInt` after reading:
    val wdataVec = VecInit((0 until 4).map(i => wdata(8 * i + 7, 8 * i)))
    when(wen) { mem.write(idx, wdataVec, wmask.asBools) }
    val rdata = mem.read(idx, ren).asUInt
- If the DUT has `Decoupled`/`Valid`-`Ready` streaming ports (common on
  accelerators: a data-in queue, a data-out queue, or both), map each such
  port to a small register-mapped handshake instead of a SyncReadMem window:
    - a data register (`_w` for an input queue: writing it asserts `valid`
      for one cycle and supplies `bits`, only completing the AXI write once
      the DUT's `ready` is high that cycle, else stall the write response
      or return the transaction with a busy/retry indication per the
      existing bvalid/brespReg mechanism -- keep the host-facing AXI
      transaction itself always completing in bounded time, and instead
      expose a separate `_ready_r` status bit the host is expected to poll
      before writing, mirroring how `enable`-style control already works)
    - a `_ready_r` or `_valid_r` status bit so the host can poll before
      pushing/popping
    - for an output queue: reading the data register pops one element only
      if `valid` is high; otherwise return a defined sentinel and leave
      a `_valid_r` bit for the host to check first
  Base this handshake register shape on the repo's TestQ example if given
  as the worked example, adapting names/addresses to the new DUT's actual
  queue ports.
- Any DUT status/done/completion output (whatever the DUT calls it -- not
  necessarily "halted") is exposed as a read-only status register, with
  each individual status bit given its own bit position and documented in
  the register-map comment block, exactly as the DUT defines it -- do not
  assume RISC-V-specific semantics like `ecall`/`illegalInst` unless the
  DUT literally has those signals.
- Every other plain control input on the DUT (enable, reset, any config or
  parameter register, etc.) is exposed as a read/write register, mapped 1:1.
- The file ends with:
    object Axi<Name> extends App {
      val p = checkParamEnv(<Name>ModuleParams.default(), "<NAME_UPPER>_MODULE_PARAMS")
      EmitVerilog.generate(new Axi<Name>(p, debugprint = true), p)
    }

You will be given one complete worked example bridge (its own wrapped DUT
may be a different shape than the new one -- e.g. simple register-mapped
control, or a Decoupled queue, rather than a CPU). Study the *structural*
pattern (params case class, register spacing, reset handling, read/write
FSMs, and -- if the example uses one -- the streaming-queue handshake
pattern), not the specific registers or port shape it happens to define,
then produce an equivalent, complete, compilable bridge that covers every
IO port on the new DUT in the same style (naming, spacing, a comment block
documenting the register map at the top of the file).

Output format: return exactly one file, as:

### FILE: <filename>.scala
```scala
<full file contents>
```

No other commentary before or after.
"""


def generate_bridge(dut_src: str, bridge_example_src: str,
                     dut_prefix: str, package: str) -> dict[str, str]:
    user = f"""\
--- WORKED EXAMPLE: AXI BRIDGE (chisel-axi-utils repo) ---
{bridge_example_src}

--- NEW DUT (package {package}) ---
{dut_src}

Generate the AXI4-Lite bridge for this new DUT, named Axi{dut_prefix}
(file Axi{dut_prefix}.scala), in `package {package}`, following the exact
conventions demonstrated above.
"""
    resp = call_claude(BRIDGE_SYSTEM_PROMPT, user)
    return extract_files(resp)


# ---------------------------------------------------------------------------
# Step 2: AXI bridge -> cocotb testbench + Makefile
# ---------------------------------------------------------------------------

TB_SYSTEM_PROMPT = """\
You generate cocotb testbenches for chisel-axi-utils AXI4-Lite bridges
(https://github.com/kazutomo/chisel-axi-utils), following the framework's
COCOTB_Bridge helper (axi_test_bridge.cocotb_bridge.COCOTB_Bridge), which
provides:
  - dut = COCOTB_Bridge(cocotb_dut); await dut.setup()
  - dut.p.<field>            -- register addresses/constants from the
                                 generated <Name>_params.json (PARAMFN env var),
                                 field names matching the bridge's ModuleParams
                                 case class exactly
  - await dut.writeWord(addr, data)
  - v = await dut.readWord(addr)
  - await dut.expectWord(addr, ref, msg="")
  - await dut.softReset(maxloopcnt=1000)   -- pulses soft_reset_rw and polls
                                              until the done bit reads 1

Conventions you MUST follow, based on the worked example:

- Test file: `tb_<name_lowercase>.py`, using `@cocotb.test()` on an
  `async def tb_<name_lowercase>(cocotb_dut):` entry point.
- Exercises the DUT via the register map end to end, shaped by whatever the
  bridge actually exposes:
    - if the bridge has memory windows: load them while disabled, set any
      entry-point/config registers, enable, poll a status register for
      whatever the bridge's status register defines as its completion bit(s)
      (bounded loop, raise on timeout), then assert final state (status
      bits, debug/output registers, memory contents) via readWord.
    - if the bridge has streaming/Decoupled register-mapped handshakes
      (data-in / data-out queues), push input word(s) only after polling
      the corresponding `_ready_r` bit, and pop output word(s) only after
      polling the corresponding `_valid_r` bit, rather than assuming a
      halt/status register drives completion.
  Do not assume CPU-specific concepts (registers x0..x31, `ecall`, program
  counter) unless the bridge's register map actually defines them.
- Status/flag bits are decoded with named bit-mask constants at module level
  (e.g. `ST_DONE = 1 << 1`), matching the bridge's actual status register
  layout and bit names/comments -- do not invent bit names the bridge
  doesn't document. Where the register map gives specific documented
  semantics (e.g. "output does not update past the completion cycle"),
  reflect that exactly in the assertions rather than a generic check.
- If (and only if) the DUT is CPU-like and the register map documents an
  `ecall`-style halt cause, use `ecall` (`0x00000073`) as the halt
  instruction in any hand-encoded test program, NOT `ebreak` (`0x00100073`)
  -- unless the register map's own comments explicitly document `ebreak`
  support, assume only `ecall` is recognized; using `ebreak` will trap as
  an illegal instruction instead and the test will falsely fail with the
  illegal-instruction bit set instead of the ecall bit. This bullet does
  not apply to non-CPU DUTs.
- End with a soft-reset check: disable/quiesce the DUT, call
  `await dut.softReset()`, then read back registers to confirm state
  cleared -- this part is a fixed convention regardless of DUT type.
- Use `dut.log.info(...)` for progress/debug lines, plain `assert` statements
  for checks (with an f-string message where it adds diagnostic value).

Also generate the matching Makefile, following this exact structure (only
substitute the module/package names):

  SIM ?= verilator
  TOPLEVEL_LANG ?= verilog
  TOPNAME=user_accel_bd_wrapper
  HWMODULENAME=<BridgeClassName>
  SRCDIR = $(abspath ../generated/$(HWMODULENAME))
  VERILOG_SOURCES += $(SRCDIR)/*.v $(SRCDIR)/*.sv

  WAVES = 1
  EXTRA_ARGS += --trace --trace-structs

  test : $(SRCDIR)/$(HWMODULENAME).sv
  	@echo $(VERILOG_SOURCES)
  	@PARAMFN=$(SRCDIR)/<ParamsModuleName>_params.json $(MAKE) sim MODULE=tb_<name_lowercase> TOPLEVEL=$(TOPNAME)
  	@echo -e "\\n[Output]"
  	@cat output.log

  $(SRCDIR)/$(HWMODULENAME).sv gen:
  	(cd ../ ; sbt "runMain <package>.<BridgeClassName>")

  include $(shell cocotb-config --makefiles)/Makefile.sim

  clean::
  	rm -f output.txt results.xml *.vcd fpga_simple.py
  	rm -rf __pycache__

(Use tabs for recipe lines, not spaces, since this is a Makefile.)

You will be given one complete worked example (a bridge and its
corresponding testbench + Makefile), followed by a new bridge. Study the
mapping from register map to test sequence, then produce an equivalent,
complete testbench and Makefile for the new bridge.

Output format: return exactly two files, as:

### FILE: tb_<name_lowercase>.py
```python
<full file contents>
```

### FILE: Makefile
```makefile
<full file contents>
```

No other commentary before or after.
"""


def generate_testbench(bridge_src: str, bridge_example_src: str, tb_example_src: str,
                        makefile_example_src: str, dut_prefix: str, package: str) -> dict[str, str]:
    user = f"""\
--- WORKED EXAMPLE: AXI BRIDGE ---
{bridge_example_src}

--- WORKED EXAMPLE: CORRESPONDING TESTBENCH ---
{tb_example_src}

--- WORKED EXAMPLE: CORRESPONDING MAKEFILE ---
{makefile_example_src}

--- NEW AXI BRIDGE (package {package}) ---
{bridge_src}

Generate the cocotb testbench and Makefile for this new bridge
(Axi{dut_prefix}), following the exact conventions demonstrated above.
"""
    resp = call_claude(TB_SYSTEM_PROMPT, user)
    return extract_files(resp)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dut", help="path to the DUT .scala file")
    ap.add_argument("--dut-prefix", default=None,
                     help="name used for Axi<prefix>.scala / tb_<prefix>.py "
                          "(default: DUT filename without extension)")
    ap.add_argument("--example", choices=["cmd", "testq"], default=None,
                     help="which chisel-axi-utils worked example to use "
                          "(default: auto-detected from the DUT's IO -- "
                          "'testq' if it has Decoupled/Valid ports, else 'cmd')")
    args = ap.parse_args()

    dut_src = read(args.dut)
    package = parse_package(dut_src)
    dut_prefix = args.dut_prefix or Path(args.dut).stem
    example_name = args.example or detect_example(dut_src)

    print(f"package: {package}, dut_prefix: {dut_prefix}")
    print(f"fetching '{example_name}' worked example from chisel-axi-utils ...")
    example = fetch_example(example_name)

    print(f"[1/2] generating AXI bridge for {dut_prefix} ...")
    bridge_files = generate_bridge(dut_src, example["bridge"], dut_prefix, package)
    for name, content in bridge_files.items():
        write(name, content)
    bridge_src = next(iter(bridge_files.values()))

    print(f"[2/2] generating cocotb testbench for Axi{dut_prefix} ...")
    tb_files = generate_testbench(
        bridge_src, example["bridge"], example["tb"], example["makefile"],
        dut_prefix, package,
    )
    for name, content in tb_files.items():
        write(name, content)

    print("done.")


if __name__ == "__main__":
    sys.exit(main())
