/* Cenda native kernels — C ABI consumed by the Java game via FFM.
 *
 * Contract notes:
 *  - Handles returned by ck_noise_* are opaque and NULL on failure.
 *  - A created node is immutable; generation calls on it are thread-safe,
 *    so one node can serve every terrain worker thread.
 *  - Grid fills are row-major with X the fastest-varying dimension:
 *    index = x + y*x_count (+ z*x_count*y_count).
 *  - Sampling positions are world-space: offset + i*step per axis. Noise
 *    "frequency" is expressed by pre-scaling offset and step by the frequency
 *    on the Java side (position * freq), matching FastNoise2 v1.x semantics.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CK_ABI_VERSION 2

/* ABI handshake — Java refuses to use the lib if this doesn't match. */
int32_t ck_abi_version(void);

/* Human-readable SIMD feature set FastNoise2 will dispatch to (static string,
 * never freed). Diagnostic only. */
const char* ck_simd_level(void);

/* Build a generator from a FastNoise2 encoded node tree (as exported by
 * NoiseTool). The most general entry point: new noise stacks need no new
 * native code, just a new tree string on the Java side. */
void* ck_noise_from_encoded_tree(const char* encoded_tree);

/* Convenience: Simplex source under FractalFBm. The frequency is applied
 * INSIDE the node (the generator's feature scale), so callers pass RAW world
 * coordinates as offsets/steps. Keeping positions as exact integer floats
 * makes per-point and batched sampling bit-identical at the same coordinate
 * (critical for FastLOD/chunk parity). frequency <= 0 rejects. */
void* ck_noise_simplex_fbm(int32_t octaves, float lacunarity, float gain, float frequency);

void ck_noise_destroy(void* node);

/* Batch fills into a caller-provided buffer of x_count*y_count(*z_count)
 * floats. Returns 0 on success, nonzero on bad arguments. */
int32_t ck_gen_grid_2d(void* node, float* out,
                       float x_offset, float y_offset,
                       int32_t x_count, int32_t y_count,
                       float x_step, float y_step,
                       int32_t seed);

int32_t ck_gen_grid_3d(void* node, float* out,
                       float x_offset, float y_offset, float z_offset,
                       int32_t x_count, int32_t y_count, int32_t z_count,
                       float x_step, float y_step, float z_step,
                       int32_t seed);

/* ════════════════════════ Chunk mesher ════════════════════════
 *
 * Culls + lights the standard-cube faces of one 16x256x16 chunk, replicating
 * the Java MmsCcoAdapter/VertexLightSampler semantics bit-for-bit (no libm in
 * this path, so exact float parity is achievable and tested).
 *
 * Block array index: idx = y*256 + z*16 + x  (== section*4096 + CCO cellIndex).
 * Faces: 0 top(+y) 1 bottom(-y) 2 north(-z) 3 south(+z) 4 east(+x) 5 west(-x).
 *
 * class_table: one byte per block id (index = id), bit flags below. Ids not in
 * the table are treated as 0 (skipped, non-transparent, non-solid).
 *
 * Border planes carry neighbor-chunk block ids at local x==-1 / x==16
 * (index [y*16+z]) and z==-1 / z==16 (index [y*16+x]); corner columns carry
 * the diagonal-neighbor column at (-1,-1),(16,-1),(-1,16),(16,16) (index [y]).
 * Pass NULL for an unloaded neighbor: culling then sees AIR and ambient
 * occlusion sees non-solid, exactly like the Java path.
 *
 * heights: 18x18 sky heightmap, index [(lz+1)*18 + (lx+1)], world Y of the
 * first free cell above the column's topmost opaque block; -1 = unloaded.
 *
 * Output: quad records of 9 floats {lx, ly, lz, face, blockId, l0, l1, l2, l3}
 * where l0..l3 are the per-corner light values in FACE_VERTEX_OFFSETS corner
 * order. Returns the quad count, or -(needed) if cap_quads is too small. */

#define CK_CLASS_CUBE 1u         /* meshed as a standard cube by this kernel */
#define CK_CLASS_TRANSPARENT 2u  /* BlockType.isTransparent() */
#define CK_CLASS_OPAQUE_LIGHT 4u /* solid for AO (BlockOpacity.isOpaque)     */

int32_t ck_mesh_chunk(const int16_t* blocks,
                      const uint8_t* class_table, int32_t class_table_len,
                      int32_t air_id,
                      const int16_t* plane_xn, const int16_t* plane_xp,
                      const int16_t* plane_zn, const int16_t* plane_zp,
                      const int16_t* corner_nn, const int16_t* corner_pn,
                      const int16_t* corner_np, const int16_t* corner_pp,
                      const int16_t* heights,
                      int32_t max_y, int32_t smooth_lighting,
                      float* out_quads, int32_t cap_quads);

/* ════════════════════════ Worm carver ════════════════════════
 *
 * Full port of PerlinWormCarver over a native terrain context (the same
 * FastNoise2 channel nodes the Java NoiseRouter uses + linear splines).
 * Deterministic per seed, but NOT bit-identical to the Java carver (libm trig
 * differs across languages) — callers gate it on the native noise backend so
 * one implementation owns a world's caves.
 *
 * Channels order everywhere: continentalness, peaksValleys, erosion, detail.
 * Splines order: base(C), peak(PV), erosion->peakStrength(E).
 *
 * anchors: per worm-bearing source chunk that has a cavern connector target,
 * 2 ints (srcChunkX, srcChunkZ) + 3 floats (x,y,z anchor). Java precomputes
 * these with CavernCarver so cavern placement stays consistent with the Java
 * cavern rasterizer.
 *
 * out_mask: 1024 uint64 words, bit index (x<<12 | y<<4 | z) — matches
 * java.util.BitSet.valueOf(long[]) layout. Returns number of set bits. */

void* ck_terrain_create(int64_t worm_seed,
                        const int32_t* ch_seeds, const int32_t* ch_octaves,
                        const float* ch_gain, const float* ch_lacunarity,
                        const float* ch_freq,
                        const int32_t* ch_xoff, const int32_t* ch_zoff,
                        const double* spline_xs, const double* spline_ys,
                        const int32_t* spline_sizes,
                        float detail_amplitude);

void ck_terrain_destroy(void* ctx);

int64_t ck_carve_worms(void* ctx, int32_t chunk_x, int32_t chunk_z,
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
 * ck_chunkgen_create:
 *  - terrain channel/spline params: same 11 as ck_terrain_create.
 *  - density_*: the Density3D cave-noise node (built via the simplex-fbm
 *    convenience, frequency inside the node).
 *  - block_ids: [air, water, stone, bedrock, magma].
 *  - biome tables: n_biomes entries each, indexed by BiomeType ordinal;
 *    flags bit0 = magma host biome, bit1 = dry-below-sea biome.
 *  - magma_feature_hash: Java "magma".hashCode() (DeterministicRandom stream).
 *  - opacity_table: per block id, 1 = opaque for the sky heightmap
 *    (BlockOpacity.isOpaque); ids >= opacity_table_len are non-opaque.
 *
 * ck_generate_chunk:
 *  - heights/biomes: 256 entries, [x*16 + z] (populateChunkHeights layout);
 *    biomes are BiomeType ordinals into the create-time tables.
 *  - out_blocks: 65536 int16, idx = y*256 + z*16 + x (mesher layout ==
 *    16 concatenated CCO sections).
 *  - out_heightmap: 256 int32, [z*16 + x] (ChunkHeightMap layout), Y+1 of the
 *    topmost opaque block per column, 0 = sky all the way down. NULL to skip.
 *  - Returns the non-air block count (>= 0), or negative on bad args. */

void* ck_chunkgen_create(
    int64_t seed,
    const int32_t* ch_seeds, const int32_t* ch_octaves,
    const float* ch_gain, const float* ch_lacunarity, const float* ch_freq,
    const int32_t* ch_xoff, const int32_t* ch_zoff,
    const double* spline_xs, const double* spline_ys, const int32_t* spline_sizes,
    float detail_amplitude,
    int32_t density_seed, int32_t density_octaves,
    float density_gain, float density_lacunarity, float density_freq,
    const int32_t* block_ids,
    int32_t n_biomes,
    const int16_t* biome_surface_id, const int16_t* biome_subsurface_id,
    const float* biome_cave_intensity, const float* biome_overhang_intensity,
    const uint8_t* biome_flags,
    int32_t magma_feature_hash, float magma_chance,
    const uint8_t* opacity_table, int32_t opacity_table_len);

#define CK_BIOME_MAGMA 1u          /* biome hosts deep magma pockets            */
#define CK_BIOME_DRY_BELOW_SEA 2u  /* suppress sub-sea WATER when surface > sea */

void ck_chunkgen_destroy(void* ctx);

int64_t ck_generate_chunk(void* ctx, int32_t chunk_x, int32_t chunk_z,
                          const int32_t* heights, const int32_t* biomes,
                          int16_t* out_blocks, int32_t* out_heightmap);

/* ════════════════════════ zstd codec ════════════════════════ */

int64_t ck_zstd_bound(int64_t src_size);
/* Returns compressed size, or negative on error. */
int64_t ck_zstd_compress(uint8_t* dst, int64_t dst_cap,
                         const uint8_t* src, int64_t src_size, int32_t level);
/* Returns decompressed size, or negative on error. */
int64_t ck_zstd_decompress(uint8_t* dst, int64_t dst_cap,
                           const uint8_t* src, int64_t src_size);

#ifdef __cplusplus
}
#endif
