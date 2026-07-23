/* Standalone worm-carver ABI (ck_terrain_create / ck_carve_worms) — a thin
 * extern "C" wrapper over the shared native terrain context + worm walk in
 * terrain_ctx.hpp. Cavern-connector anchors arrive precomputed from Java
 * (CavernCarver/MegaCavernCarver own cavern placement on this path).
 *
 * The fused chunk generator (generator.cpp) reuses the same internals with a
 * native anchor source instead. */
#include "cenda/kernels.h"
#include "terrain_ctx.hpp"

namespace {

using cenda::gen::AnchorSource;
using cenda::gen::TerrainCtx;

/* Anchor lookup over the Java-precomputed (srcChunkX, srcChunkZ) -> (x,y,z)
 * arrays. The worm origin is ignored — Java derived each anchor with the same
 * origin already. */
struct ArrayAnchorSource final : AnchorSource {
    int32_t count = 0;
    const int32_t* chunks = nullptr;
    const float* positions = nullptr;

    bool anchorFor(int cx, int cz, float /*ox*/, float /*oy*/, float /*oz*/,
                   float out[3]) const override {
        for (int32_t i = 0; i < count; i++) {
            if (chunks[i * 2] == cx && chunks[i * 2 + 1] == cz) {
                const float* p = positions + static_cast<ptrdiff_t>(i) * 3;
                out[0] = p[0];
                out[1] = p[1];
                out[2] = p[2];
                return true;
            }
        }
        return false;
    }
};

} // namespace

extern "C" {

void* ck_terrain_create(int64_t worm_seed,
                        const int32_t* ch_seeds, const int32_t* ch_octaves,
                        const float* ch_gain, const float* ch_lacunarity,
                        const float* ch_freq,
                        const int32_t* ch_xoff, const int32_t* ch_zoff,
                        const double* spline_xs, const double* spline_ys,
                        const int32_t* spline_sizes,
                        float detail_amplitude) {
    return cenda::gen::terrainCreateImpl(worm_seed, ch_seeds, ch_octaves, ch_gain,
                                         ch_lacunarity, ch_freq, ch_xoff, ch_zoff,
                                         spline_xs, spline_ys, spline_sizes,
                                         detail_amplitude);
}

void ck_terrain_destroy(void* ctx) {
    delete static_cast<TerrainCtx*>(ctx);
}

int64_t ck_carve_worms(void* ctxPtr, int32_t chunk_x, int32_t chunk_z,
                       const int32_t* target_heights,
                       int32_t n_anchors, const int32_t* anchor_chunks,
                       const float* anchors,
                       uint64_t* out_mask) {
    auto* ctx = static_cast<TerrainCtx*>(ctxPtr);
    if (ctx == nullptr || target_heights == nullptr || out_mask == nullptr) {
        return -1;
    }
    if (n_anchors > 0 && (anchor_chunks == nullptr || anchors == nullptr)) {
        return -1;
    }
    ArrayAnchorSource anchorSource;
    anchorSource.count = n_anchors;
    anchorSource.chunks = anchor_chunks;
    anchorSource.positions = anchors;
    return cenda::gen::carveWormsImpl(*ctx, chunk_x, chunk_z, target_heights,
                                      &anchorSource, out_mask);
}

} // extern "C"
