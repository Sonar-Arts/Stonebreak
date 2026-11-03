/* Stonebreak worldgen natives — C ABI consumed by the game via FFM
 * (com.stonebreak.world.generation.nativegen.WorldgenNatives). Issue #386
 * moved these out of the engine's Cenda kernels library: they are ports of
 * Stonebreak's own generator (PerlinWormCarver, CavernCarver, Density3D,
 * TerrainGenerationSystem's block fill, DeterministicRandom), not engine code.
 *
 * Noise parity with the engine: the terrain channels are built with the
 * engine's shared cenda/noise_nodes.hpp against the same pinned FastNoise2
 * (Open Mason's cmake/CendaFastNoise2.cmake, delivered by its Cenda SDK at
 * the engine version the game runs with, #388), so channel values are
 * bit-identical to what CendaKernels' ck_gen_grid_* return to Java.
 *
 * The kernels emit a FIXED 16 x 256 x 16 volume (Stonebreak's Standard
 * terrain frame; see terrain_ctx.hpp), whatever the engine's VoxelWorldConfig
 * says — the game lifts the volume into its own storage. */
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

#define SBW_ABI_VERSION 2 /* 1 (#386): split out of the engine's CK ABI 12; 2: distilled Standard shape (11 channels, constants + curve table in the spline slots) */

/* ABI handshake — WorldgenNatives refuses the lib if this doesn't match. */
SBW_API int32_t sbw_abi_version(void);

/* ════════════════════════ Worm carver ════════════════════════
 *
 * Full port of PerlinWormCarver over a native terrain context (the same
 * FastNoise2 channel nodes and formula as the Java DistilledShape).
 * Deterministic per seed, but NOT bit-identical to the Java carver (libm trig
 * differs across languages) — callers gate it on the native noise backend so
 * one implementation owns a world's caves.
 *
 * ABI 2 terrain params (DistilledShape.nativeArgs): ch_* = the 11 shape channels in
 * DistilledShape order (cont, warpX, warpZ, range, spur, along, hills, hilly, valley,
 * texture, detail; offsets must be 0); spline_xs = the DistilledShape constants
 * (DistilledShape::K_* order), spline_ys = the height-curve table (HeightCurveTable),
 * spline_sizes = {11, constant count, table length}; detail_amplitude = detail blocks
 * (informational, the constants carry it).
 *
 * anchors: per worm-bearing source chunk that has a cavern connector target,
 * 2 ints (srcChunkX, srcChunkZ) + 3 floats (x,y,z anchor). Java precomputes
 * these with CavernCarver so cavern placement stays consistent with the Java
 * cavern rasterizer.
 *
 * out_mask: 1024 uint64 words, bit index (x<<12 | y<<4 | z) — matches
 * java.util.BitSet.valueOf(long[]) layout. Returns number of set bits. */

SBW_API void* sbw_terrain_create(int64_t worm_seed,
                        const int32_t* ch_seeds, const int32_t* ch_octaves,
                        const float* ch_gain, const float* ch_lacunarity,
                        const float* ch_freq,
                        const int32_t* ch_xoff, const int32_t* ch_zoff,
                        const double* spline_xs, const double* spline_ys,
                        const int32_t* spline_sizes,
                        float detail_amplitude);

SBW_API void sbw_terrain_destroy(void* ctx);

SBW_API int64_t sbw_carve_worms(void* ctx, int32_t chunk_x, int32_t chunk_z,
                       const int32_t* target_heights,
                       int32_t n_anchors, const int32_t* anchor_chunks,
                       const float* anchors,
                       uint64_t* out_mask);

/* ════════════════════ Fused chunk generator ════════════════════
 *
 * One call generates a chunk's full block volume: worm carve + cavern &
 * megacavern carve/formations + 3D cave density + biome block fill + sky
 * heightmap. Intermediates (carve masks, density volume) never cross the FFM
 * boundary. Java keeps: height/biome computation (passed in), features,
 * water layer, snow, chunk installation.
 *
 * Bit-identical to the mixed path (native worms + Java caverns + Java fill)
 * given identical inputs; NOT bit-identical to the pure-Java noise backend —
 * callers gate on the native backend exactly like the standalone carver.
 * Cavern-connector anchors are computed natively (pure integer/LCG/float
 * math, bit-identical to PerlinWormCarver.cavernAnchorFor).
 *
 * sbw_chunkgen_create:
 *  - terrain channel/spline params: same 11 as sbw_terrain_create.
 *  - density_*: Density3D's FOUR cave-noise nodes, in fill order
 *    (cheese, spaghetti 1, spaghetti 2, crag), each built via the simplex-fbm
 *    convenience with the frequency inside the node. FOUR is authoritative —
 *    the kernel reads four entries; sizing the arrays to fewer reads one past
 *    the end (see Density3D.nodeParams, which is the single source).
 *  - cheese_spline_*: Density3D's depth->threshold curve, same packing as
 *    spline_xs (one curve, so cheese_spline_sizes is a single count).
 *  - block_ids: [air, water, stone, bedrock, magma].
 *  - biome tables: n_biomes entries each, indexed by BiomeType ordinal;
 *    flags bit0 = magma host biome, bit1 = dry-below-sea biome.
 *  - magma_feature_hash: Java "magma".hashCode() (DeterministicRandom stream).
 *  - opacity_table: per block id, 1 = opaque for the sky heightmap
 *    (BlockOpacity.isOpaque); ids >= opacity_table_len are non-opaque.
 *
 * sbw_generate_chunk:
 *  - heights/biomes: 256 entries, [x*16 + z] (populateChunkHeights layout);
 *    biomes are BiomeType ordinals into the create-time tables.
 *  - extra_carve_mask: 1024 uint64 (bit = (x<<12)|(y<<4)|z), OR'd into the
 *    kernel's own carve mask, or NULL for none. This is how the surface-anchored
 *    carvers reach the fused path: RavineCarver and SinkholeCarver walk a shape
 *    grammar over Java's NoiseGenerator, which has no native point sampler, so
 *    duplicating them here would mean porting that simplex bit-exactly to gain a
 *    pass that only fires in 1-in-450 and 1-in-40 chunks. Taking the Java mask
 *    instead keeps one implementation of the grammar and makes the two paths
 *    agree by construction rather than by test.
 *  - out_blocks: 65536 int16, idx = y*256 + z*16 + x (mesher layout ==
 *    16 concatenated CCO sections).
 *  - out_heightmap: 256 int32, [z*16 + x] (ChunkHeightMap layout), Y+1 of the
 *    topmost opaque block per column, 0 = sky all the way down. NULL to skip.
 *  - Returns the non-air block count (>= 0), or negative on bad args. */

SBW_API void* sbw_chunkgen_create(
    int64_t seed,
    const int32_t* ch_seeds, const int32_t* ch_octaves,
    const float* ch_gain, const float* ch_lacunarity, const float* ch_freq,
    const int32_t* ch_xoff, const int32_t* ch_zoff,
    const double* spline_xs, const double* spline_ys, const int32_t* spline_sizes,
    float detail_amplitude,
    const int32_t* density_seeds, const int32_t* density_octaves,
    const float* density_gain, const float* density_lacunarity, const float* density_freq,
    const double* cheese_spline_xs, const double* cheese_spline_ys,
    const int32_t* cheese_spline_sizes,
    const int32_t* block_ids,
    int32_t n_biomes,
    const int16_t* biome_surface_id, const int16_t* biome_subsurface_id,
    const float* biome_cave_intensity, const float* biome_overhang_intensity,
    const uint8_t* biome_flags,
    int32_t magma_feature_hash, float magma_chance,
    const uint8_t* opacity_table, int32_t opacity_table_len);

#define SBW_BIOME_MAGMA 1u          /* biome hosts deep magma pockets            */
#define SBW_BIOME_DRY_BELOW_SEA 2u  /* suppress sub-sea WATER when surface > sea */
#define SBW_BIOME_CRAG_SURFACE 4u   /* overhang band reads the crag channel      */

SBW_API void sbw_chunkgen_destroy(void* ctx);

SBW_API int64_t sbw_generate_chunk(void* ctx, int32_t chunk_x, int32_t chunk_z,
                          const int32_t* heights, const int32_t* biomes,
                          const uint64_t* extra_carve_mask,
                          int16_t* out_blocks, int32_t* out_heightmap);

#ifdef __cplusplus
}
#endif
