/* The shared library's exported C surface, called from plain C through the public header only. */
#include "stonebreak/worldgen.h"
#include "cenda/voxel_world.h"

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main(void) {
    CHECK(sbw_abi_version() == SBW_ABI_VERSION);
    CHECK(sbw_config_size() == (int32_t) sizeof(sbw_gen_config));
    CHECK(sizeof(sbw_gen_config) == 368);
    CHECK(sizeof(sbw_column_profile) == 36864);                 /* ABI 5: + uint8 surface[16][1024] */
    CHECK(offsetof(sbw_column_profile, surface) == 20480);      /* Java writes it there */
    CHECK(offsetof(sbw_column_profile, carve) == 18432);        /* Java writes it there */
    /* carve styles are stored in the profile code (and the terrain model emits them): never renumber */
    CHECK(SBW_CARVE_KARST == 4 && SBW_CARVE_NONE == 5 && SBW_CARVE_SEA_STACKS == 6 && SBW_CARVE_TOWERS == 7);
    CHECK(sizeof(sbw_column_info) == 40);
    sbw_gen_config cfg;
    sbw_config_defaults(&cfg);
    cfg.seed = 12345;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) cfg.block_ids[i] = (uint16_t) i;
    const cv_generator* g = sbw_cv_generator_create(&cfg);
    CHECK(g != NULL);
    CHECK(g->abi == CV_ABI_VERSION);
    CHECK(g->populate_reach == 1);
    uint16_t* ids = (uint16_t*) malloc(sizeof(uint16_t) * CV_CUBE_CELLS);
    /* far above everything: uniform air */
    CHECK(g->generate_cube(g->user, 0, 19, 0, ids, NULL) == 0);
    CHECK(ids[0] == SBW_AIR);
    /* the bedrock floor */
    CHECK(sbw_generate_cube(g, 0, -12, 0, ids, NULL) == 1);
    CHECK(ids[CV_CELL_INDEX(0, 0, 0)] == SBW_BEDROCK);
    int64_t sky[1024], top[1024];
    CHECK(g->column_surface(g->user, 0, 0, sky, top) == 1);
    CHECK(top[0] > -384 && top[0] < 640);
    sbw_column_info info;
    CHECK(sbw_query_column(g, 5, 7, &info) == 0);
    CHECK(info.height > -384 && info.height < 640);
    free(ids);
    sbw_cv_generator_destroy(g);
    /* bad config */
    cfg.min_y = 10;
    cfg.max_y = 0;
    CHECK(sbw_cv_generator_create(&cfg) == NULL);
    CHECK(sbw_last_error()[0] != 0);
    printf("abi ok\n");
    return 0;
}
