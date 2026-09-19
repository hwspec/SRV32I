.section .text
.globl _start
_start:
    addi x1, x0, 0
    addi x2, x0, 0
    addi x3, x0, 100
loop:
    bge  x2, x3, end
    add  x1, x1, x2
    addi x2, x2, 1
    jal  x0, loop
end:
    ecall
