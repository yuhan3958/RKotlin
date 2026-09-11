#include <stdint.h>
#include <stdio.h>

int32_t rk_main(void);

int32_t rk_main(void) {
    int32_t l0;
    l0 = 40;
    int32_t r0 = l0;
    int32_t r1 = r0 + 2;
    printf("%d\n", r1);
    int32_t r2 = l0;
    int32_t r3 = r2 + 2;
    return r3;
}

int main(void) {
    return rk_main();
}
