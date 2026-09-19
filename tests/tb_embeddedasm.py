import cocotb

from srv32i_bridge import SRV32I_Bridge, ST_ECALL, ST_HALTED, ST_ILLEGAL, ST_RUNNING
from rv32i_compile import compile_prog

ASM = """
addi x1, x0, 0
addi x2, x0, 0
addi x3, x0, 100
loop:
    bge  x2, x3, end
    add  x1, x1, x2
    addi x2, x2, 1
    jal  x0, loop
end:
    ecall
"""


@cocotb.test()
async def tb_embeddedasm(cocotb_dut):
    dut = SRV32I_Bridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    program = compile_prog(ASM)

    st = await dut.run(program, entry=0)

    assert await dut.readReg(1) == sum(range(100))

    dut.log.info("Verified!")
