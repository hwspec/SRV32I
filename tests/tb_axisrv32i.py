import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

# ---------------------------------------------------------------------------
# Bit-mask constants for debug_status_r
#   bits: {28'd0, ecall[3], illegalInst[2], halted[1], running[0]}
# ---------------------------------------------------------------------------
ST_RUNNING      = 1 << 0
ST_HALTED       = 1 << 1
ST_ILLEGAL_INST = 1 << 2
ST_ECALL        = 1 << 3

# Maximum poll iterations before declaring a timeout
MAX_POLL = 100_000


# ---------------------------------------------------------------------------
# Helper: load a list of 32-bit words into instruction memory
# ---------------------------------------------------------------------------
async def load_imem(dut, words):
    for i, w in enumerate(words):
        addr = dut.p.imem_base_rw + i * 0x10
        await dut.writeWord(addr, w & 0xFFFF_FFFF)


# ---------------------------------------------------------------------------
# Helper: load a list of 32-bit words into data memory
# ---------------------------------------------------------------------------
async def load_dmem(dut, words):
    for i, w in enumerate(words):
        addr = dut.p.dmem_base_rw + i * 0x10
        await dut.writeWord(addr, w & 0xFFFF_FFFF)


# ---------------------------------------------------------------------------
# Helper: poll debug_status_r until halted or ecall, bounded by MAX_POLL
# ---------------------------------------------------------------------------
async def poll_until_done(dut):
    for i in range(MAX_POLL):
        status = await dut.readWord(dut.p.debug_status_r)
        if status & (ST_HALTED | ST_ECALL | ST_ILLEGAL_INST):
            return status
    raise TimeoutError(f"CPU did not halt within {MAX_POLL} poll iterations")


@cocotb.test()
async def tb_axisrv32i(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()

    # ------------------------------------------------------------------
    # 1. Verify initial state: enable=0, entry_addr=0, not running
    # ------------------------------------------------------------------
    dut.log.info("=== Check initial register state ===")
    await dut.expectWord(dut.p.enable_rw,       0, msg="enable should be 0 at reset")
    await dut.expectWord(dut.p.entry_addr_rw,   0, msg="entry_addr should be 0 at reset")
    await dut.expectWord(dut.p.running_r,        0, msg="running should be 0 at reset")
    await dut.expectWord(dut.p.entry_addr_we_rw, 0, msg="entry_addr_we read should always be 0")

    # ------------------------------------------------------------------
    # 2. Write / read-back control registers while disabled
    # ------------------------------------------------------------------
    dut.log.info("=== Write/read-back control registers ===")

    test_entry = 0x0000_0000
    await dut.writeWord(dut.p.entry_addr_rw, test_entry)
    await dut.expectWord(dut.p.entry_addr_rw, test_entry,
                         msg="entry_addr round-trip failed")

    # Pulse entry_addr_we (write-only strobe; read always returns 0)
    await dut.writeWord(dut.p.entry_addr_we_rw, 1)
    await dut.expectWord(dut.p.entry_addr_we_rw, 0,
                         msg="entry_addr_we read should always be 0")

    # ------------------------------------------------------------------
    # 3. Load a minimal RISC-V program into imem
    #
    #    We use a tiny hand-encoded RV32I program that:
    #      [0] addi x1, x0, 42      -- x1 = 42  (0x02A00093)
    #      [1] sw   x1, 0(x0)       -- dmem[0] = x1 (0x00102023)
    #      [2] ecall                -- halt     (0x00000073)
    #
    #    SRV32I only decodes ecall (imm[11:0]==0) as a legal halt-causing
    #    instruction -- ebreak (imm[11:0]==1) is NOT implemented and will
    #    trap as illegalInst instead of ecall.
    #
    #    After execution:
    #      - debug_status_r should have ST_HALTED and ST_ECALL set
    #      - debug_regs[1] should be 42
    #      - dmem[0] should be 42
    #      - debug_pc_r should point at the ecall instruction (word 2 = 0x8)
    #        NOTE: per the core design, PC does not advance past the
    #        halt-causing instruction, so debug_pc_r == 0x8.
    # ------------------------------------------------------------------
    dut.log.info("=== Load program into imem ===")

    program = [
        0x02A00093,   # addi x1, x0, 42
        0x00102023,   # sw   x1, 0(x0)
        0x00000073,   # ecall  (halts the core)
    ]
    await load_imem(dut, program)

    # Verify imem round-trip
    for i, expected in enumerate(program):
        addr = dut.p.imem_base_rw + i * 0x10
        v = await dut.readWord(addr)
        assert v == expected, (
            f"imem[{i}] round-trip failed: got 0x{v:08x}, expected 0x{expected:08x}"
        )
    dut.log.info("imem load verified")

    # ------------------------------------------------------------------
    # 4. Set entry address and enable the CPU
    # ------------------------------------------------------------------
    dut.log.info("=== Set entry address and enable CPU ===")

    await dut.writeWord(dut.p.entry_addr_rw, 0x0000_0000)
    await dut.writeWord(dut.p.entry_addr_we_rw, 1)   # latch entry address into DUT
    await dut.writeWord(dut.p.enable_rw, 1)

    v = await dut.readWord(dut.p.enable_rw)
    assert v == 1, f"enable register should read 1, got {v}"

    # ------------------------------------------------------------------
    # 5. Poll until the CPU halts
    # ------------------------------------------------------------------
    dut.log.info("=== Polling for CPU halt ===")
    status = await poll_until_done(dut)
    dut.log.info(f"CPU stopped: debug_status_r = 0x{status:08x}")

    assert status & ST_HALTED, (
        f"Expected ST_HALTED bit set, got status=0x{status:08x}"
    )
    assert status & ST_ECALL, (
        f"Expected ST_ECALL bit set, got status=0x{status:08x}"
    )
    assert not (status & ST_ILLEGAL_INST), (
        f"Unexpected illegal instruction flag in status=0x{status:08x}"
    )

    # ------------------------------------------------------------------
    # 6. Check debug PC
    #    ebreak is at word index 2 => byte address 0x8.
    #    The PC does not advance past the halt-causing instruction.
    # ------------------------------------------------------------------
    dut.log.info("=== Check debug PC ===")
    pc = await dut.readWord(dut.p.debug_pc_r)
    dut.log.info(f"debug_pc = 0x{pc:08x}")
    assert pc == 0x8, f"Expected PC=0x8 (ebreak), got PC=0x{pc:08x}"

    # ------------------------------------------------------------------
    # 7. Check debug register file: x1 should be 42
    # ------------------------------------------------------------------
    dut.log.info("=== Check debug register file ===")
    reg1_addr = dut.p.debug_regs_base_r + 1 * 0x10
    reg1 = await dut.readWord(reg1_addr)
    dut.log.info(f"debug_regs[1] = {reg1}")
    assert reg1 == 42, f"Expected x1=42, got {reg1}"

    # x0 must always be 0
    reg0_addr = dut.p.debug_regs_base_r + 0 * 0x10
    reg0 = await dut.readWord(reg0_addr)
    assert reg0 == 0, f"Expected x0=0, got {reg0}"

    # ------------------------------------------------------------------
    # 8. Check data memory: dmem[0] should be 42 (written by sw)
    # ------------------------------------------------------------------
    dut.log.info("=== Check data memory ===")
    dmem0 = await dut.readWord(dut.p.dmem_base_rw)
    dut.log.info(f"dmem[0] = {dmem0}")
    assert dmem0 == 42, f"Expected dmem[0]=42, got {dmem0}"

    # ------------------------------------------------------------------
    # 9. Check debug cycle counter is non-zero
    # ------------------------------------------------------------------
    dut.log.info("=== Check debug cycle counter ===")
    cyc_lo = await dut.readWord(dut.p.debug_cycles_lo_r)
    cyc_hi = await dut.readWord(dut.p.debug_cycles_hi_r)
    cycles = (cyc_hi << 32) | cyc_lo
    dut.log.info(f"debug_cycles = {cycles}")
    assert cycles > 0, "Expected non-zero cycle count after program execution"

    # ------------------------------------------------------------------
    # 10. Soft reset: disable CPU first, then reset, verify state clears
    # ------------------------------------------------------------------
    dut.log.info("=== Soft reset ===")
    await dut.writeWord(dut.p.enable_rw, 0)
    await dut.expectWord(dut.p.enable_rw, 0, msg="enable should be 0 after disable")

    await dut.softReset()
    dut.log.info("Soft reset complete")

    # After reset: enable, entry_addr, running should all be 0
    await dut.expectWord(dut.p.enable_rw,     0, msg="enable should be 0 after soft reset")
    await dut.expectWord(dut.p.entry_addr_rw, 0, msg="entry_addr should be 0 after soft reset")
    await dut.expectWord(dut.p.running_r,      0, msg="running should be 0 after soft reset")

    # Status should show not running, not halted (core is in reset state)
    status_after = await dut.readWord(dut.p.debug_status_r)
    dut.log.info(f"debug_status after reset = 0x{status_after:08x}")
    assert not (status_after & ST_RUNNING), (
        f"CPU should not be running after soft reset, status=0x{status_after:08x}"
    )

    dut.log.info("Done!!\n")
