// Method "block_scale": river/scale.py BlockScale.from_world for the world in the host state, plus the WorldConfig /
// HeightCurve host code (world.hpp).
#include "stages/tiles/block_scale.hpp"
#include "stages/tiles/world.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>

namespace sbtg::tiles {

namespace {
int64_t bits(double v) {
    int64_t b;
    std::memcpy(&b, &v, sizeof b);
    return b;
}
} // namespace

std::vector<int64_t> World::key() const {
    std::vector<int64_t> k{world_height, sea_level, tile_size, min_y, curve_version};
    for (double v : {horizontal_m, downscale, ocean, lowland, midland, highland, lowland_top, highland_base, shore_blend,
                     midland_blend, highland_blend, shelf, shelf_base, shelf_blend, shallow, shallow_base,
                     shallow_blend})
        k.push_back(bits(v));
    for (char c : climate_layout) k.push_back(c);
    return k;
}

World world_of(const Gen& g) {
    if (!g.st.raw.has("world") || g.st.raw["world"].kind() != Json::Object)
        throw std::logic_error("no world config in the generator state (set_state {\"world\": WorldConfig.to_dict()})");
    const Json& j = g.st.raw["world"];
    World w;
    w.world_height = j["world_height"].i64();
    w.sea_level = j["sea_level"].i64();
    w.horizontal_m = j["horizontal_m"].num();
    w.downscale = j["downscale"].num();
    w.tile_size = j["tile_size"].i64();
    w.ocean = j["ocean_m_per_block"].num();
    w.lowland = j["lowland_m_per_block"].num();
    w.midland = j["midland_m_per_block"].num();
    w.highland = j["highland_m_per_block"].num();
    w.lowland_top = j["lowland_top_m"].num();
    w.highland_base = j["highland_base_m"].num();
    w.shore_blend = j["shore_blend_m"].num();
    w.midland_blend = j["midland_blend_m"].num();
    w.highland_blend = j["highland_blend_m"].num();
    w.min_y = j.i64_or("min_y", 0);
    w.climate_layout = j.str_or("climate_layout", "");
    w.curve_version = j.i64_or("curve_version", 1);
    w.shelf = j.num_or("shelf_m_per_block", 0.0);
    w.shelf_base = j.num_or("shelf_base_m", -200.0);
    w.shelf_blend = j.num_or("shelf_blend_m", 100.0);
    w.shallow = j.num_or("shallow_m_per_block", 0.0);
    w.shallow_base = j.num_or("shallow_base_m", -16.0);
    w.shallow_blend = j.num_or("shallow_blend_m", 8.0);
    if (!(w.downscale == 0.5 || (w.downscale >= 1 && w.downscale == std::floor(w.downscale))))
        throw std::invalid_argument("world downscale must be an integer >= 1 or 0.5");
    return w;
}

// ---------------------------------------------------------------- height curve (world/height_curve.py)
namespace {
double smoothstep(double t) {
    t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);                    // np.clip
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}
double blend(double below, double above, double e, double center, double hw) {
    return below + (above - below) * smoothstep((e - (center - hw)) / (2.0 * hw));
}
double rate(const World& w, double e) {
    double ocean = w.ocean;
    const double lo = w.lowland, hi = w.midland;
    const double span = w.highland_base - w.lowland_top;
    double ramp = lo + (hi - lo) * (e - w.lowland_top) / span;
    ramp = std::min(std::max(ramp, std::min(lo, hi)), std::max(lo, hi));
    if (w.shelf > 0.0) ocean = blend(ocean, w.shelf, e, w.shelf_base, w.shelf_blend);
    if (w.shallow > 0.0) ocean = blend(ocean, w.shallow, e, w.shallow_base, w.shallow_blend);
    double m = blend(ocean, w.lowland, e, 0.0, w.shore_blend);
    m = blend(m, ramp, e, w.lowland_top, w.midland_blend);
    m = blend(m, w.highland, e, w.highland_base, w.highland_blend);
    return m;
}
// np.arange(start, stop, step) for float arguments (numpy: length ceil((stop - start) / step), start + i * step).
std::vector<double> arange(double start, double stop, double step) {
    const double len = std::ceil((stop - start) / step);
    std::vector<double> v(static_cast<size_t>(std::max(0.0, len)));
    if (!v.empty()) v[0] = start;
    if (v.size() > 1) {
        const double delta = (start + step) - start;
        for (size_t i = 1; i < v.size(); ++i) v[i] = start + static_cast<double>(i) * delta;
    }
    return v;
}
void integrate(const World& w, double lo_m, double hi_m, std::vector<double>& elev, std::vector<double>& blocks) {
    elev = arange(lo_m, hi_m + 1.0, 1.0);
    const size_t n = elev.size();
    std::vector<double> inv(n);
    for (size_t i = 0; i < n; ++i) inv[i] = 1.0 / rate(w, elev[i]);
    blocks.assign(n, 0.0);
    double acc = 0.0;
    for (size_t k = 1; k < n; ++k) {
        const double x = 0.5 * (inv[k] + inv[k - 1]) * 1.0;
        acc = (k == 1) ? x : acc + x;
        blocks[k] = acc;
    }
    const double at0 = np_interp(0.0, elev, blocks);
    const double sea = static_cast<double>(w.sea_level);
    for (size_t k = 0; k < n; ++k) blocks[k] = blocks[k] - at0 + sea;
}
} // namespace

double np_interp(double x, const std::vector<double>& xp, const std::vector<double>& fp) {
    const size_t n = xp.size();
    if (std::isnan(x)) return x;
    if (x < xp[0]) return fp[0];
    if (x > xp[n - 1]) return fp[n - 1];
    // j: dx[j] <= x < dx[j + 1]
    size_t j = static_cast<size_t>(std::upper_bound(xp.begin(), xp.end(), x) - xp.begin()) - 1;
    if (j == n - 1) return fp[j];
    if (xp[j] == x) return fp[j];
    const double slope = (fp[j + 1] - fp[j]) / (xp[j + 1] - xp[j]);
    double r = slope * (x - xp[j]) + fp[j];
    if (std::isnan(r)) {
        r = slope * (x - xp[j + 1]) + fp[j + 1];
        if (std::isnan(r) && fp[j] == fp[j + 1]) r = fp[j];
    }
    return r;
}

void curve_table(const World& w, std::vector<double>& elev, std::vector<double>& blocks) {
    double lo_m = -20000.0, hi_m = 20000.0;
    integrate(w, lo_m, hi_m, elev, blocks);
    const double ymin = static_cast<double>(w.min_y), ytop = static_cast<double>(w.world_height - 1);
    while ((blocks.front() > ymin || blocks.back() < ytop) && hi_m - lo_m < 640000.0) {
        if (blocks.front() > ymin) lo_m *= 2.0;
        if (blocks.back() < ytop) hi_m *= 2.0;
        integrate(w, lo_m, hi_m, elev, blocks);
    }
    if (blocks.front() > ymin || blocks.back() < ytop)
        throw std::invalid_argument("height curve does not span the block column");
}

double World::sea_bed_floor_m() const {
    if (curve_version < 2) return -4000.0;
    std::vector<double> e, b;
    curve_table(*this, e, b);
    const double el = np_interp(static_cast<double>(min_y), b, e);
    return 2.0 * std::floor(std::min(el, -4000.0) / 2.0);
}

namespace {

// BlockScale.from_world: e = arange(sea_bed_floor, 9000, 2), y = curve.to_block_height_exact(e), both as fp32.
Fields block_scale(Gen& g, const Args& a) {
    need(a, 0, 0, 0, "block_scale");
    const World w = world_of(g);
    std::vector<int64_t> key{g.st.seed};
    for (int64_t v : w.key()) key.push_back(v);
    return g.cache("tiles.block_scale", 8).get_or(key, [&] {
        std::vector<double> te, tb;
        curve_table(w, te, tb);
        const std::vector<double> e = arange(w.sea_bed_floor_m(), 9000.0, 2.0);
        std::vector<float> ef(e.size()), yf(e.size());
        for (size_t i = 0; i < e.size(); ++i) {
            ef[i] = static_cast<float>(e[i]);
            yf[i] = static_cast<float>(np_interp(e[i], te, tb));
        }
        const int64_t n = static_cast<int64_t>(e.size());
        Fields f;
        f.set("elev", g.gpu.upload(ef.data(), DType::F32, {n}));
        f.set("blocks", g.gpu.upload(yf.data(), DType::F32, {n}));
        f.set("horizontal_m", g.gpu.upload(&w.horizontal_m, DType::F64, {1}));
        f.set("sea_level", g.gpu.upload(&w.sea_level, DType::I64, {1}));
        f.set("world_height", g.gpu.upload(&w.world_height, DType::I64, {1}));
        f.set("min_y", g.gpu.upload(&w.min_y, DType::I64, {1}));
        return f;
    });
}
SBTG_METHOD("block_scale", block_scale);

} // namespace
} // namespace sbtg::tiles
