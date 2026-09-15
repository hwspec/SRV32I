// Copyright (c) 2026, UChicago Argonne, LLC.
// License: See LICENSE in the project top-level directory.
// Main author: Kazutomo Yoshii <kazutomo@anl.gov>

package srv32i

import chisel3._
import chisel3.simulator.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

class SRV32ISpec extends AnyFlatSpec with ChiselSim {

  // Test program:
  //   addi x1, x0, 5
  //   addi x2, x0, 10
  //   add  x3, x1, x2
  //   ecall
  val program: Seq[BigInt] = Seq(
    BigInt("00500093", 16), // addi x1, x0, 5
    BigInt("00A00113", 16), // addi x2, x0, 10
    BigInt("002081B3", 16), // add  x3, x1, x2
    BigInt("00000073", 16)  // ecall
  )

  "basictest" should "pass" in {
    simulate(new SRV32I()) { dut =>
      // imem/dmem modeled here as external SyncReadMem-like arrays:
      // 1-cycle read latency (address sampled this edge, data valid next cycle),
      // matching what the core assumes with no handshake.
      val imem = mutable.Map[BigInt, BigInt]()
      program.zipWithIndex.foreach { case (inst, i) => imem(BigInt(i * 4)) = inst }
      val dmem = mutable.Map[BigInt, BigInt]()

      var pendingImemAddr = BigInt(0)
      var pendingDmemAddr = BigInt(0)

      def tick(): Unit = {
        // present data for the address sampled on the previous edge
        dut.io.imem.inst.poke(imem.getOrElse(pendingImemAddr, BigInt(0)).U(32.W))
        dut.io.dmem.rdata.poke(dmem.getOrElse(pendingDmemAddr, BigInt(0)).U(32.W))

        // capture this cycle's request before advancing the clock
        val nextImemAddr = dut.io.imem.addr.peek().litValue
        val nextDmemAddr = dut.io.dmem.addr.peek().litValue
        if (dut.io.dmem.wen.peek().litToBoolean) {
          dmem(nextDmemAddr) = dut.io.dmem.wdata.peek().litValue
        }

        dut.clock.step(1)

        pendingImemAddr = nextImemAddr
        pendingDmemAddr = nextDmemAddr
      }

      // idle
      dut.io.enable.poke(false.B)
      dut.io.softReset.poke(false.B)
      dut.io.entryAddr.poke(0.U)
      dut.io.entryAddr_we.poke(false.B)
      tick()

      // load entry PC (demonstrates the entryAddr path, though it's already 0)
      dut.io.entryAddr.poke(0.U)
      dut.io.entryAddr_we.poke(true.B)
      tick()
      dut.io.entryAddr_we.poke(false.B)

      // start core
      dut.io.enable.poke(true.B)

      var cycles = 0
      while (!dut.io.debugStatus.halted.peek().litToBoolean && cycles < 100) {
        tick()
        cycles += 1
      }

      assert(dut.io.debugStatus.halted.peek().litToBoolean, "core did not halt")
      assert(dut.io.debugStatus.ecall.peek().litToBoolean, "expected halt cause: ecall")
      assert(!dut.io.debugStatus.illegalInst.peek().litToBoolean, "unexpected illegal instruction")

      assert(dut.io.debugRegs(1).peek().litValue == BigInt(5))
      assert(dut.io.debugRegs(2).peek().litValue == BigInt(10))
      assert(dut.io.debugRegs(3).peek().litValue == BigInt(15))
    }
  }
}
