// Stage "controls": the EXP-2 world controls -- layout (controls2 + v4's procedural_controls), the climate node
// (climate windows, grid, cells), controls(), continent_land_cells, climate_native, detail_gates, red deserts.
// Shared pieces of the stage's translation units (configuration read once from the manifest, Python helpers).
#pragma once

#include "stages/common/util.hpp"

#include <cmath>

namespace sbtg::controls {

constexpr int kMaxRows = 72, kMaxK = 160, kMaxRowsV4 = 16, kMaxKV4 = 32, kMaxPc = 64;

// Everything the stage reads from the manifest, parsed once (a POD in a pinned host tensor of the generator's cache,
// so it is rebuilt with the caches and never outlives its generator).
struct Cfg {
    // v4 procedural_controls (csrc/controls.cu)
    int pc_n;
    int pc_streams[kMaxPc];
    float pc_invp[kMaxPc];
    int pc_rows[9];
    float pc_k[16];
    float pc_arch[28];
    // controls2 (csrc/controls2.cu)
    int c2_n, c2v_n, c2_nk, c2_nkv4;
    int c2_streams[kMaxRows];
    float c2_invp[kMaxRows];
    int c2v_streams[kMaxRowsV4];
    float c2v_invp[kMaxRowsV4];
    float c2_kv4[kMaxKV4];
    float c2_k[3][kMaxK];
    int k_cos_th, k_sin_th, k_cos_flow, k_sin_flow, hot_stream, salt_th, salt_flow;
    int n_fields, n_gates;
    float c035, inv_land, sea_seed_trend;
    // BV sea link (generator2.SEA_LINK*): 0 = off (exports without it)
    int sea_link, sea_link_cells, sea_link_steps;
    float sea_link_trend, sea_link_sea_land;
    // climate node
    int has_climate, canonical;
    double grid_cells;
    float grid_cells_f;
    int win, core, apron, sub, fetch;
    double grid_km;
    float inv_grid_km, wind_scale, lw0, lw1, calm_inv, up_eps;
    int n_near, n_far;
    float near_km[8], far_km[8];
    float inv_near, inv_far, forcing0, forcing1;
    int n_out;
    float mean[7], stdv[7];
    double kms[3];
    // controls(), detail_gates, climate_native
    int aeolian, prune, enabled[8];
    float a0, inv_a, r0, inv_r;
    int detail_has_experts, spc, detail_ext, cp;
    float lapse;
    // red deserts
    int rd_lattice, rd_max_steps, rd_octaves, rd_stream, n_arch;
    double rd_period, rd_warp_period, rd_jitter_period, zone_cells;
    float rd_threshold, rd_softness, rd_warp_cells, rd_jitter;
    float rd_logp[8];
};

// The configuration of generator `g` (built on first use after a cache clear). Holds the pinned memory alive.
struct CfgRef {
    Fields keep;
    const Cfg* p = nullptr;
    const Cfg* operator->() const { return p; }
    const Cfg& operator*() const { return *p; }
};
CfgRef cfg(Gen& g);

// generator._hash32_int / synth.noise lowbias32 and the per-stream noise base (seed * 0x9E3779B1 + s * 0x632BE5AB).
inline uint32_t hash32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
inline uint32_t stream_base(int64_t seed, int64_t s) {
    const uint64_t v = static_cast<uint64_t>(seed) * 0x9E3779B1ULL + static_cast<uint64_t>(s) * 0x632BE5ABULL;
    return hash32(static_cast<uint32_t>(v & 0xFFFFFFFFULL));
}
// random.Random(seed * 0x9E3779B1 + salt).uniform(0, hi) (CPython's MT19937 seeding from an int, bit for bit).
double py_uniform(int64_t seed, int64_t salt, double hi);

// Same memory as t with a new shape (numel must match).
inline Tensor reshaped(const Tensor& t, int ndim, const int64_t* shape) {
    Tensor o = t;
    o.ndim = ndim;
    o.shape = {1, 1, 1, 1};
    int64_t n = 1;
    for (int i = 0; i < ndim; ++i) {
        o.shape[static_cast<size_t>(i)] = shape[i];
        n *= shape[i];
    }
    if (n != t.numel()) throw std::invalid_argument("reshape: numel differs");
    return o;
}

// Planes [first, first + count) of a contiguous (P, ...) tensor as one tensor of shape `shape` (shares memory).
inline Tensor planes(const Tensor& t, int64_t first, int64_t count, int ndim, const int64_t* shape) {
    const int64_t per = t.numel() / t.shape[0];
    Tensor o = t;
    o.ptr = static_cast<char*>(t.ptr) + static_cast<size_t>(first * per) * dtype_size(t.dtype);
    o.ndim = 1;
    o.shape = {count * per, 1, 1, 1};
    return reshaped(o, ndim, shape);
}

} // namespace sbtg::controls
