/* libstonebreak_terrain C ABI smoke test (no GPU needed): handshake, clean failures on bad arguments. The stages'
 * numeric parity against the Python generator is Models/DaedalusTGM-Exp/tests/test_cenda_engine.py (test_phase2_*). */
#include "stonebreak/terrain.h"

#include <stdio.h>

int main(void) {
    int fail = 0;
    if (sbtg_abi_version() != SBTG_ABI_VERSION) { puts("abi mismatch"); fail = 1; }
    if (sbtg_create(NULL, NULL) != NULL) { puts("create(NULL) succeeded"); fail = 1; }
    if (sbtg_create("/nonexistent/libcenda_kernels.so", (void*)1) != NULL) { puts("create(bad lib) succeeded"); fail = 1; }
    if (sbtg_last_error()[0] == '\0') { puts("no error message"); fail = 1; }
    if (sbtg_planner_configure(NULL, NULL) != SBTG_ERR_ARG) { puts("configure(NULL)"); fail = 1; }
    double v[6];
    if (sbtg_stats(NULL, v, 6) != 0) { puts("stats(NULL)"); fail = 1; }
    if (!fail) puts("terrain_abi: OK");
    return fail;
}
