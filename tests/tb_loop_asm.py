import cocotb

from srv32i_bridge import SRV32I_Bridge, ST_ECALL, ST_HALTED, ST_ILLEGAL, ST_RUNNING
from rv32i_compile import compile_prog


@cocotb.test()
async def tb_loop_asm(cocotb_dut):
    dut = SRV32I_Bridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    program = compile_prog("loop.s")

    st = await dut.run(program, entry=0)

    assert st & ST_ECALL, f"ecall not set: {st:#010x}"
    assert st & ST_HALTED, f"halted not set: {st:#010x}"
    assert not (st & ST_ILLEGAL), f"illegal set: {st:#010x}"
    assert not (st & ST_RUNNING), f"still running: {st:#010x}"

    # loop.s sums 0..99 into x1
    assert await dut.readReg(1) == sum(range(100))

    dut.log.info("Verified!")
