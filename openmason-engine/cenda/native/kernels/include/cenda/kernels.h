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

#define CK_ABI_VERSION 1

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

#ifdef __cplusplus
}
#endif
