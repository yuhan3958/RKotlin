#include <stdint.h>
#include <stdio.h>

int32_t rk_add(int32_t a, int32_t b);
int32_t rk_mul(int32_t a, int32_t b);
int32_t rk_f(int32_t a, int32_t b, int32_t c);
int32_t rk_main(void);

int32_t rk_add(int32_t a, int32_t b) {
    int32_t r0 = a + b;
    return r0;
}

int32_t rk_mul(int32_t a, int32_t b) {
    int32_t r0 = a * 2;
    int32_t r1 = b + 3;
    int32_t r2 = rk_add(r0, r1);
    return r2;
}

int32_t rk_f(int32_t a, int32_t b, int32_t c) {
    int32_t r0 = rk_mul(a, b);
    int32_t r1 = rk_add(b, c);
    int32_t r2 = rk_add(r0, r1);
    return r2;
}

int32_t rk_main(void) {
    int32_t l0;
    int32_t l1;
    l0 = 10;
    int32_t r0 = l0;
    int32_t r1 = r0 + 32;
    l0 = r1;
    int32_t r2 = l0;
    int32_t r3 = l0;
    int32_t r4 = r3 + 1;
    int32_t r5 = l0;
    int32_t r6 = r5 + 2;
    int32_t r7 = rk_f(r2, r4, r6);
    l1 = r7;
    int32_t r8 = l1;
    printf("%d\n", r8);
    return 0;
}

int main(void) {
    return rk_main();
}
