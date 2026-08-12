import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

# ---------------------------------------------------------------------------
# Status register bit masks (status_r, offset 0x0040)
#   [0] running
#   [1] halted
#   [2] illegalInst
#   [3] ecall
# ---------------------------------------------------------------------------
ST_RUNNING     = 1 << 0
ST_HALTED      = 1 << 1
ST_ILLEGAL     = 1 << 2
ST_ECALL       = 1 << 3

# ---------------------------------------------------------------------------
# RISC-V encodings used in hand-assembled test programs
# ---------------------------------------------------------------------------
ECALL          = 0x00000073   # environment call – the documented halt cause

# ---------------------------------------------------------------------------
# Helper: poll status register until the expected bits are set (or timeout)
# ---------------------------------------------------------------------------
async def poll_status(dut, mask, timeout=50000):
    for _ in range(timeout):
        st = await dut.readWord(dut.p.status_r)
        if (st & mask) == mask:
            return st
    raise TimeoutError(
        f"Timed out waiting for status mask 0x{mask:08x}; "
        f"last status=0x{st:08x}"
    )


@cocotb.test()
async def tb_axisrv32i(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()

    # -----------------------------------------------------------------------
    # 1. Verify initial state: CPU should be disabled / not running
    # -----------------------------------------------------------------------
    dut.log.info("=== 1. Check initial state ===")
    en = await dut.readWord(dut.p.enable_rw)
    dut.log.info(f"enable_rw = {en:#010x}")
    assert en == 0, f"Expected CPU disabled at reset, got {en:#010x}"

    st = await dut.readWord(dut.p.status_r)
    dut.log.info(f"status_r  = {st:#010x}")
    # CPU is not running; halted bit may or may not be set depending on
    # reset state – we just confirm the running bit is clear.
    assert (st & ST_RUNNING) == 0, \
        f"CPU should not be running after reset, status={st:#010x}"

    # -----------------------------------------------------------------------
    # 2. Load a minimal RISC-V program into imem while CPU is disabled.
    #
    #    Program (word indices 0..):
    #      0: addi x1, x0, 42    -> 0x02a00093
    #      1: addi x2, x0, 7     -> 0x00700113
    #      2: add  x3, x1, x2    -> 0x002081b3
    #      3: sw   x3, 0(x0)     -> 0x00302023  (store result to dmem[0])
    #      4: ecall               -> 0x00000073  (halt)
    #
    #    Expected: x1=42, x2=7, x3=49, dmem[0]=49
    # -----------------------------------------------------------------------
    dut.log.info("=== 2. Load instruction memory ===")

    program = [
        0x02a00093,   # addi x1, x0, 42
        0x00700113,   # addi x2, x0, 7
        0x002081b3,   # add  x3, x1, x2
        0x00302023,   # sw   x3, 0(x0)
        ECALL,        # ecall  (halt)
    ]

    p = dut.p
    for i, instr in enumerate(program):
        addr = p.imem_base_rw + i * 0x10
        await dut.writeWord(addr, instr)
        dut.log.info(f"  imem[{i}] @ {addr:#06x} <- {instr:#010x}")

    # Verify a couple of written words read back correctly
    for i, instr in enumerate(program):
        addr = p.imem_base_rw + i * 0x10
        v = await dut.readWord(addr)
        assert v == instr, \
            f"imem[{i}] readback mismatch: expected {instr:#010x}, got {v:#010x}"
    dut.log.info("  imem readback OK")

    # -----------------------------------------------------------------------
    # 3. Set entry address to 0 and latch it
    # -----------------------------------------------------------------------
    dut.log.info("=== 3. Set entry address ===")
    await dut.writeWord(p.entry_addr_rw, 0x00000000)
    v = await dut.readWord(p.entry_addr_rw)
    assert v == 0, f"entry_addr_rw readback: {v:#010x}"

    # Latch the entry address into the PC
    await dut.writeWord(p.entry_addr_we_rw, 1)
    dut.log.info("  entry_addr_we pulsed")

    # -----------------------------------------------------------------------
    # 4. Enable the CPU and wait for ecall halt
    # -----------------------------------------------------------------------
    dut.log.info("=== 4. Enable CPU and wait for ecall ===")
    await dut.writeWord(p.enable_rw, 1)

    en = await dut.readWord(p.enable_rw)
    dut.log.info(f"  enable_rw = {en:#010x}")
    assert en == 1, f"enable_rw should be 1, got {en:#010x}"

    # Poll until ecall (and halted) bits are set
    st = await poll_status(dut, ST_ECALL | ST_HALTED)
    dut.log.info(f"  status_r  = {st:#010x}  (halted via ecall)")

    assert (st & ST_ECALL)   != 0, f"ecall bit not set: {st:#010x}"
    assert (st & ST_HALTED)  != 0, f"halted bit not set: {st:#010x}"
    assert (st & ST_ILLEGAL) == 0, \
        f"illegalInst bit unexpectedly set: {st:#010x}"
    assert (st & ST_RUNNING) == 0, \
        f"running bit should be clear after halt: {st:#010x}"

    # -----------------------------------------------------------------------
    # 5. Inspect debug registers and PC
    # -----------------------------------------------------------------------
    dut.log.info("=== 5. Inspect debug state ===")

    pc = await dut.readWord(p.debug_pc_r)
    dut.log.info(f"  debug_pc_r = {pc:#010x}")
    # PC should point at the ecall instruction (word index 4 = byte addr 16)
    assert pc == 0x10, f"Expected PC=0x10 at ecall, got {pc:#010x}"

    # x0 is always 0
    x0 = await dut.readWord(p.debug_regs_base_r + 0 * 0x10)
    assert x0 == 0, f"x0 should be 0, got {x0:#010x}"

    # x1 = 42
    x1 = await dut.readWord(p.debug_regs_base_r + 1 * 0x10)
    dut.log.info(f"  x1 = {x1}")
    assert x1 == 42, f"x1 expected 42, got {x1}"

    # x2 = 7
    x2 = await dut.readWord(p.debug_regs_base_r + 2 * 0x10)
    dut.log.info(f"  x2 = {x2}")
    assert x2 == 7, f"x2 expected 7, got {x2}"

    # x3 = 49
    x3 = await dut.readWord(p.debug_regs_base_r + 3 * 0x10)
    dut.log.info(f"  x3 = {x3}")
    assert x3 == 49, f"x3 expected 49, got {x3}"

    # -----------------------------------------------------------------------
    # 6. Inspect data memory: dmem[0] should contain 49 (result of sw)
    # -----------------------------------------------------------------------
    dut.log.info("=== 6. Inspect data memory ===")
    # dmem is only accessible while CPU is disabled; disable first
    await dut.writeWord(p.enable_rw, 0)

    dmem0 = await dut.readWord(p.dmem_base_rw + 0 * 0x10)
    dut.log.info(f"  dmem[0] = {dmem0}")
    assert dmem0 == 49, f"dmem[0] expected 49, got {dmem0}"

    # -----------------------------------------------------------------------
    # 7. Cycle counter sanity check (should be non-zero)
    # -----------------------------------------------------------------------
    dut.log.info("=== 7. Cycle counter ===")
    cyc_lo = await dut.readWord(p.debug_cycles_lo_r)
    cyc_hi = await dut.readWord(p.debug_cycles_hi_r)
    cycles = (cyc_hi << 32) | cyc_lo
    dut.log.info(f"  cycles = {cycles}")
    assert cycles > 0, "Cycle counter should be non-zero after execution"

    # -----------------------------------------------------------------------
    # 8. Soft reset: confirm state clears
    # -----------------------------------------------------------------------
    dut.log.info("=== 8. Soft reset ===")
    # Ensure CPU is disabled before reset
    await dut.writeWord(p.enable_rw, 0)

    await dut.softReset()
    dut.log.info("  softReset() completed")

    # After reset: enable should be 0, status should show not-running
    en_after = await dut.readWord(p.enable_rw)
    dut.log.info(f"  enable_rw after reset = {en_after:#010x}")
    assert en_after == 0, \
        f"enable_rw should be 0 after soft reset, got {en_after:#010x}"

    st_after = await dut.readWord(p.status_r)
    dut.log.info(f"  status_r  after reset = {st_after:#010x}")
    assert (st_after & ST_RUNNING) == 0, \
        f"CPU should not be running after soft reset, status={st_after:#010x}"
    assert (st_after & ST_ECALL) == 0, \
        f"ecall bit should be clear after soft reset, status={st_after:#010x}"

    # entry_addr should have been cleared
    ea_after = await dut.readWord(p.entry_addr_rw)
    dut.log.info(f"  entry_addr_rw after reset = {ea_after:#010x}")
    assert ea_after == 0, \
        f"entry_addr_rw should be 0 after soft reset, got {ea_after:#010x}"

    dut.log.info("Done!!\n")
