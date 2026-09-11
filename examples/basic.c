#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#ifdef _WIN32
#include <windows.h>
#endif

struct rk_Point {
    int32_t x;
    int32_t y;
};

int32_t rk_Point_sum(struct rk_Point* this);
int32_t rk_main(void);

int32_t rk_Point_sum(struct rk_Point* this) {
    int32_t r0;
    int32_t r1;
    int32_t r2;
    r0 = this->x;
    r1 = this->y;
    r2 = r0 + r1;
    return r2;
}

int32_t rk_main(void) {
    struct rk_Point* l0;
    struct rk_Point* r0;
    struct rk_Point* r1;
    struct rk_Point* r2;
    struct rk_Point* r3;
    int32_t r4;
    struct rk_Point* r5;
    int32_t r6;
    r0 = calloc(1, sizeof(struct rk_Point));
    l0 = r0;
    r1 = l0;
    r1->x = 20;
    r2 = l0;
    r2->y = 22;
    printf("%s\n", "point sum:");
    r3 = l0;
    r4 = rk_Point_sum(r3);
    printf("%d\n", r4);
    r5 = l0;
    r6 = rk_Point_sum(r5);
    return r6;
}

int main(void) {
    #ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    #endif
    return rk_main();
}
