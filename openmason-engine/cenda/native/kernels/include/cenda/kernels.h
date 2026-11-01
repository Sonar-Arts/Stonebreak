/* Cenda native kernels — the engine's C ABI consumed via FFM (CendaKernels):
 * noise, the chunk mesher and zstd. Game-specific kernels live in the game's
 * own native library (Stonebreak: stonebreak-game/native, #386).
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

#define CK_ABI_VERSION 13 /* 13 (#386): the worldgen kernels (ck_terrain_*, ck_carve_worms, ck_chunkgen_*,
                             ck_generate_chunk) moved to the game's own stonebreak_worldgen library */

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
 * Culls + lights the standard-cube faces of one cs x wh x cs chunk (cs =
 * chunk_size, 1..CK_MESH_MAX_CHUNK_SIZE; wh = world_height, any positive
 * height — #385), replicating the Java MmsCcoAdapter/VertexLightSampler
 * semantics bit-for-bit (no libm in this path, so exact float parity is
 * achievable and tested).
 *
 * Block array index: idx = (y*cs + z)*cs + x  (== section*cs*cs*16 + CCO cellIndex).
 * Faces: 0 top(+y) 1 bottom(-y) 2 north(-z) 3 south(+z) 4 east(+x) 5 west(-x).
 *
 * class_table: one byte per block id (index = id), bit flags below. Ids not in
 * the table are treated as 0 (skipped, non-transparent, non-solid).
 *
 * Border planes carry neighbor-chunk block ids at local x==-1 / x==cs
 * (index [y*cs+z]) and z==-1 / z==cs (index [y*cs+x]); corner columns carry
 * the diagonal-neighbor column at (-1,-1),(cs,-1),(-1,cs),(cs,cs) (index [y]).
 * Pass NULL for an unloaded neighbor: culling then sees AIR and ambient
 * occlusion sees non-solid, exactly like the Java path.
 *
 * heights: (cs+2)x(cs+2) sky heightmap, index [(lz+1)*(cs+2) + (lx+1)], world
 * Y of the first free cell above the column's topmost opaque block; -1 = unloaded.
 *
 * Output: quad records of 9 floats {lx, ly, lz, face, blockId, l0, l1, l2, l3}
 * where l0..l3 are the per-corner light values in FACE_VERTEX_OFFSETS corner
 * order. Returns the quad count, -(needed) if cap_quads is too small, or
 * CK_MESH_BAD_ARGS for a null array or a dimension out of range. */

/* Widest chunk the mesher takes: a row mask is a uint16 with one bit per lz. */
#define CK_MESH_MAX_CHUNK_SIZE 16
/* Invalid arguments (never a valid count or a retry size). */
#define CK_MESH_BAD_ARGS INT32_MIN

#define CK_CLASS_CUBE 1u         /* meshed as a standard cube by this kernel */
#define CK_CLASS_TRANSPARENT 2u  /* BlockType.isTransparent() */
#define CK_CLASS_OPAQUE_LIGHT 4u /* solid for AO (BlockOpacity.isOpaque)     */

int32_t ck_mesh_chunk(const int16_t* blocks,
                      int32_t chunk_size, int32_t world_height,
                      const uint8_t* class_table, int32_t class_table_len,
                      int32_t air_id,
                      const int16_t* plane_xn, const int16_t* plane_xp,
                      const int16_t* plane_zn, const int16_t* plane_zp,
                      const int16_t* corner_nn, const int16_t* corner_pn,
                      const int16_t* corner_np, const int16_t* corner_pp,
                      const int16_t* heights,
                      int32_t max_y, int32_t smooth_lighting,
                      float* out_quads, int32_t cap_quads);

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
