int main(void) {
    volatile int *out = (int *)0;
    int sum = 0;
    for (int i = 0; i < 100; i++)
        sum += i;
    *out = sum;
    return 0;
}
