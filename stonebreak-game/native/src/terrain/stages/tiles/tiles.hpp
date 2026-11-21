// stages/tiles internals: biomes.py, world/ocean.py and TileBuilder helpers shared by the stage's .cpp files.
#pragma once

#include "stages/common/util.hpp"
#include "stages/tiles/block_scale.hpp"
#include "stages/tiles/world.hpp"

namespace sbtg::tiles {

// A (SH, SW) source frame read at output (r, c) + (R0, C0) (kernel twin: tiles_biomes.cu Frame).
struct Frame {
    int SH, SW, R0, C0;
};

// biomes.py module constants (kernel twin: BiomeK).
struct BiomeK {
    float hyper_arid, snow_cover_we, snow_cover_tmax;
    float sw_min_moist, sw_inv_moist_span, sw_min_temp, sw_snow_margin, sw_max_reg, sw_max_elev, sw_dry, sw_wet_minus_dry;
    float soil0, inv_soil_span, soil_min, bog_minus_min;
    int16_t id_plains, id_snowy_plains, id_desert, id_swamp, id_forest, id_taiga, id_snowy_taiga, id_savanna,
        id_windswept, id_jungle, id_grove, id_snowy_slopes, id_frozen_peaks, id_stony_peaks, id_forest_sparse,
        id_taiga_sparse, id_snowy_taiga_sparse;
    int16_t ocean_frozen, ocean_cold, ocean_warm, ocean_normal;
};
BiomeK biome_k(Gen& g);   // from the manifest (cheap; rebuilt per call)

ci_kernel* kern(Gen& g, const char* name);   // a kernel of module "tiles_biomes"

// biomes._noise fields at h x w columns from (i0, j0), `step` apart: (nf, h, w) fp32; fields 0..4 = temp, temp fine,
// precip, snow, snow fine, 5 = swamp. Cached (Python memoises them too).
Tensor noise(Gen& g, int64_t i0, int64_t j0, int h, int w, int64_t step, int nf);
// The same fields at explicit fp32 coordinates xs (w) / ys (h) (cached by origin and size).
Tensor noise_at(Gen& g, double i0, double j0, int h, int w, double step, int nf, const Tensor* xs, const Tensor* ys);

// biomes._classify_biome over (h, w) outputs; elev / climate on frame fe, elev_padded from epad on frame fpad.
Tensor classify_biome(Gen& g, const Tensor& elev, const Tensor& clim, const Frame& fe, const Tensor& epad,
                      const Frame& fpad, int h, int w, int64_t i0, int64_t j0, double pixel_size_m);
// biomes.swamp_candidates over (h, w) outputs on frame f (elev, regional, climate share it).
Tensor swamp_candidates(Gen& g, const Tensor& elev, const Tensor& regional, const Tensor& clim, const Frame& f, int h,
                        int w, int64_t i0, int64_t j0, int64_t step);
Tensor majority(Gen& g, const Tensor& ids, int size);
Tensor smooth_mask(Gen& g, const Tensor& mask, int size);
// biomes.apply_swamps(biome, swamps[R0:R0+h, C0:C0+w]) in place.
void apply_swamps(Gen& g, Tensor& biome, const Tensor& swamps, int R0, int C0);
// FX4 alpine_zones in place (temp / tseason = planes 0 / 1 of clim; elev and clim on frame f).
void alpine_zones(Gen& g, Tensor& biome, const Tensor& elev, const Tensor& clim, const Frame& f, double i0, double j0,
                  double step);
// TileBuilder._red_deserts (biome (h, w) at samples from (i1, j1) of d native pixels; d_int: d is an integer).
Tensor red_deserts(Gen& g, const Tensor& biome, int64_t i1, int64_t j1, double d);

// models/biomenet.py classify (the engine's biome_<H>x<W> model): (h, w) int16 biome ids.
Tensor bn_classify(Gen& g, const Tensor& elev, const Tensor& climate, int64_t i0, int64_t j0);

// world/ocean.py
Tensor grow(Gen& g, const Tensor& seed, const Tensor& through, int64_t steps);
Tensor dilate(Gen& g, const Tensor& m, int r);
Tensor sea_cells(Gen& g, const Tensor& height_m, const Tensor& land);
Tensor land_cells(Gen& g, int64_t ci0, int64_t cj0, int64_t hc, int64_t wc);
Tensor sea_mask_for_samples(Gen& g, int64_t s1, int64_t t1, int64_t h, int64_t w, int64_t d);
// elev (fp32, frame) with lakes lifted: ocean.classify_elev(elev, sub & ~ocean_columns(sub & ~wet, sea)),
// sub = height < sea_level. Returns a new tensor.
Tensor lift_lakes(Gen& g, const Tensor& elev, const Tensor& height, const Tensor& wet, const Tensor& sea,
                  int64_t sea_level);

// small helpers
inline int64_t ci(const Gen& g, const char* name) { return g.manifest["constants"][name].i64(); }
float inv_f32(double v);   // torch "/ python scalar": the fp32 reciprocal of (float)v

} // namespace sbtg::tiles
