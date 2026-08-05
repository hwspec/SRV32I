import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

# status_r bit layout (see AXI_SRV32I.scala)
ST_RUNNING = 1 << 0
ST_HALTED  = 1 << 1
ST_ILLEGAL = 1 << 2
ST_ECALL   = 1 << 3

# Test program (RV32I):
#   addi x1, x0, 5
#   addi x2, x0, 10
#   add  x3, x1, x2
#   ecall
PROGRAM = [
    0x00500093,  # addi x1, x0, 5
    0x00A00113,  # addi x2, x0, 10
    0x002081B3,  # add  x3, x1, x2
    0x00000073,  # ecall
]


def debug_reg_addr(dut, idx):
    return dut.p.debug_regs_base_r + 0x10 * idx


async def load_program(dut, program):
    # imem/dmem are only host-writable while ctrl.enable == 0
    for i, inst in enumerate(program):
        await dut.writeWord(dut.p.imem_base_rw + i * 4, inst)


async def wait_halted(dut, maxloopcnt=500):
    for _ in range(maxloopcnt):
        status = await dut.readWord(dut.p.status_r)
        if status & ST_HALTED:
            return status
    raise RuntimeError("core did not halt within maxloopcnt reads")


@cocotb.test()
async def tb_srv32i(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()

    # core starts disabled (enableReg RegInit(false)) -- load program first
    await load_program(dut, PROGRAM)

    # set entry PC = 0 and pulse entry_we (self-clearing, only takes effect
    # while enable == 0)
    await dut.writeWord(dut.p.entry_addr_rw, 0)
    await dut.writeWord(dut.p.entry_we_rw, 1)

    # start the core
    await dut.writeWord(dut.p.ctrl_rw, 1)

    status = await wait_halted(dut)
    dut.log.info(f"status={status:#04x}")

    assert status & ST_ECALL, "expected halt cause: ecall"
    assert not (status & ST_ILLEGAL), "unexpected illegal instruction"

    pc = await dut.readWord(dut.p.debug_pc_r)
    dut.log.info(f"debugPC={pc:#x}")
    assert pc == 0xC, f"expected PC at ecall (0xC), got {pc:#x}"

    x1 = await dut.readWord(debug_reg_addr(dut, 1))
    x2 = await dut.readWord(debug_reg_addr(dut, 2))
    x3 = await dut.readWord(debug_reg_addr(dut, 3))
    dut.log.info(f"x1={x1} x2={x2} x3={x3}")
    assert x1 == 5
    assert x2 == 10
    assert x3 == 15

    # stop the core, then soft-reset and verify state clears
    await dut.writeWord(dut.p.ctrl_rw, 0)
    await dut.softReset()

    pc_after_reset = await dut.readWord(dut.p.debug_pc_r)
    status_after_reset = await dut.readWord(dut.p.status_r)
    dut.log.info(f"post-reset pc={pc_after_reset:#x} status={status_after_reset:#04x}")
    assert pc_after_reset == 0
    assert not (status_after_reset & ST_HALTED)
    assert not (status_after_reset & ST_ECALL)

    dut.log.info("SRV32I Verified!!\n")
