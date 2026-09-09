# Copyright (c) 2026, UChicago Argonne, LLC.
# License: See LICENSE in the project top-level directory.

import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

# ---------------------------------------------------------------------------
# Status register bit masks (status_r)
# ---------------------------------------------------------------------------
ST_RUNNING  = 1 << 0
ST_HALTED   = 1 << 1
ST_ILLEGAL  = 1 << 2
ST_ECALL    = 1 << 3

ECALL = 0x00000073   # halt instruction


class SRV32I_Bridge(COCOTB_Bridge):
    def __init__(self, cocotb_dut):
        super().__init__(cocotb_dut)

    # -- instruction memory --------------------------------------------
    async def writeInst(self, idx, data):
        addr = self.p.imem_base_rw + idx * 0x10
        await self.writeWord(addr, data)

    async def readInst(self, idx):
        addr = self.p.imem_base_rw + idx * 0x10
        return await self.readWord(addr)

    async def loadProg(self, insts, verify=True):
        # insts: list of hex-encoded instruction words
        for i, instr in enumerate(insts):
            await self.writeInst(i, instr)
        if verify:
            for i, instr in enumerate(insts):
                v = await self.readInst(i)
                assert v == instr, \
                    f"imem[{i}] mismatch: expected {instr:#010x} got {v:#010x}"

    # -- data memory (only accessible while CPU disabled) ---------------
    async def writeData(self, addr, val):
        await self.writeWord(self.p.dmem_base_rw + addr, val)

    async def readData(self, addr):
        return await self.readWord(self.p.dmem_base_rw + addr)

    # -- control / status -------------------------------------------------
    async def enable(self, en=1):
        await self.writeWord(self.p.enable_rw, en)

    async def setEntry(self, addr=0):
        await self.writeWord(self.p.entry_addr_rw, addr)
        await self.writeWord(self.p.entry_addr_we_rw, 1)

    async def readStatus(self):
        return await self.readWord(self.p.status_r)

    async def waitForHalt(self, timeout=50000):
        st = 0
        for _ in range(timeout):
            st = await self.readStatus()
            if st & ST_HALTED:
                return st
        raise TimeoutError(
            f"Timed out waiting for halt; last status={st:#010x}"
        )

    # -- debug -------------------------------------------------------------
    async def readReg(self, idx):
        return await self.readWord(self.p.debug_regs_base_r + idx * 0x10)

    async def readPC(self):
        return await self.readWord(self.p.debug_pc_r)

    async def readCycles(self):
        lo = await self.readWord(self.p.debug_cycles_lo_r)
        hi = await self.readWord(self.p.debug_cycles_hi_r)
        return (hi << 32) | lo

    # -- run helper (mirrors R2_Bridge.run) --------------------------------
    async def run(self, prog=None, entry=0, timeout=50000):
        await self.enable(0)
        if prog:
            await self.loadProg(prog)
        await self.setEntry(entry)
        await self.enable(1)
        st = await self.waitForHalt(timeout)
        await self.enable(0)   # disable so dmem/imem are readable again
        return st
