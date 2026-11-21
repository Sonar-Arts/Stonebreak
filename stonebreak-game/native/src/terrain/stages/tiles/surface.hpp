// world/surface_channels.py (TGMPipe v3 surface channels) and the TileBuilder's surface providers.
//
// A provider = (Gen&, const SurfaceCtx&) -> named fp32 planes over the tile (rows, cols), NaN = no data, in the
// Python builder's order (TileBuilder.surface_providers: vegetation_node, surface_node); the values are encoded with the
// channel table's scale / offset (manifest "tiles.channels", from surface_channels.CHANNELS) into uint8, 255 = no data.
#pragma once

#include "stages/tiles/tiles.hpp"

#include <utility>

namespace sbtg::tiles {

// The block frame of a build (TileBuilder._surface_ctx): tile + BLOCK_MARGIN.
struct SurfaceCtx {
    double d = 0;              // native pixels per block (0.5 at 1:1)
    int64_t i1 = 0, j1 = 0;    // the tile's first block
    int mb = 0;                // BLOCK_MARGIN
    Tensor ground;             // fp32: native pixels of the frame (1:1: per 15 m block)
    Tensor climate;            // fp32 (C, frame) per block
    Tensor regional;           // fp32 frame per block
    Tensor height, water;      // int64 frame (block units)
    Tensor wet;                // bool frame
    Tensor biome;              // int16 tile (rows, cols)
};

// Provider `name` of the build (manifest "tiles.providers", the Python builder's order) is the generator method
// "TileBuilder.surface.<name>": ints [i1, j1, mb], floats [d], tensors [ground, climate, regional, height, water, wet,
// biome] -> {channel: fp32 (rows, cols), NaN = no data}. Native when a stage registers it, else the Python provider.
Args surface_args(const SurfaceCtx& ctx);

// Every provider's channels for the build, encoded: [(channel name, uint8 (rows, cols))].
std::vector<std::pair<std::string, Tensor>> surface_channels(Gen& g, const World& w, const SurfaceCtx& ctx);
// surface_channels.encode(name, values) on the device.
Tensor encode_channel(Gen& g, const std::string& name, const Tensor& values);

} // namespace sbtg::tiles
