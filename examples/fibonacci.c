#include <stdint.h>
#include <stdio.h>
#ifdef _WIN32
#include <windows.h>
#endif

int32_t rk_fibonacci(int32_t n);
int32_t rk_main(void);

int32_t rk_fibonacci(int32_t n) {
    int32_t l0;
    int32_t l1;
    int32_t l2;
    int32_t l3;
    int32_t r0;
    int32_t r1;
    int32_t r2;
    int32_t r3;
    int32_t r4;
    int32_t r5;
    int32_t r6;
    int32_t r7;
    int32_t r8;
    int32_t r9;
    int32_t r10;
    r0 = n <= 1;
    if (r0 != 0) goto L0;
    goto L1;
L0:
    return n;
    goto L2;
L1:
L2:
    l0 = 0;
    l1 = 1;
    l2 = 2;
L3:
    r1 = l2;
    r2 = r1 <= n;
    if (r2 != 0) goto L4;
    goto L5;
L4:
    r3 = l0;
    r4 = l1;
    r5 = r3 + r4;
    l3 = r5;
    r6 = l1;
    l0 = r6;
    r7 = l3;
    l1 = r7;
    r8 = l2;
    r9 = r8 + 1;
    l2 = r9;
    goto L3;
L5:
    r10 = l1;
    return r10;
}

int32_t rk_main(void) {
    int32_t l0;
    int32_t l1;
    int32_t r0;
    int32_t r1;
    int32_t r2;
    int32_t r3;
    printf("%s\n", "피보나치 수열의 몇번째 항을 구하시겠습니까?");
    scanf("%d", &r0);
    l0 = r0;
    r1 = l0;
    r2 = rk_fibonacci(r1);
    l1 = r2;
    r3 = l1;
    printf("%d\n", r3);
    return 0;
}

int main(void) {
    #ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    #endif
    return rk_main();
}
