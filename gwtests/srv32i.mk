#
# Repository-specific settings for gwtests/Makefile (SRV32I)
#

HWMODULENAME=AxiSRV32I
BRIDGEBN=srv32i_bridge
TOPNAME=wrapper
SRCDIR = $(abspath ../generated/$(HWMODULENAME))

TARGETS =loop_hex rlwimieq rlwimi    # does not require riscv toolchain
TARGETS+=loop_asm sum_c embeddedasm  # requires riscv toolchain

.DEFAULT_GOAL := loop_hex

SCALA_SRCS := $(shell find ../src -name '*.scala') ../build.sbt
GENCMD = (cd ../ ; sbt "runMain srv32i.AxiSRV32I")
