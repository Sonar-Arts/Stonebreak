// Surface channels of a tile (world/surface_channels.py + TileBuilder._surface).
#include "stages/tiles/surface.hpp"

namespace sbtg::tiles {

namespace {
struct EncodeArgs {
    const float* v;
    uint8_t* out;
    long long n;
    float offset, scale;
};
} // namespace

Tensor encode_channel(Gen& g, const std::string& name, const Tensor& values) {
    const Json& ch = g.manifest["tiles"]["channels"];
    if (!ch.has(name)) throw std::invalid_argument("unknown surface channel '" + name + "'");
    Tensor v = values.dtype == DType::F32 ? values : ops::cast(g.gpu, values, DType::F32);
    Tensor out = g.gpu.empty_shape(DType::U8, v.ndim, v.shape);
    EncodeArgs a{v.f32(), out.data<uint8_t>(), v.numel(), ch[name]["offset"].f32(), ch[name]["scale"].f32()};
    g.gpu.launch(g.gpu.kernel("tiles_tile", "tiles_encode"), v.numel(), a);
    return out;
}

Args surface_args(const SurfaceCtx& ctx) {
    return Args{{ctx.i1, ctx.j1, ctx.mb},
                {ctx.d},
                {ctx.ground, ctx.climate, ctx.regional, ctx.height, ctx.water, ctx.wet, ctx.biome}};
}

std::vector<std::pair<std::string, Tensor>> surface_channels(Gen& g, const World& w, const SurfaceCtx& ctx) {
    (void)w;
    std::vector<std::pair<std::string, Tensor>> out;
    const Json& prov = g.manifest["tiles"]["providers"];
    if (prov.size() == 0) return out;
    const Args a = surface_args(ctx);
    for (const Json& p : prov.items()) {
        Fields f = g.call("TileBuilder.surface." + p.str(), a);
        for (auto& [name, t] : f.items) {
            Tensor e = encode_channel(g, name, t);
            bool done = false;
            for (auto& [n, v] : out)
                if (n == name) {          // a later provider overwrites (Python dict: the first position stays)
                    v = e;
                    done = true;
                }
            if (!done) out.emplace_back(name, e);
        }
    }
    return out;
}

} // namespace sbtg::tiles

namespace sbtg::tiles {
namespace {
// SC.encode(CHANNELS[k].name, values): ints [k (position in surface_channels.CHANNELS)], tensors [values].
Fields m_encode(Gen& g, const Args& a) {
    need(a, 1, 0, 1, "SC.encode");
    const Json& order = g.manifest["tiles"]["channel_order"];
    return Fields::of(encode_channel(g, order[static_cast<size_t>(a.i[0])].str(), a.t[0]));
}
SBTG_METHOD("SC.encode", m_encode);
} // namespace
} // namespace sbtg::tiles
