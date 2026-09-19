import cocotb

from srv32i_bridge import SRV32I_Bridge, ST_ECALL, ST_HALTED, ST_ILLEGAL, ST_RUNNING, ECALL

program = [
    0x00000093,   # addi x1, x0, 0
    0x00000113,   # addi x2, x0, 0
    0x06400193,   # addi x3, x0, 100
    0x00315863,   # loop: bge x2, x3, end
    0x002080b3,   # add x1, x1, x2
    0x00110113,   # addi x2, x2, 1
    0xff5ff06f,   # jal x0, loop
    ECALL,        # end: ecall (halt)
]


@cocotb.test()
async def tb_loop(cocotb_dut):
    dut = SRV32I_Bridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    st = await dut.run(program, entry=0)

    assert await dut.readReg(1) == 4950
    assert await dut.readReg(2) == 100
    assert await dut.readReg(3) == 100

    cycles = await dut.readCycles()
    dut.log.info(f"cycles = {cycles}")
    assert cycles > 0, "Cycle counter should be non-zero after execution"

    dut.log.info("Verified!")
