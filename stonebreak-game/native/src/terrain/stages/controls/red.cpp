// Stage "controls" (part 3): generator.red_desert_cells (module function, registered as "G.red_desert_cells").
#include "stages/controls/controls.hpp"

#include <cstring>

namespace sbtg {
namespace {

constexpr int kMaxA = 8;
struct RedArgs {   // cuda/controls.cu RedArgs
    const float* ci;
    const float* cj;
    int64_t* nodes;
    float* frac;
    int64_t* box_ext;
    float* box;
    bool* out;
    int64_t n;
    int64_t bi, bj;
    int hi, hj;
    uint32_t warp_i_bases[4], warp_j_bases[4], jit_bases[4], zone_bases[kMaxA];
    float warp_invp[4], jit_invp[4];
    float zone_invp, inv_warp_norm, inv_jit_norm, zone_inv_norm;
    uint32_t red_base;
    float red_invp, threshold, softness, warp_cells, jitter, inv_lattice, inv6;
    float logp[kMaxA];
    int n_arch, warp_oct, jit_oct, zone_oct, max_steps, lattice, single;
};

float inv(double x) { return static_cast<float>(1.0 / x); }
float fbm_inv_norm(int oct) {   // generator._fbm: tot / norm, norm = 1 + 0.5 + ... (Python floats)
    double norm = 0.0, amp = 1.0;
    for (int o = 0; o < oct; ++o) {
        norm += amp;
        amp *= 0.5;
    }
    return inv(norm);
}

Fields red_desert_cells(Gen& g, const Args& a) {
    need(a, 0, 0, 2, "G.red_desert_cells");
    controls::CfgRef c = controls::cfg(g);
    const int64_t seed = g.st.seed;
    Tensor ci = a.t[0].dtype == DType::F32 ? a.t[0] : ops::cast(g.gpu, a.t[0], DType::F32);
    Tensor cj = a.t[1].dtype == DType::F32 ? a.t[1] : ops::cast(g.gpu, a.t[1], DType::F32);
    if (ci.host) ci = g.gpu.to_device(ci);
    if (cj.host) cj = g.gpu.to_device(cj);
    const int64_t n = ci.numel();
    if (cj.numel() != n) throw std::invalid_argument("G.red_desert_cells: ci / cj sizes differ");
    Tensor out = g.gpu.empty_shape(DType::BOOL, ci.ndim, ci.shape);
    if (n == 0) return Fields::of(out);
    if (c->n_arch > kMaxA) throw std::length_error("G.red_desert_cells: too many climate archetypes");

    RedArgs r;
    std::memset(&r, 0, sizeof r);
    const int L = c->rd_lattice, S = c->rd_stream;
    r.warp_oct = 4;   // generator.red_desert_cells: _fbm(..., octaves=4) warp, octaves=3 jitter
    r.jit_oct = 3;
    if (r.warp_oct > 4 || r.jit_oct > 4) throw std::logic_error("red desert octaves");
    for (int o = 0; o < r.warp_oct; ++o) {
        r.warp_i_bases[o] = controls::stream_base(seed, (S + 1) * 16 + o);
        r.warp_j_bases[o] = controls::stream_base(seed, (S + 2) * 16 + o);
        r.warp_invp[o] = inv(c->rd_warp_period / static_cast<double>(1 << o));
    }
    for (int o = 0; o < r.jit_oct; ++o) {
        r.jit_bases[o] = controls::stream_base(seed, (S + 3) * 16 + o);
        r.jit_invp[o] = inv(c->rd_jitter_period / static_cast<double>(1 << o));
    }
    if (c->rd_octaves != 1) throw std::logic_error("G.red_desert_cells: RED_DESERT_OCTAVES != 1 is not ported");
    r.zone_oct = 1;
    for (int k = 0; k < c->n_arch; ++k) r.zone_bases[k] = controls::stream_base(seed, (20 + k) * 16);
    r.zone_invp = inv(c->zone_cells);
    r.zone_inv_norm = fbm_inv_norm(1);
    r.inv_warp_norm = fbm_inv_norm(r.warp_oct);
    r.inv_jit_norm = fbm_inv_norm(r.jit_oct);
    r.red_base = controls::stream_base(seed, S);
    r.red_invp = inv(c->rd_period);
    r.threshold = c->rd_threshold;
    r.softness = c->rd_softness;
    r.warp_cells = c->rd_warp_cells;
    r.jitter = c->rd_jitter;
    r.inv_lattice = inv(L);
    r.inv6 = inv(6.0);
    for (int k = 0; k < c->n_arch; ++k) r.logp[k] = c->rd_logp[k];
    r.n_arch = c->n_arch;
    r.max_steps = c->rd_max_steps;
    r.lattice = L;
    r.single = n == 1 ? 1 : 0;

    Gpu& gpu = g.gpu;
    Tensor nodes = gpu.empty(DType::I64, {2, n}), frac = gpu.empty(DType::F32, {2, n});
    Tensor ext = gpu.empty(DType::I64, {4});
    r.ci = ci.f32();
    r.cj = cj.f32();
    r.nodes = nodes.i64();
    r.frac = frac.f32();
    r.box_ext = ext.i64();
    r.out = static_cast<bool*>(out.ptr);
    r.n = n;
    gpu.launch(gpu.kernel("controls", "controls_red_prep"), n, r);
    gpu.launch_grid(gpu.kernel("controls", "controls_red_extent"), 1, 1, 1, 256, r);
    // the node box (the one host sync: its size depends on the data, as in the Python)
    Tensor h = gpu.to_host(ext);
    gpu.sync();
    const int64_t* e = h.i64();
    r.bi = e[0] - 1;
    r.bj = e[1] - 1;
    const int64_t hi = e[2] + 3 - r.bi, hj = e[3] + 3 - r.bj;
    if (hi * hj > (int64_t{1} << 28)) throw std::length_error("G.red_desert_cells: node box too large");
    r.hi = static_cast<int>(hi);
    r.hj = static_cast<int>(hj);
    Tensor box = gpu.empty(DType::F32, {hi, hj});
    r.box = box.f32();
    gpu.launch(gpu.kernel("controls", "controls_red_walk"), hi * hj, r, 128);
    gpu.launch(gpu.kernel("controls", "controls_red_blend"), n, r);
    return Fields::of(out);
}
SBTG_METHOD("G.red_desert_cells", red_desert_cells);

} // namespace
} // namespace sbtg
