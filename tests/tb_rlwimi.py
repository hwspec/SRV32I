import cocotb

from srv32i_bridge import SRV32I_Bridge, ST_ECALL, ST_HALTED, ST_ILLEGAL, ST_RUNNING, ECALL


def rotl32(v, sh):
    v &= 0xFFFFFFFF
    return ((v << sh) | (v >> (32 - sh))) & 0xFFFFFFFF


def ppc_mask(mb, me):
    # MSB-numbered (bit0=MSB..bit31=LSB), MB <= ME (no wraparound), matches
    # CustomOp.ppcMask in SRV32I.scala.
    width = me - mb + 1
    low = 31 - me
    return ((1 << width) - 1) << low


def encode_rlwimi(rd, rs1, sh, mb, me):
    # custom-0 opcode, immediate form: funct7:rs2:funct3 = SH(5):MB(5):ME(5)
    imm15 = (sh << 10) | (mb << 5) | me
    funct7 = (imm15 >> 8) & 0x7F
    rs2 = (imm15 >> 3) & 0x1F
    funct3 = imm15 & 0x7
    return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0b0001011


CONST1 = 0x12345678
CONST2 = 0xABCDEF01
SH = 8
MB = 16          # MSB-numbered start of the 8-bit field -> mask = 0x0000FF00
ME = 23          # MSB-numbered end of the 8-bit field

program = [
    0x12345537,   # lui  x10, 0x12345          -> x10 = rA = 0x12345678
    0x67850513,   # addi x10, x10, 0x678
    0xabcdf5b7,   # lui  x11, 0xabcdf          -> x11 = rS = 0xabcdef01
    0xf0158593,   # addi x11, x11, -0xff
    encode_rlwimi(rd=10, rs1=11, sh=SH, mb=MB, me=ME),  # rlwimi x10, x11, 8, 16, 23
    ECALL,        # ecall (halt)
]


@cocotb.test()
async def tb_rlwimi(cocotb_dut):
    dut = SRV32I_Bridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    st = await dut.run(program, entry=0)

    assert st & ST_ECALL, f"ecall not set: {st:#010x}"
    assert st & ST_HALTED, f"halted not set: {st:#010x}"
    assert not (st & ST_ILLEGAL), f"illegal set: {st:#010x}"
    assert not (st & ST_RUNNING), f"still running: {st:#010x}"

    mask = ppc_mask(MB, ME)
    rotated = rotl32(CONST2, SH)
    expected_rA = (CONST1 & ~mask & 0xFFFFFFFF) | (rotated & mask)

    assert await dut.readReg(11) == CONST2
    assert await dut.readReg(10) == expected_rA

    cycles = await dut.readCycles()
    dut.log.info(f"rlwimi cycles = {cycles - 6}")
    assert cycles > 0, "Cycle counter should be non-zero after execution"

    dut.log.info("Verified!")
