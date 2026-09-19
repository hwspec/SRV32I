import cocotb

from srv32i_bridge import SRV32I_Bridge, ST_ECALL, ST_HALTED, ST_ILLEGAL, ST_RUNNING
from rv32i_compile import compile_prog

@cocotb.test()
async def tb_sum_c(cocotb_dut):
    dut = SRV32I_Bridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    program = compile_prog("sum.c")

    st = await dut.run(program, entry=0)

    # sum.c stores sum(0..99) to dmem[0]
    assert await dut.readData(0) == sum(range(100))

    dut.log.info("Verified!")
