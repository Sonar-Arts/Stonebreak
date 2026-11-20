/* Stonebreak worldgen natives - C ABI (SBW) consumed by the Open Mason cubic voxel runtime
 * (cenda/voxel_world.h, cv_generator) and by the game via FFM
 * (com.stonebreak.world.generation.NativeCubeGenerator). Issue #446 (OpenMason#89 G5).
 *
 * SBW ABI 3 replaces the column ABI (ABI 1/2: sbw_terrain_create / sbw_carve_worms /
 * sbw_chunkgen_create / sbw_generate_chunk, a fixed 16x256x16 frame) with the WHOLE Stonebreak
 * generator behind the engine's cv_generator: terrain shape, biomes, carvers (worms, caverns,
 * megacaverns, ravines, sinkholes, Density3D cheese/spaghetti/crag, deep cave layers), surface
 * rules, ores, limestone + stalagmites, swamp pools, trees, vegetation, cactus and surface
 * decorations, for 32^3 cubes in a vertically bounded world (default Y [-384, 640), sea 64).
 *
 * The library never links libcenda_kernels: it includes voxel_world.h for the types only and writes
 * population through the cv_gen_api function table the engine hands it. Noise is the engine's pinned
 * FastNoise2 (cmake/CendaFastNoise2.cmake) built through cenda/noise_nodes.hpp.
 *
 * ---------------------------------------------------------------------------------------------
 * GENERATION MODEL
 *  - COLUMN PROFILE per column cube (cx, cz) (32 x 32 columns), cached in bounded thread-safe
 *    caches: raw height, water level, biome, river void (profile kind), carve masks (per 32^3 slice),
 *    carved surface, top block, swamp pools. Pure functions of (seed, cx, cz).
 *  - generate_cube(cx, cy, cz) is a PURE function of (seed, cx, cy, cz): terrain fill + every carver
 *    + Density3D + deep cave layers + ores + crystals + limestone/stalagmites + swamp pools. Cubes
 *    wholly above max(surface, water) of their 32x32 columns return 0 (uniform air) without noise.
 *    Carvers are deterministic fields evaluated per column footprint, never from neighbour state, so
 *    a worm crossing a cube border is identical on both sides.
 *  - MEMO LAYERS (all bounded, sharded, thread-safe, pure; results never depend on what is cached):
 *    worm-system and ravine walks per carving layer and 16-block source chunk (walked once, replayed
 *    by every footprint whose scan window holds them; a missing walk is built by the first thread
 *    that needs it while the others build other walks of the window and only then wait), the top
 *    carve, carved surface, pools and features per column cube, each deep cave layer as its own tile
 *    (layer i only carves below h - 100 * (i + 1): a cube above that never builds it), ore sources
 *    per 16-block chunk, limestone column fields, and the raw terrain of recently generated cubes
 *    (a neighbour's stalagmite / limestone shell reads it instead of re-deciding the cells).
 *    cache_columns scales every one of them. Cube fills decide cells in cache-sized batches and
 *    sample each Density3D channel only for the cells whose decision reads it.
 *  - populate(cx, cy, cz) places the SURFACE features (trees, flowers, cactus, snow/ice, stony-peak
 *    and badlands surface swaps, red-sand clay patches) whose ANCHOR lies in the origin region
 *        x, z in [32c + 16, 32c + 48),   y in [32cy + 2, 32cy + 34)
 *    (anchor = the column's surface y; every feature reaches at most 7 blocks sideways, 2 down and
 *    30 up, so it always fits inside cubes c..c+1). ORDER-INDEPENDENCE RULE: every populate call
 *    recomputes (deterministically, from column profiles only - never from world reads) every tree
 *    anchored in the 2x2 column footprint [32c, 32c + 64)^2, which contains every tree that can
 *    overlap a cell it writes. A cell covered by several trees goes to the highest RANK:
 *        rank = (block is a log ? 1 : 0, per-tree priority = splitmix64(seed, anchor x, y, z));
 *    within one tree its own placement order holds (later writes win, unanchored leaves pruned).
 *    A populate call writes a tree cell only when its own tree holds that rank, so each cell is
 *    written by exactly one call. Non-tree features never write a cell any tree covers; clay patches
 *    beat badlands surface bands (bands skip cells any clay patch covers). Feature predicates read
 *    the column profiles (raw surface block, carved height, pools), never the world, so the result
 *    is identical in any populate order, on any thread, and populating twice changes nothing.
 *  - Deep range: below the old 0..256 band the column is stone on a bedrock floor (y = min_y, plus a
 *    hashed bedrock band up to min_y + 4), with Density3D caves at every depth, the top-layer carvers
 *    free to walk down to min_y + 6, and SBW_DEEP_LAYERS repetitions of the worm/cavern/megacavern
 *    carvers under their own seeds, each 100 blocks deeper (DeepCaveLayers); ores and limestone
 *    pockets get a second, deep spawn stream in [min_y + 6, 1).
 *
 * LAYERS (cv_layer_*, indices chosen in the config; -1 = not written):
 *  - water_layer: every generated WATER cell gets water_source_value (default 16 = the cv_fluid
 *    encoding "source, level 0": level | falling << 3 | source << 4). The top water cell of a column
 *    a river runs through (profile kind, flow octant 0..7) gets water_river_values[octant] instead
 *    when that is non-zero (the game's ChunkWaterLayer RIVER marker, mapped by the game).
 *  - state_layer: LIMESTONE_STALAGMITE cells - the anchor gets stalagmite_states[((size - 1) * 2 +
 *    hanging) * 4 + facing] (facing = StairState.Facing ordinal: 0 SOUTH, 1 EAST, 2 NORTH, 3 WEST), the
 *    part cells stalagmite_part_states[0] (upright, StalagmiteUpper) / [1] (hanging, StalagmiteLower).
 *  - snow_layer: SNOW cells get their layer count (1..8, SnowLayerManager).
 *  Population writes reset these three layers to 0 on every cell it overwrites (a log replacing a
 *  pool's water cell drops the water value).
 *
 * COLUMN SURFACE (cv_generator.column_surface): out_top = 1 + the topmost non-air cell the generator
 * places (carved terrain, water, river voids, and population features including tree canopies);
 * out_sky = 1 + the topmost block whose block_flags entry has CV_BLOCK_SKY_OPAQUE (0x0008). Exact
 * against generated + populated cubes (tested), computed from the column profiles of the 3x3 column
 * neighbourhood.
 *
 * Noise is float (FastNoise2): positions are valid to about +-2^23 blocks.
 * --------------------------------------------------------------------------------------------- */
#pragma once

#include <stdint.h>

#if defined(_WIN32)
#  if defined(STONEBREAK_WORLDGEN_BUILD)
#    define SBW_API __declspec(dllexport)
#  else
#    define SBW_API __declspec(dllimport)
#  endif
#else
#  define SBW_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* 1 (#386): split out of the engine's CK ABI 12; 2: distilled Standard shape (column ABI);
 * 3 (#446): cube generator behind cv_generator, column ABI removed. */
#define SBW_ABI_VERSION 5   /* 5 (2026-11-19): sbw_column_profile.surface (surface channels) */

/* Standard shape versions (sbw_gen_config.standard_shape), fixed per world like the seed (a saved world keeps its own):
 *  1 = the 2026-11-01 DaedalusTGM-Exp v4 distillation (height curve v1, no surface features);
 *  2 = the 2026-11-19 DaedalusTGM-Exp-2 distillation: refitted shape + landform provinces, dendritic tributaries and
 *      the open ocean's shelf / abyss; the height-curve v2 sea bands (shallows / shelf / deep); inland sub-sea pits
 *      are lakes (land biome, lake bed); distilled surface channels (vegetation, rock exposure, scree, roughness) so
 *      SBW_FLAG_SEA_FLOOR / LAND_SURFACE / BOULDERS / VEGETATION work on Standard worlds. */
#define SBW_STANDARD_SHAPE_V1 1
#define SBW_STANDARD_SHAPE_V2 2

/* Water level "no standing water" (water levels are world y and may be negative). */
#define SBW_NO_WATER INT32_MIN

/* Generator kinds. */
#define SBW_KIND_STANDARD 0  /* distilled noise terrain, all native */
#define SBW_KIND_PROFILE  1  /* heights / water / biomes / rivers from column_fn (diffusion / TGM) */

/* Config flags. */
#define SBW_FLAG_PEAK_DAMP   0x1u /* Density3D overhang band tapers with altitude (Standard) */
#define SBW_FLAG_DEEP_LAYERS 0x2u /* deep cave layers below the top carvers */
#define SBW_FLAG_FEATURES    0x4u /* populate places surface features (else populate is a no-op) */
#define SBW_FLAG_SEA_FLOOR   0x10u /* profile kind: the ocean floor's surface block follows depth, slope and shelter
                                       (sand shallows, gravel on swept / steep floors, clay in deep or sheltered water,
                                       rock on cliffed coasts) instead of ~97 % sand; off by default (2026-11-19) */
#define SBW_FLAG_PROFILE_CARVE 0x8u /* profile kind: ravines and sinkholes follow the profile's carve codes
                                      (sbw_column_profile.carve) instead of the hash; off by default */
#define SBW_FLAG_BOULDERS    0x20u /* profile kind: population places boulders - pebbles to glacial erratics, boulder fields,
                                       scree aprons (src/boulders.cpp); shapes procedural + the rock bank named by
                                       sbw_gen_config.rock_bank. Off by default; per world (a saved world keeps its set,
                                       2026-11-19) */

#define SBW_FLAG_VEGETATION  0x80u /* profile kind: trees (density, size, clearings) and ground vegetation (bushes, grass,
                                       flowers, bare / wet-meadow patches) follow the vegetation channels (SBW_SURF_CANOPY_*
                                       / CLEARING / GROUND_*; agent V). Columns without channel data (255) keep the biome
                                       rules, so it is a no-op for tiles without the node; off by default (2026-11-19) */
#define SBW_FLAG_VEG_OPEN_LAND 0x100u /* with SBW_FLAG_VEGETATION: open the land up (big meadow patches by biome on wooded land,
                                       a steeper cover-to-density curve) so temperate land is not ~90 % closed forest; per world
                                       (WorldFeatureVersion V4, 2026-11-20); without it the V3 rules exactly */
#define SBW_FLAG_LAND_SURFACE 0x40u /* profile kind: dry land's surface block follows the surface-detail node's channels
                                       (SBW_SURF_ROCK_EXPOSURE / SCREE / ROUGHNESS, 1 m lidar-trained; agent L): bare rock,
                                       talus gravel / cobble, broken ground. Columns without channel data (255) keep their
                                       surface, so it is a no-op for tiles without the node; off by default (2026-11-19) */

#define SBW_DEEP_LAYERS 4         /* layers 100, 200, 300, 400 blocks under the top carvers */

/* Block roles: config.block_ids[role] = the game's id (BlockType.getId()). */
enum sbw_block {
    SBW_AIR = 0, SBW_WATER, SBW_STONE, SBW_BEDROCK, SBW_MAGMA,
    SBW_DIRT, SBW_GRASS, SBW_SAND, SBW_RED_SAND, SBW_SANDSTONE, SBW_RED_SANDSTONE,
    SBW_GRAVEL, SBW_CLAY, SBW_SNOWY_DIRT, SBW_SWAMPY_GRASS, SBW_ICE, SBW_SNOW,
    SBW_COAL_ORE, SBW_IRON_ORE, SBW_CRYSTAL, SBW_LIMESTONE, SBW_LIMESTONE_STALAGMITE,
    SBW_WOOD, SBW_LEAVES, SBW_ELM_WOOD_LOG, SBW_ELM_LEAVES, SBW_PINE, SBW_PINE_LEAVES,
    SBW_CYPRESS_LOG, SBW_CYPRESS_LEAVES, SBW_ROSE, SBW_WILDGRASS, SBW_DANDELION, SBW_CACTUS,
    SBW_RED_SAND_COBBLESTONE,
    /* 2026-11-19 (boulders; id 0 = not supplied -> stone / the sandstone body) */
    SBW_COBBLESTONE, SBW_SAND_COBBLESTONE,
    SBW_BLOCK_COUNT
};
#define SBW_BLOCK_CAP 64

/* Biomes = com.stonebreak.world.generation.biomes.BiomeType ordinals. */
enum sbw_biome {
    SBW_PLAINS = 0, SBW_DESERT, SBW_RED_SAND_DESERT, SBW_SNOWY_PLAINS, SBW_TUNDRA, SBW_TAIGA,
    SBW_STONY_PEAKS, SBW_BEACH, SBW_ICE_FIELDS, SBW_BADLANDS, SBW_MEADOW, SBW_OCEAN, SBW_SWAMP,
    SBW_BIOME_COUNT
};

/* Tree kinds (VegetationGenerator.TreeKind ordinals). */
enum sbw_tree { SBW_TREE_OAK = 0, SBW_TREE_ELM, SBW_TREE_PINE, SBW_TREE_CYPRESS };

/* Column profile for SBW_KIND_PROFILE, filled by column_fn for column cube (cx, cz); arrays are
 * [lz * 32 + lx]. No padding. height = first y above the ground; water = first y that
 * is not water (SBW_NO_WATER when dry); river_floor/river_roof = a river void under standing ground
 * (floor and roof are stone, water below `water`), roof <= floor for none; river_flow = flow octant
 * 0..7 of the column's water, -1 none; biome = BiomeType ordinal. carve = the terrain model's carve-node code
 * (SBW ABI 4; used with SBW_FLAG_PROFILE_CARVE): bits 0-3 ravine density 0..15 (0 = none), 4-6 style
 * (SBW_CARVE_*), 7-10 strike: the incision's axis theta in 16 steps over [0, pi), measured from +z toward +x
 * (a ravine's yaw from +x toward +z is pi/2 - theta), 11-15 depth class 0..31 (32 m per step). surface = the
 * surface channels (ABI 5, below). 36864 bytes. */
enum sbw_carve_style { SBW_CARVE_RAVINE = 0, SBW_CARVE_SLOT, SBW_CARVE_GORGE, SBW_CARVE_RIFT, SBW_CARVE_KARST,
                       SBW_CARVE_NONE,
                       /* a cliffed coast (land within ~300 m of a steep shore): the cliff retreats into a wave-cut
                        * platform with stacks left standing, a wave notch along its foot, sea caves and arches; where
                        * the cut connects to the open sea it is a rock shelf 1-3 blocks under sea level, flooded with
                        * sea water (notches, caves and arches too), else a dry platform at sea level; density = cliff
                        * exposure (0 = none). No ravines. */
                       SBW_CARVE_SEA_STACKS = 6,
                       /* humid tower karst: vertical-walled corridors and pits between rock towers. No ravines. */
                       SBW_CARVE_TOWERS = 7 };
typedef struct sbw_column_profile {
    int32_t height[1024];
    int32_t water[1024];
    int32_t river_floor[1024];
    int32_t river_roof[1024];
    uint8_t biome[1024];
    int8_t river_flow[1024];
    uint16_t carve[1024];
    uint8_t surface[16][1024];   /* SBW ABI 5: surface channels by SBW_SURF_* slot, see below */
} sbw_column_profile;            /* 36864 bytes */

/* SURFACE CHANNELS (SBW ABI 5, 2026-11-19; contract: Models/DaedalusTGM-Exp/terrain_slm/world/surface_channels.py,
 * Java diffusion/SurfaceChannel): per-column bytes from the terrain model's nodes, physical = offset + byte * scale
 * with the scale/offset fixed per channel below; SBW_SURF_NO_DATA (255) = no data (sea / lakes / rivers, models or
 * tiles without the producing node, Standard worlds): every consumer must then do exactly what it did before the
 * channel existed. The generator sets every slot to 255 before calling column_fn, so a column_fn that never writes a
 * slot changes nothing. Slots are fixed forever once shipped; 12..15 are free. */
#define SBW_SURFACE_SLOTS 16
#define SBW_SURF_NO_DATA 255
enum sbw_surface_slot {
    SBW_SURF_CANOPY_COVER = 0,   /* share of ground under tree canopy >= 5 m, byte / 254 */
    SBW_SURF_CANOPY_HEIGHT = 1,  /* mean canopy tree height, byte * 0.25 m */
    SBW_SURF_CLEARING = 2,       /* share of the ~300 m neighbourhood in open clearings, byte / 254 */
    SBW_SURF_GROUND_GRASS = 3,   /* ground cover shares (of the ground), byte / 254 */
    SBW_SURF_GROUND_SHRUB = 4,
    SBW_SURF_GROUND_MOSS = 5,
    SBW_SURF_GROUND_BARE = 6,
    SBW_SURF_GROUND_WETLAND = 7,
    SBW_SURF_ROCK_EXPOSURE = 8,  /* land surface materials / boulders (agent L), byte / 254 */
    SBW_SURF_SCREE = 9,
    SBW_SURF_BOULDER_DENSITY = 10,
    SBW_SURF_BOULDER_SIZE = 11,  /* byte * 0.05 m */
    SBW_SURF_ROUGHNESS = 12      /* RMS of the sub-10 m relief inside the block, byte * 0.01 m (agent L) */
};

/* Upcall: fill *out for column cube (cx, cz). May run on any generator thread, concurrently, and
 * may block; must not call back into this generator. Return 1 = filled, anything else = failure
 * (the cube or query asking for it fails with a negative result). */
typedef int32_t (*sbw_column_fn)(void* user, int64_t cx, int64_t cz, sbw_column_profile* out);

/* Generator config (368 bytes; offsets fixed, see sbw_config_size). Fill with sbw_config_defaults
 * first, then override. Ids come from the game's runtime block registry (SBO), never constants. */
typedef struct sbw_gen_config {
    uint32_t struct_size;            /*   0: sizeof(sbw_gen_config) */
    int32_t kind;                    /*   4: SBW_KIND_* */
    int64_t seed;                    /*   8 */
    int64_t min_y;                   /*  16: world floor (bedrock), default -384 */
    int64_t max_y;                   /*  24: exclusive top, default 640 */
    int32_t sea_level;               /*  32: default 64 */
    uint32_t flags;                  /*  36: SBW_FLAG_*, default all */
    double horizontal_m_per_block;   /*  40: terrain scale; 60 = 1:4 (the fit), 30 = 1:2 */
    double curve_rates[4];           /*  48: m/block ocean, lowland, midland, highland */
    double curve_knots[5];           /*  80: lowland top, highland base, shore/midland/highland blend */
    const uint16_t* block_flags;     /* 120: cv block flags per game id (CV_BLOCK_*) for out_sky */
    int32_t block_flags_count;       /* 128 */
    int32_t water_layer;             /* 132: -1 none */
    int32_t state_layer;             /* 136: -1 none */
    int32_t snow_layer;              /* 140: -1 none */
    sbw_column_fn column_fn;         /* 144: SBW_KIND_PROFILE */
    void* column_user;               /* 152 */
    int32_t cache_columns;           /* 160: column profiles kept (0 = default 4096) */
    uint16_t water_source_value;     /* 164: default 16 */
    uint16_t surface_prefetch;       /* 166: column_surface prefetch helper threads (0 = auto: hardware
                                        threads / 4, 1..8; SBW_PREFETCH_OFF = none), see below */
    uint16_t water_river_values[8];  /* 168: 0 = plain source value */
    uint16_t stalagmite_states[24];  /* 184 */
    uint16_t stalagmite_part_states[2]; /* 232 */
    uint16_t rock_bank;              /* 236: SBW_FLAG_BOULDERS: id of a registered SBRB rock shape bank
                                        (sbw_register_rock_bank), 0 = procedural shapes only (was reserved, 0) */
    uint16_t standard_shape;         /* 238: SBW_KIND_STANDARD: the world's Standard shape version (SBW_STANDARD_SHAPE_*;
                                        0 = 1, the 2026-11-01 v4 distillation; was reserved, 0). Added within ABI 5
                                        (2026-11-19): callers check sbw_standard_shape_max() first */
    uint16_t block_ids[SBW_BLOCK_CAP]; /* 240: indexed by sbw_block */
} sbw_gen_config;                    /* 368 */

/* sbw_gen_config.surface_prefetch: no helper threads. COLUMN SURFACE PREFETCH: a column_surface call that
 * misses the generator's caches also queues the column cubes within 2 of it to a few helper threads
 * (started on first use, joined by sbw_cv_generator_destroy), which build their surfaces - and so the
 * carve tiles, worm walks and column profiles those need - while the caller computes its own. A host that
 * asks for the surfaces of an area one column at a time (the streamer's planner does, on one thread) then
 * finds most of them ready. Pure caching: results are identical with and without it. The profile kind's
 * column_fn is then also called on these threads. */
#define SBW_PREFETCH_OFF 0xFFFF

/* Per-column query result (sbw_query_column). */
typedef struct sbw_column_info {
    int32_t height;          /* raw height (first y above ground) */
    int32_t carved_height;   /* one past the highest solid block after carving */
    int32_t water_level;     /* SBW_NO_WATER when dry; a swamp pool reports its water, a sea-flooded cut sea level */
    int32_t biome;
    int32_t surface_block;   /* block the column exposes at carved_height - 1 (game id) */
    int32_t pool_depth;      /* swamp pool: 0, 1 or 2 */
    int32_t tree;            /* 0 none, else (kind + 1) | trunk_height << 8 */
    int32_t top;             /* column_surface TOP at this column */
    float temperature;       /* Standard only (0 for profile kind) */
    float moisture;
} sbw_column_info;           /* 40 bytes */

/* sbw_sample_columns flags */
#define SBW_SAMPLE_CARVED 1u /* heights/surfaces after carving (else raw, no carve profile built) */

typedef struct cv_generator cv_generator;
typedef struct cv_gen_api cv_gen_api;
typedef struct cv_world cv_world;

SBW_API int32_t sbw_abi_version(void);
SBW_API int32_t sbw_config_size(void);
/* Highest sbw_gen_config.standard_shape this library generates (SBW_STANDARD_SHAPE_V2). Added within ABI 5
 * (2026-11-19): optional, a library without it knows only shape 1 (and ignores the field). */
SBW_API int32_t sbw_standard_shape_max(void);
/* Thread-local message for the last failure on this thread. */
SBW_API const char* sbw_last_error(void);
/* Defaults: Standard kind, seed 0, Y [-384, 640), sea 64, flags all, 1:4 scale (60 m, rates
 * {48, 16, 24, 38}, knots {600, 2000, 60, 150, 300}), layers -1, water source 16, ids 0, caches
 * 4096 columns, surface prefetch auto. */
SBW_API void sbw_config_defaults(sbw_gen_config* config);
/* NULL + sbw_last_error on bad config. The returned table is owned by the library. */
SBW_API const cv_generator* sbw_cv_generator_create(const sbw_gen_config* config);
/* Refuses new sbw_* calls on the handle, waits for the sbw_* calls in flight on it, then frees it. A destroyed (or
 * foreign) handle passed to any sbw_* entry point returns -1 with "generator destroyed" in sbw_last_error instead of
 * touching freed memory; a second destroy is a no-op. The table's own function pointers (the engine streamer's path)
 * are not tracked: stop every streamer using the generator first. (2026-11-19; no ABI change.) */
SBW_API void sbw_cv_generator_destroy(const cv_generator* generator);

/* Direct entry points (same functions the table holds). */
SBW_API int32_t sbw_generate_cube(const cv_generator* g, int64_t cx, int64_t cy, int64_t cz,
                                  uint16_t* ids, uint16_t* const* layers);
SBW_API int32_t sbw_populate(const cv_generator* g, const cv_gen_api* api, cv_world* world,
                             int64_t cx, int64_t cy, int64_t cz);
SBW_API int32_t sbw_column_surface(const cv_generator* g, int64_t cx, int64_t cz,
                                   int64_t* out_sky, int64_t* out_top);

/* ---- FastLOD sampling (mirrors TerrainGenerator.sampleColumns / sampleRawColumns /
 * sampleCellOpenings / probeCellTrees). Grids are count x count at (x0 + ix*stride, z0 + iz*stride),
 * indexed [ix * count + iz] like the Java. Outputs other than heights may be NULL. Return 0 or a
 * negative error. With SBW_SAMPLE_CARVED a sea-flooded cut (SBW_CARVE_SEA_STACKS) reports water = sea level over
 * its shelf. */
SBW_API int32_t sbw_sample_columns(const cv_generator* g, int64_t x0, int64_t z0, int32_t count,
                                   int32_t stride, uint32_t flags, int32_t* out_heights,
                                   int32_t* out_water, uint16_t* out_surface, int32_t* out_trees);
/* Cave-mouth geometry per coarse cell: lowest carved floor in each cellSize^2 footprint below
 * cell_heights (INT32_MIN when none) and the carved share 0..255. */
SBW_API int32_t sbw_sample_cell_openings(const cv_generator* g, int64_t x0, int64_t z0,
                                         int32_t cells, int32_t cell_size,
                                         const int32_t* cell_heights, int32_t* out_floor,
                                         uint8_t* out_coverage);
/* As sbw_sample_cell_openings, plus out_water (may be NULL): the water level standing in each cell's mouth -
 * sea level when the footprint holds a sea-flooded cut (SBW_CARVE_SEA_STACKS: a flooded shelf, cove or moat) and
 * the floor lies under the sea, else NO_WATER. Added within ABI 4 (callers look it up optionally). */
SBW_API int32_t sbw_sample_cell_openings_water(const cv_generator* g, int64_t x0, int64_t z0,
                                               int32_t cells, int32_t cell_size,
                                               const int32_t* cell_heights, int32_t* out_floor,
                                               uint8_t* out_coverage, int32_t* out_water);
/* Every tree planted inside a coarse node: spots packed (lx & 0xFF) | (lz & 0xFF) << 8 |
 * kind << 16 | trunk << 18 (lx, lz relative to x0, z0). Returns the count, or -(needed) - 16 when
 * out_cap is too small (CV_NEED convention). */
SBW_API int32_t sbw_probe_cell_trees(const cv_generator* g, int64_t x0, int64_t z0, int32_t cells,
                                     int32_t cell_size, const int32_t* cell_heights,
                                     const int32_t* cell_water, const uint16_t* cell_surface,
                                     int32_t* out_spots, int32_t out_cap);
SBW_API int32_t sbw_query_column(const cv_generator* g, int64_t x, int64_t z, sbw_column_info* out);
/* Carved surface of a column cube: out_carved_height[1024] and out_carved[1024] (1 = cut down from
 * the raw height), [lz * 32 + lx]. Either may be NULL. */
/* Registers an SBRB rock shape bank (format: src/rocks.hpp) under `bank_id` for this process; generators
 * created afterwards with sbw_gen_config.rock_bank = bank_id read it (the bank is copied; the caller keeps
 * `data`). Registering the same bank again is a no-op; a different bank under a taken id fails. Returns 0, or -1
 * with sbw_last_error. Added within ABI 4 (callers look it up optionally). */
SBW_API int32_t sbw_register_rock_bank(uint32_t bank_id, const uint8_t* data, int64_t length);
SBW_API int32_t sbw_column_carve(const cv_generator* g, int64_t cx, int64_t cz,
                                 int32_t* out_carved_height, uint8_t* out_carved);

#ifdef __cplusplus
}
#endif
