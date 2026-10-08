#
# This is Makefile for cocotb and fpga
#

HWMODULENAME=AxiSRV32I
BRIDGEBN=srv32i_bridge
TOPNAME=wrapper
SRCDIR = $(abspath ../generated/$(HWMODULENAME))

TARGETS =loop_hex rlwimieq rlwimi    # does not require riscv toolchain
TARGETS+=loop_asm sum_c embeddedasm  # requires riscv toolchain

.DEFAULT_GOAL := loop_hex

WAVES ?= 0
ifeq ($(WAVES),1)
EXTRA_ARGS += --trace --trace-structs
endif

SIM ?= verilator
TOPLEVEL_LANG ?= verilog
VERILOG_SOURCES += $(wildcard $(SRCDIR)/*.v $(SRCDIR)/*.sv)
SCALA_SRCS := $(shell find ../src -name '*.scala') ../build.sbt

.PHONY: $(TARGETS) list gen sim-all hw-all


$(TARGETS) : $(SRCDIR)/$(HWMODULENAME).sv
	@echo $(VERILOG_SOURCES)
	@PARAMFN=$(SRCDIR)/params.json $(MAKE) sim MODULE=tb_$@ TOPLEVEL=$(TOPNAME)

$(SRCDIR)/$(HWMODULENAME).sv: $(SCALA_SRCS)
	(cd ../ ; sbt "runMain srv32i.AxiSRV32I")

gen:
	(cd ../ ; sbt "runMain srv32i.AxiSRV32I")

sim-all: $(TARGETS)
hw-all: $(addprefix hw_,$(TARGETS))

fpga_tb_%.py: tb_%.py $(BRIDGEBN).py
	@conv_cocotb_to_fpga $^ --force

.PRECIOUS: fpga_tb_%.py          # keep converted testbenches between runs

hw_%: fpga_tb_%.py
	@rm -f output.log
	@PARAMFN=$(SRCDIR)/params.json python fpga_tb_$*.py
	@printf '\n[Output]\n'
	@cat output.log

list:
	@for t in $(TARGETS); do printf '%-14s hw_%s\n' $$t $$t; done

NON_SIM_GOALS := hw_% fpga_tb_%.py list
ifneq ($(if $(MAKECMDGOALS),$(filter-out $(NON_SIM_GOALS),$(MAKECMDGOALS)),sim),)
include $(shell cocotb-config --makefiles)/Makefile.sim
endif

clean::
	@rm -rf __pycache__
	@rm -f output.txt results.xml *.vcd
	@rm -f fpga_tb_*.py
	@rm -f fpga_$(BRIDGEBN).py
