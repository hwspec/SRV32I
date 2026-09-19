# Copyright (c) 2026, UChicago Argonne, LLC.
# License: See LICENSE in the project top-level directory.
#
# rv32i_compile.py -- compile RV32I asm/C into a flat list of 32-bit
# machine-code words. No project-specific dependencies (no cocotb import),
# so this is reusable across testbenches/projects: just `import rv32i_compile`
# and call compile_prog().
#
# --- asm example (loop.s) -- own your _start/ecall -------------------------
#   .section .text
#   .globl _start
#   _start:
#       addi x1, x0, 0
#       addi x2, x0, 0
#       addi x3, x0, 100
#   loop:
#       bge  x2, x3, end
#       add  x1, x1, x2
#       addi x2, x2, 1
#       jal  x0, loop
#   end:
#       ecall
#
#   program = compile_prog("loop.s")
#
# --- C example (sum.c) -- just define main(), crt0 supplies _start/ecall --
#   int main(void) {
#       volatile int *out = (int *)0;
#       int sum = 0;
#       for (int i = 0; i < 100; i++)
#           sum += i;
#       *out = sum;
#       return 0;
#   }
#
#   program = compile_prog("sum.c")

import os
import re
import shutil
import struct
import subprocess
import tempfile

# Tried in order; whichever is on PATH wins. Covers Ubuntu's
# `gcc-riscv64-unknown-elf` apt package, a from-source riscv-gnu-toolchain
# build, and the xPack prebuilt (`riscv-none-elf-gcc`) commonly used on
# Fedora where there's no official bare-metal dnf package.
RISCV_CC_CANDIDATES = [
    "riscv64-unknown-elf-gcc",
    "riscv32-unknown-elf-gcc",
    "riscv-none-elf-gcc",
    "riscv64-elf-gcc",
    "riscv32-linux-gnu-gcc",   # glibc/Linux target; fine with -nostdlib, see PIE note below
    "riscv64-linux-gnu-gcc",
]

# Minimal crt0 for a bare-metal core with no OS/syscalls: C sources just
# define main(), and this stub supplies _start (call main, then ecall halt).
CRT0_ASM = """\
.section .text
.globl _start
_start:
    call main
    ecall
"""


def _guess_lang(text):
    """Heuristic for inline snippets with no lang= given: RV32I asm doesn't
    use braces, C function bodies always do. Good enough for typical
    snippets; pass lang= explicitly if a given input is ambiguous."""
    return "c" if "{" in text or "#include" in text else "asm"


def find_riscv_gcc(cc=None):
    """Locate an RV32I bare-metal gcc on PATH (or validate an explicit one)."""
    if cc:
        if shutil.which(cc) is None:
            raise FileNotFoundError(f"{cc} not found in PATH")
        return cc
    for c in RISCV_CC_CANDIDATES:
        if shutil.which(c):
            return c
    raise FileNotFoundError(
        "No RV32I bare-metal gcc found in PATH. Install one of: "
        + ", ".join(RISCV_CC_CANDIDATES)
        + " -- e.g. `apt install gcc-riscv64-unknown-elf` on Ubuntu, or an "
        "xPack riscv-none-elf-gcc release on Fedora."
    )


def _elf_to_words(objcopy, elf_path):
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        binpath = f.name
    try:
        # Extract only .text -- a whole-binary dump would also pick up any
        # other *allocated* section the linker adds (.note.gnu.build-id,
        # .riscv.attributes, etc.), which land at real addresses and get
        # interleaved into the output.
        subprocess.run(
            [objcopy, "-O", "binary", "--only-section=.text", elf_path, binpath],
            check=True,
        )
        with open(binpath, "rb") as f:
            data = f.read()
    finally:
        os.unlink(binpath)
    if len(data) % 4:
        data += b"\x00" * (4 - len(data) % 4)
    return list(struct.unpack(f"<{len(data) // 4}I", data))


def compile_prog(src, lang=None, cc=None, entry=0x0, march="rv32i", mabi="ilp32"):
    """Compile RV32I asm or C source into a list of 32-bit machine code
    words in program order (word[i] == instruction at PC = i*4).

    src:  path to a .c/.s/.S file, OR raw source text (used as-is if it
          doesn't resolve to an existing file) -- e.g. compile_prog('''
          addi x1, x0, 5
          ecall
          ''').
    lang: 'c' or 'asm'. Auto-detected from the file extension when `src`
          is a path; for raw text, auto-detected by a simple heuristic
          (presence of '{' / '#include' -> C, else asm) unless given
          explicitly -- pass lang= if a snippet is ambiguous.
    cc:   override the compiler binary (default: auto-detect via
          find_riscv_gcc()).
    entry: link address (-Wl,-Ttext=...); match your core's reset/entry PC.
    march/mabi: override if your core supports more than plain rv32i/ilp32
          (e.g. march="rv32im" once your core has mul/div).

    C sources need only define `main()` -- a crt0 stub supplies `_start`
    (call main, then ecall). Asm sources are compiled as-is: you own
    `_start`/entry layout and must end with ecall (or your core's halt)
    yourself.
    """
    is_path = "\n" not in src and os.path.isfile(src)
    if is_path:
        with open(src) as f:
            src_text = f.read()
        if lang is None:
            ext = os.path.splitext(src)[1].lower()
            lang = "c" if ext == ".c" else "asm"
    else:
        src_text = src
        if lang is None:
            lang = _guess_lang(src_text)

    gcc = find_riscv_gcc(cc)
    objcopy = gcc[:-3] + "objcopy" if gcc.endswith("gcc") else gcc.replace("gcc", "objcopy")

    common = [
        gcc, f"-march={march}", f"-mabi={mabi}", "-mno-relax",
        "-nostdlib", "-nostartfiles", "-ffreestanding",
        "-fno-pie", "-no-pie",   # glibc/Linux-targeted gccs often default to PIE,
                                  # which breaks a fixed-address freestanding blob
        "-Wl,--build-id=none",   # skip generating a build-id note section entirely
        f"-Wl,-Ttext={entry:#x}",
    ]

    with tempfile.TemporaryDirectory() as td:
        elf = os.path.join(td, "out.elf")
        if lang == "c":
            cfile = os.path.join(td, "main.c")
            crtfile = os.path.join(td, "crt0.s")
            with open(cfile, "w") as f:
                f.write(src_text)
            with open(crtfile, "w") as f:
                f.write(CRT0_ASM)
            cmd = common + [crtfile, cfile, "-o", elf]
        elif lang == "asm":
            asmfile = os.path.join(td, "prog.s")
            with open(asmfile, "w") as f:
                f.write(src_text)
            cmd = common + [asmfile, "-o", elf]
        else:
            raise ValueError(f"lang must be 'c' or 'asm', got {lang!r}")

        subprocess.run(cmd, check=True)
        return _elf_to_words(objcopy, elf)


def _main():
    import argparse

    ap = argparse.ArgumentParser(
        description="Compile RV32I asm/C to a machine-code word list (standalone test/CLI).",
        epilog="""examples:
  asm (loop.s -- own your _start/ecall):
    .section .text
    .globl _start
    _start:
        addi x1, x0, 0
        ...
        ecall
    $ rv32i_compile.py loop.s --lang asm

  C (sum.c -- just define main(), crt0 supplies _start/ecall):
    int main(void) { ...; return 0; }
    $ rv32i_compile.py sum.c
""",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("src", help="asm/C source file, or raw source text")
    ap.add_argument("--lang", choices=["c", "asm"], help="override auto-detected language")
    ap.add_argument("--cc", help="override compiler binary")
    ap.add_argument("--entry", type=lambda s: int(s, 0), default=0x0, help="link address")
    ap.add_argument("--march", default="rv32i")
    ap.add_argument("--mabi", default="ilp32")
    args = ap.parse_args()

    words = compile_prog(
        args.src, lang=args.lang, cc=args.cc,
        entry=args.entry, march=args.march, mabi=args.mabi,
    )

    print(f"# {len(words)} words, entry={args.entry:#x}")
    print("program = [")
    for i, w in enumerate(words):
        print(f"    {w:#010x},   # [{i:3d}] pc={args.entry + i * 4:#06x}")
    print("]")


if __name__ == "__main__":
    _main()
