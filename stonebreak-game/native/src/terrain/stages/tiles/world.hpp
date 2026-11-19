// world/world_config.py WorldConfig, read from the host state (g.st.raw["world"] = WorldConfig.to_dict()), and the
// world/height_curve.py curve integrated on the host in fp64 (numpy's operation order; built with -ffp-contract=off).
#pragma once

#include "stages/common/util.hpp"

#include <string>
#include <vector>

namespace sbtg::tiles {

struct World {
    int64_t world_height = 256, sea_level = 64, tile_size = 256, min_y = 0, curve_version = 1;
    double horizontal_m = 60.0, downscale = 2.0;
    double ocean = 48, lowland = 16, midland = 24, highland = 38, lowland_top = 600, highland_base = 2000,
           shore_blend = 60, midland_blend = 150, highland_blend = 300;
    double shelf = 0.0, shelf_base = -200.0, shelf_blend = 100.0;
    double shallow = 0.0, shallow_base = -16.0, shallow_blend = 8.0;
    std::string climate_layout;

    // Every number of the config (cache keys: two worlds with the same key build the same tiles).
    std::vector<int64_t> key() const;
    double sea_bed_floor_m() const;
};

// The world of the current state; throws when the host sent none.
World world_of(const Gen& g);

// HeightCurve tables (_curve_table): elevation grid (1 m) and block heights, fp64.
void curve_table(const World& w, std::vector<double>& elev, std::vector<double>& blocks);
// numpy.interp(x, xp, fp) for one x (xp increasing).
double np_interp(double x, const std::vector<double>& xp, const std::vector<double>& fp);

} // namespace sbtg::tiles
