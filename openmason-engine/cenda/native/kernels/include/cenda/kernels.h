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

#define CK_ABI_VERSION 7

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
 *  - density_*: Density3D's THREE cave-noise nodes, in fill order
 *    (cheese, spaghetti 1, spaghetti 2), each built via the simplex-fbm
 *    convenience with the frequency inside the node.
 *  - cheese_spline_*: Density3D's depth->threshold curve, same packing as
 *    spline_xs (one curve, so cheese_spline_sizes is a single count).
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

void* ck_chunkgen_create(
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

#define CK_BIOME_MAGMA 1u          /* biome hosts deep magma pockets            */
#define CK_BIOME_DRY_BELOW_SEA 2u  /* suppress sub-sea WATER when surface > sea */
#define CK_BIOME_CRAG_SURFACE 4u   /* overhang band reads the crag channel      */

void ck_chunkgen_destroy(void* ctx);

int64_t ck_generate_chunk(void* ctx, int32_t chunk_x, int32_t chunk_z,
                          const int32_t* heights, const int32_t* biomes,
                          const uint64_t* extra_carve_mask,
                          int16_t* out_blocks, int32_t* out_heightmap);

/* ═════════════════ The water params array (plan.md §8) ═════════════════
 *
 * ONE array, read by both water kernels: `ck_solve_basins` takes the indices
 * that decide where water is, `ck_carve_water` the ones that decide what it
 * looks like on the ground. Two arrays would mean two places for a knob to
 * drift out of step, and several of these are read by both.
 *
 * Any prefix is legal; entries past the end take the default. NULL/0 is all
 * defaults.
 *
 *   idx  name                  unit      default  read by
 *   ---  --------------------  --------  -------  -----------------
 *    0   min_lake_depth        blocks        0.5  solve
 *    1   min_lake_area         cells           8  solve
 *    2   sea_level             blocks        320  solve, carve
 *    3   min_river_lake_area   cells          12  solve
 *    4   river_keep_fraction   fraction     0.70  solve
 *    5   step_len              blocks         16  solve
 *    6   max_steps             steps         256  solve   (halo may bind)
 *    7   w_inertia             weight        1.0  solve
 *    8   w_descent             weight       0.55  solve
 *    9   meander_amp           radians      0.35  solve
 *   10   bank_tolerance        blocks          8  carve
 *   11   gorge_max_depth       blocks         24  solve
 *   12   gorge_max_width       blocks         96  solve
 *   13   waterfall_min_drop    blocks          6  solve
 *   14   valley_radius         blocks         80  carve
 *   15   w_base                blocks          4  solve
 *   16   w_lake                blocks          3  solve
 *   17   w_dist                blocks          2  solve
 *   18   vol_scale             blocks^3    50000  solve
 *   19   dist_scale            blocks       1000  solve
 *   20   d_base                blocks        1.5  solve
 *   21   d_gain                blocks        0.8  solve
 *   22   plunge_widen          multiplier    1.6  solve
 *   23   refine_levels         count           2  solve
 *   24   refine_amp            fraction     0.22  solve
 *   25   min_points            vertices        4  solve
 *
 * Four deliberate departures from §8's table:
 *
 *   sea_level is here at [2]. §8 omitted it because the old design passed it
 *   as an argument; both kernels need it and one source is better than two.
 *
 *   w_avoid is GONE. §8 reserved [8] for the obstruction avoidance of §5.7,
 *   which has no trigger: `filled >= raw` by construction and a route never
 *   climbs, so ground above the water surface cannot lie ahead of one.
 *   Measured over 158 real route steps, the largest `rise` at any centreline
 *   was 3.1 blocks against a bank tolerance of 8 — and that 3.1 is a bilinear
 *   sample against its own cell, not terrain. Indices after it shift down one.
 *
 *   vol_scale and dist_scale are new. §5.5's width formula divides by both and
 *   §8's table forgot them; a width term without its scale is not a knob.
 *
 *   The ownership lattice — cell/region/halo, i.e. which region emits which
 *   column — is NOT here. It is passed per call (see ck_solve_basins) because
 *   the caller owns the escalation ladder, but it is not a runtime knob in the
 *   retuning sense: changing a rung silently invalidates every cached region
 *   rather than retuning anything, so `BasinCache`'s fingerprint covers every
 *   rung of the ladder.
 */

/* ═══════════════ Region water plan (lakes + rivers) ═══════════════
 *
 * One region's depression fill AND the river routes whose sources it owns —
 * layers 1 and 2 of the lakes-first hydrology (plan.md §4, §5). The unit of
 * work is a REGION, not a tile: the solve is ~23 ms on the 512² window L1
 * uses, which is 0.089 ms once amortized over that region's 256 terrain tiles
 * but ruinous if repeated per tile. The caller caches it.
 *
 * Both come from one call because the routes need the fill's basin table —
 * spill points, areas, volumes — and that table deliberately does not cross
 * this boundary. Splitting them would mean solving the region twice.
 *
 * dem: cells^2 float32, row = world X, col = world Z, fractional block
 * heights on a `cell_blocks` lattice, covering world
 * [origin_x, origin_x + cells*cell_blocks) x the same in Z. Fractional
 * matters: quantised to whole blocks 40 % of land is perfectly level and
 * downhill routing over it degenerates into a distance field.
 *
 * origin must be the level's own window origin for the region — that is,
 * region * region_blocks - halo_blocks — because ownership of both lakes and
 * river sources is decided from it.
 *
 * out_filled: the depression-filled surface — the water surface AND the field
 * rivers descend. Complete, including basins whose lake is withheld, because
 * a route descending it must never be able to go uphill.
 * out_depth: lake depth in blocks, and exactly 0 wherever this level has no
 * lake to vouch for. Ownership (§4.4) is applied before either plane is
 * written, so `depth > 0` alone tells a consumer what it may trust.
 *
 * region_blocks / halo_blocks: the level's ownership geometry, together with
 * cell_blocks. A basin whose own bounding box exceeds halo_blocks is NOT
 * emitted by this level — it would be cut differently by a window one region
 * over, and the two would disagree across the seam.
 *
 * These are CALLER DATA rather than a fixed pair of levels, because the number
 * of rungs between "cheap and narrow" and "expensive and wide" is a cost
 * decision, not a physical one. Escalating L1 (a 16-chunk DEM fetch) straight
 * to a 16 km halo (1,024 chunks, ~24 min of GPU measured 2026-09-03) to own a
 * basin barely past L1's limit is what made a world load unusable; a caller
 * that can interpose 4 km and 8 km rungs pays 64 chunks instead. The kernel
 * has no opinion on the ladder — it applies the ownership rule to whatever
 * geometry it is handed, and the caller's cache fingerprint must cover every
 * rung of it (see BasinCache.fingerprint).
 *
 * coarse_*: optional. Pass the coarser level's already-solved planes and their
 * geometry to import the basins this level withheld; pass NULL to skip. The
 * return value says whether it is worth fetching: a region that withheld
 * nothing needs no coarse solve, which on measured terrain is the usual case
 * (largest basin 928 blocks against L1's 2048-block halo).
 *
 * out_withheld: optional, 2 int32 — {largest withheld lake in CELLS of this
 * call's lattice, longest withheld bbox side in blocks}, over the same basins
 * the return value counts. Both 0 when nothing was withheld.
 *
 * This is the second half of the escalation decision and the return value is
 * not usable without it. A coarser rung applies `min_lake_area` in ITS OWN
 * cells, so the smallest lake it will emit grows with its cell area — on the
 * shipping parameters 0.46 km² at L1, 1.84 at L2, 7.37 at L3, 29.5 at L4.
 * Escalating for a lake below the next rung's floor buys a plane that comes
 * back dry over that ground, at 4x the DEM: measured 2026-09-03, one such
 * escalation cost 207 CoarseDem chunks (~5-7 min of serial GPU, 76 % of a
 * whole world load) and emitted zero lake cells. Compare the stake against
 * the rung you are about to pay for before paying for it.
 *
 * The lake figure is a LOWER BOUND when the basin is truncated by the window
 * — the border is an escape, so a truncated fill sits at or below the true
 * spill — so a caller gating on it must leave headroom.
 *
 * ── Rivers ──
 * Optional: pass out_vertices NULL to skip planning them. Otherwise
 * out_vertices holds up to max_vertices packed vertices of
 * CK_RIVER_VERTEX_FLOATS floats each, out_route_starts holds up to
 * max_routes+1 offsets into it (route r spans [starts[r], starts[r+1])), and
 * out_river_counts receives {route count, vertex count}. Planning stops when
 * either cap is reached rather than overflowing; a caller that sees the counts
 * pinned at its caps should raise them.
 *
 * A route is emitted by the region that owns its SOURCE, so no two regions
 * ever plan the same river. A consumer needing every route that touches some
 * ground must gather from every region within a route's reach of it, which at
 * L1 is ~2 km — rivers cross region borders and ownership does not change that.
 *
 * params: the shared water params array above; solve reads [0]-[13] and
 * [15]-[25]. *
 * Thread-safe and reentrant (per-thread scratch). Returns the number of basins
 * withheld for the coarser level (>= 0), or negative on bad arguments. */

/* Packed river vertex layout: x, z, surf, width, bed_depth, bank, flags. */
#define CK_RIVER_VERTEX_FLOATS 7
#define CK_RIVER_FLAG_WATERFALL 1
#define CK_RIVER_FLAG_GORGE 2

int32_t ck_solve_basins(int64_t seed,
                        int32_t region_blocks, int32_t halo_blocks,
                        int32_t cells, int32_t cell_blocks,
                        int64_t origin_x, int64_t origin_z,
                        const float* dem,
                        const float* params, int32_t n_params,
                        int32_t coarse_cells, int32_t coarse_cell_blocks,
                        int64_t coarse_origin_x, int64_t coarse_origin_z,
                        const float* coarse_filled, const float* coarse_depth,
                        float* out_filled, float* out_depth,
                        int32_t max_routes, int32_t max_vertices,
                        int32_t* out_route_starts, float* out_vertices,
                        int32_t* out_river_counts,
                        int32_t* out_withheld);

/* ════════════════════════ Water carve ════════════════════════
 *
 * Inland water at block resolution, stamped from a basin solve — layer 3 of
 * the lakes-first hydrology (plan.md §6). Input is one terrain tile's raw
 * block heights plus a one-tile halo on every side (a 3x3 tile window), and
 * the depression-fill planes covering that same ground; output is the CENTER
 * tile's heights and per-column water levels.
 *
 * This kernel invents nothing. Where water sits was decided by
 * ck_solve_basins; all that happens here is drawing the shoreline at block
 * resolution, which is a comparison rather than a construction: a lake's
 * surface is one integer for the whole basin, and a column is wet exactly when
 * its block height is below it. So a 16-block DEM yields a 1-block shoreline
 * while the surface stays level to the bit.
 *
 * heights3x3: (3*tile_size)^2 int16, row-major with row = world X and
 * col = world Z (the TerrainTile layout), covering world
 * [origin_x, origin_x + 3*tile_size) x [origin_z, origin_z + 3*tile_size).
 * The center tile is the middle third.
 *
 * dem_filled / dem_depth: dem_cells^2 float32 each, same row/col convention,
 * covering the SAME ground as heights3x3 — dem_cells * dem_cell_blocks must
 * equal 3*tile_size exactly, with the span's origin at (origin_x, origin_z).
 * These are a sub-window of the owning region's ck_solve_basins output. Pass
 * NULL for both to get sea-level-only water, the same graceful degradation as
 * an absent kernels library.
 *
 * n_routes / route_starts / vertices: the river routes near this window, as
 * ck_solve_basins packed them (CK_RIVER_VERTEX_FLOATS floats per vertex,
 * route r spanning [route_starts[r], route_starts[r+1])). A tile must be
 * handed every route that comes within a valley radius of it, which means
 * gathering from every region within a route's reach — rivers cross region
 * borders. Pass n_routes 0 to stamp lakes only.
 *
 * out_heights/out_water: tile_size^2, same layout, center tile only.
 * Water level w means the column holds water for height <= y < w; -1 = dry.
 * Terrain is returned uncarved for LAKES — one sits in a depression the
 * terrain already has — and carved for RIVERS: a channel cut to the bed depth
 * and, on an ordinary reach, a valley drawn down around it. A gorge reach
 * keeps its walls.
 * Containment invariant (WaterSim): every wet column's 4-neighbors are wet
 * or have terrain >= its level; worldgen water is source blocks, so a
 * violation is a permanent spring. Wet-next-to-wet at differing levels is a
 * waterfall and is deliberately allowed.
 *
 * params: the shared water params array above. The carve reads exactly two of
 * its entries — [10] bank_tolerance and [14] valley_radius. Sea level arrives
 * as the `sea_level` argument, NOT through [2]; the solve is what reads [2].
 *
 * Thread-safe and reentrant (per-thread scratch). Returns 0 on success,
 * negative on bad arguments. */

int32_t ck_carve_water(int64_t seed,
                       int32_t tile_size,
                       int32_t origin_x, int32_t origin_z,
                       const int16_t* heights3x3,
                       int32_t sea_level, int32_t world_height,
                       int32_t dem_cells, int32_t dem_cell_blocks,
                       const float* dem_filled, const float* dem_depth,
                       int32_t n_routes, const int32_t* route_starts,
                       const float* vertices,
                       const float* params, int32_t n_params,
                       int16_t* out_heights, int16_t* out_water);

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
