#!/usr/bin/env python3
# Copyright (c) 2026, UChicago Argonne, LLC.
# License: See LICENSE in the project top-level directory.
#
# Usage: gen_tb.py NAME input.txt
#   input.txt describes, in plain text, the program to run and what to check.
#   Writes tb_NAME.py in the current directory.

import sys
import os
import re
import anthropic

MODEL = "claude-sonnet-5"

BRIDGE_REFERENCE = '''\
# srv32i_bridge.py (SRV32I_Bridge) -- available methods:
#   await dut.setup()
#   await dut.softReset()
#   await dut.run(program, entry=0, timeout=50000) -> status word
#       (loads program into imem, sets entry, enables, waits for halt, disables)
#   await dut.readReg(idx)         # debug register file
#   await dut.readPC()             # debug PC (points PAST ecall after halt)
#   await dut.readData(addr)       # dmem read (byte address, CPU must be disabled)
#   await dut.writeData(addr, val) # dmem write (CPU must be disabled)
#   await dut.readCycles()         # 64-bit cycle counter
# Status bits: ST_RUNNING, ST_HALTED, ST_ILLEGAL, ST_ECALL (imported from srv32i_bridge)
# ECALL = 0x00000073 is the halt instruction and must terminate every program.
'''

TB_EXAMPLE = '''\
import cocotb

from srv32i_bridge import SRV32I_Bridge, ST_ECALL, ST_HALTED, ST_ILLEGAL, ST_RUNNING, ECALL

program = [
    0x02a00093,   # addi x1, x0, 42
    0x00700113,   # addi x2, x0, 7
    0x002081b3,   # add  x3, x1, x2
    0x00302023,   # sw   x3, 0(x0)
    ECALL,        # ecall  (halt)
]


@cocotb.test()
async def tb_basic(cocotb_dut):
    dut = SRV32I_Bridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    st = await dut.run(program, entry=0)

    assert st & ST_ECALL, f"ecall not set: {st:#010x}"
    assert st & ST_HALTED, f"halted not set: {st:#010x}"
    assert not (st & ST_ILLEGAL), f"illegal set: {st:#010x}"
    assert not (st & ST_RUNNING), f"still running: {st:#010x}"

    assert await dut.readReg(1) == 42
    assert await dut.readReg(2) == 7
    assert await dut.readReg(3) == 49
    assert await dut.readData(0) == 49

    cycles = await dut.readCycles()
    dut.log.info(f"cycles = {cycles}")
    assert cycles > 0, "Cycle counter should be non-zero after execution"

    dut.log.info("Verified!")
'''


def extract_text(resp):
    for block in resp.content:
        if block.type == "text":
            return block.text.strip()
    raise RuntimeError(
        f"No text block in response (stop_reason={resp.stop_reason}); "
        "raise max_tokens"
    )


def gen_machine_code(client, spec_text):
    """Step 1: hand-assemble RV32I machine code from the plain-text program spec."""
    prompt = f"""You are hand-assembling a program for a plain RV32I core (SRV32I).
There is no compiler yet, so you must emit raw 32-bit RV32I machine code words.
The program MUST end with ECALL (0x00000073), the only supported halt instruction.
No pseudo-instructions beyond standard RV32I; no CSR/system instructions besides ecall.

Program spec:
---
{spec_text}
---

Output ONLY a Python list literal named `program`, one instruction per line,
each line a hex word followed by a `# <asm>` comment, e.g.:

program = [
    0x02a00093,   # addi x1, x0, 42
    ECALL,        # ecall  (halt)
]

Use the bare name ECALL (already imported) for the halt instruction instead of
re-writing 0x00000073. No other text."""

    resp = client.messages.create(
        model=MODEL,
        max_tokens=2000,
        thinking={"type": "disabled"},
        messages=[{"role": "user", "content": prompt}],
    )
    return extract_text(resp)


def gen_testbench(client, name, spec_text, program_code):
    """Step 2: build the full cocotb testbench around the assembled program."""
    prompt = f"""Write a cocotb testbench file for the SRV32I core using SRV32I_Bridge.

{BRIDGE_REFERENCE}

Style example (tb_basic.py):
---
{TB_EXAMPLE}
---

The test function must be named `tb_{name}` and decorated with @cocotb.test().
Use this already-assembled program (paste verbatim, do not re-derive it):
---
{program_code}
---

What to check (from the spec, translate into asserts after dut.run()):
---
{spec_text}
---

Keep it as concise as possible, matching the style example. Always check the
halt status bits (ST_ECALL, ST_HALTED, not ST_ILLEGAL, not ST_RUNNING) before
the spec-specific asserts. Output ONLY the final Python file contents, no
markdown fences, no commentary."""

    resp = client.messages.create(
        model=MODEL,
        max_tokens=3000,
        thinking={"type": "disabled"},
        messages=[{"role": "user", "content": prompt}],
    )
    return extract_text(resp)


def strip_fences(text):
    m = re.match(r"^```(?:python)?\s*\n(.*)\n```\s*$", text, re.DOTALL)
    return m.group(1) if m else text


def main():
    args = sys.argv[1:]
    if len(args) == 1:
        infile = args[0]
        name = os.path.basename(infile)
        if name.endswith(".tbspec"):
            name = name[: -len(".tbspec")]
    elif len(args) == 2:
        name, infile = args
    else:
        sys.exit(f"Usage: {sys.argv[0]} [NAME] input.tbspec")

    with open(infile) as f:
        spec_text = f.read()

    client = anthropic.Anthropic()

    program_code = strip_fences(gen_machine_code(client, spec_text))
    tb_code = strip_fences(gen_testbench(client, name, spec_text, program_code))

    outfile = f"tb_{name}.py"
    with open(outfile, "w") as f:
        f.write(tb_code)
        if not tb_code.endswith("\n"):
            f.write("\n")

    print(f"Wrote {outfile}")


if __name__ == "__main__":
    main()
