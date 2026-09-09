# Copyright (c) 2026, UChicago Argonne, LLC.
# License: See LICENSE in the project top-level directory.

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

    assert await dut.readPC() == 0x14, "PC should be past ecall"
    assert await dut.readReg(1) == 42
    assert await dut.readReg(2) == 7
    assert await dut.readReg(3) == 49
    assert await dut.readData(0) == 49

    cycles = await dut.readCycles()
    dut.log.info(f"cycles = {cycles}")
    assert cycles > 0, "Cycle counter should be non-zero after execution"

    dut.log.info("Verified!")
