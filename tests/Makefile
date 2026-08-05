#
# This is Makefile for cocotb
#

SIM ?= verilator
TOPLEVEL_LANG ?= verilog
TOPNAME=user_accel_bd_wrapper
HWMODULENAME=AXI_SRV32I
SRCDIR = $(abspath ../generated/$(HWMODULENAME))
VERILOG_SOURCES += $(SRCDIR)/*.v $(SRCDIR)/*.sv

WAVES = 1
EXTRA_ARGS += --trace --trace-structs

test : $(SRCDIR)/$(HWMODULENAME).sv
	@echo $(VERILOG_SOURCES)
	@PARAMFN=$(SRCDIR)/SRV32I_params.json $(MAKE) sim MODULE=tb_srv32i TOPLEVEL=$(TOPNAME)
	@echo -e "\n[Output]"
	@cat output.log

$(SRCDIR)/$(HWMODULENAME).sv gen:
	(cd ../ ; sbt "runMain srv32i.AXI_SRV32I")

include $(shell cocotb-config --makefiles)/Makefile.sim

clean::
	rm -f output.txt results.xml *.vcd fpga_simple.py
	rm -rf __pycache__
