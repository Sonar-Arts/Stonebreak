// stages/river: the river pipeline (terrain_slm/river/*) as generator methods.
//
//   "_flatten_swamps" (i1, j1; heights)           generator(2)._flatten_swamps: swamp_relief + the v2 coastal shelf
//   "river_field" (i1, j1, i2, j2)                generator.river_field: "0.<RiverField attr>", "1.<RiverContext ...>"
//   "river.columns" (i1, j1, i2, j2; d; [ground_fine])   gen.river.columns(*gen.river_field(frame), d, ground_fine)
//   "river.bank_net" (x)                          the learned bank model's forward (gen.banks(x)); fp32, not bitwise
//
// Every other method (native heights, swamp_relief_cells, drainage, block_scale, G2._shelf_remap) is reached through
// g.call(). Shared helpers of the stage files live here.
#pragma once

#include "stages/common/common.hpp"
#include "stages/tiles/block_scale.hpp"

#include <cmath>
#include <string>

namespace sbtg::river {

inline ci_kernel* K(Gen& g, const char* name) { return g.gpu.kernel("river_ops", name); }

inline Tensor mask(Gen& g, int64_t h, int64_t w) { return g.gpu.empty(DType::BOOL, {h, w}); }
inline Tensor f32(Gen& g, int64_t h, int64_t w) { return g.gpu.empty(DType::F32, {h, w}); }
inline Tensor i32(Gen& g, int64_t h, int64_t w) { return g.gpu.empty(DType::I32, {h, w}); }
inline Tensor i64(Gen& g, int64_t h, int64_t w) { return g.gpu.empty(DType::I64, {h, w}); }
inline uint8_t* u8p(const Tensor& t) { return static_cast<uint8_t*>(t.ptr); }

// A host scalar tensor (shape (1,)): Python's ints / floats in a flattened result.
inline Tensor host_i64(Gen& g, int64_t v) {
    Tensor t = g.gpu.empty(DType::I64, {1}, true);
    t.data<int64_t>()[0] = v;
    return t;
}
inline Tensor host_f64(Gen& g, double v) {
    Tensor t = g.gpu.empty(DType::F64, {1}, true);
    t.data<double>()[0] = v;
    return t;
}

// The world's BlockScale: tables from the "block_scale" method, scalars from the world in the host state (no device
// read) when the host sent one, else from the method's fields.
struct Scale {
    Fields raw;                 // the "block_scale" fields (the context output repeats them)
    scale::BlockScale s;
    double ppb() const { return s.px_per_block(); }
};
Scale get_scale(Gen& g);

// A hash of the host state (seed, world, scale, flags): cache keys of the stage.
int64_t state_key(const Gen& g);

// Key of the drainage D8 a native river_field left for river.columns (cache "river_d8").
Cache::Key d8_key(const Gen& g, int64_t i1, int64_t j1, int64_t i2, int64_t j2);

// Squared Euclidean distance (int32) to the nearest set pixel of mask m within R, else a large value.
Tensor edt2(Gen& g, const Tensor& m, int R);
// Chebyshev distance (int32) to the nearest pixel with m == target within cap, else cap + 1.
Tensor cheb(Gen& g, const Tensor& m, int target, int cap);
// csrc_river monotone_downstream: lv = where(line, level, inf) stepped `iters` times; returns where(line, lv, level).
Tensor monotone_downstream(Gen& g, const Tensor& level, const Tensor& line, const Tensor& flow, int iters);
// Any byte set (one device read).
bool any(Gen& g, const Tensor& m);

} // namespace sbtg::river
