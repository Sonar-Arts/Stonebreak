// C ABI of the standalone terrain pipeline (include/stonebreak/terrain.h, "terrain (ABI 2)"): the native generator
// with every method native, its own engine runtime and stream, no Python. Hosted by the game's JVM (FFM, phase 3) or
// by the Python service in native mode.
#include "stonebreak/terrain.h"

#include "gen/gen.hpp"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>

namespace sbtg {
void set_error(const std::string& msg);
} // namespace sbtg

using sbtg::DType;
using sbtg::Fields;
using sbtg::Tensor;

struct sbtg_tile {
    Tensor planes;                                       // host int16 (P, R, C)
    std::vector<std::pair<std::string, Tensor>> surface; // host uint8 (R, C)
};

struct sbtg_terrain {
    sbtg::Ci ci;
    ci_runtime* rt = nullptr;
    void* stream = nullptr;
    std::unique_ptr<sbtg::Gpu> gpu;
    std::unique_ptr<sbtg::Gen> gen;
    std::map<int32_t, sbtg::Json> worlds;
    std::map<int32_t, std::string> world_text;
    std::string info;
    std::string manifest_path;
    long long tiles = 0;
    std::mutex mu;

    ~sbtg_terrain() {
        if (gpu) {
            try {
                gpu->sync();
            } catch (...) {
            }
        }
        gen.reset();
        gpu.reset();
        if (stream != nullptr && ci.stream_destroy != nullptr) ci.stream_destroy(rt, stream);
        if (rt != nullptr && ci.runtime_destroy != nullptr) ci.runtime_destroy(rt);
    }
};

namespace {

int32_t fail(int32_t code, const std::string& msg) {
    sbtg::set_error(msg);
    return code;
}
int32_t translate(const std::exception& e) {
    if (dynamic_cast<const sbtg::EngineError*>(&e)) return fail(SBTG_ERR_ENGINE, e.what());
    if (dynamic_cast<const std::logic_error*>(&e) && !dynamic_cast<const std::invalid_argument*>(&e) &&
        !dynamic_cast<const std::out_of_range*>(&e))
        return fail(SBTG_ERR_STATE, e.what());
    return fail(SBTG_ERR_ARG, e.what());
}

std::string read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::invalid_argument("cannot read " + path);
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

std::string json_str(const std::string& s) {
    std::string o = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        o += c;
    }
    return o + "\"";
}

Tensor host_copy(sbtg::Gpu& g, const Tensor& t) { return t.host ? t : g.to_host(t); }

} // namespace

#if !defined(_WIN32)
#pragma GCC visibility push(default)
#endif
extern "C" {

sbtg_terrain* sbtg_terrain_open(const char* cenda_lib, const char* native_dir, const sbtg_terrain_options* options) {
    if (cenda_lib == nullptr || native_dir == nullptr) {
        sbtg::set_error("sbtg_terrain_open: null library or directory");
        return nullptr;
    }
    try {
        auto t = std::make_unique<sbtg_terrain>();
        t->ci.bind(cenda_lib);
        const int32_t device = options != nullptr ? options->device : 0;
        if (t->ci.cuda_available(device) == 0)
            throw sbtg::EngineError("no CUDA device " + std::to_string(device) + ": " + t->ci.last_error());
        ci_runtime_options ro{};
        ro.cache_dir = options != nullptr ? options->cache_dir : nullptr;
        ro.nvrtc_path = options != nullptr ? options->nvrtc_path : nullptr;
        t->rt = t->ci.runtime_create(device, &ro);
        if (t->rt == nullptr) throw sbtg::EngineError(std::string("ci_runtime_create: ") + t->ci.last_error());
        t->stream = t->ci.stream_create(t->rt);
        if (t->stream == nullptr) throw sbtg::EngineError(std::string("ci_stream_create: ") + t->ci.last_error());
        t->gpu = std::make_unique<sbtg::Gpu>(t->ci, t->rt, t->stream);
        t->manifest_path = std::string(native_dir) + "/manifest.json";
        t->gen = std::make_unique<sbtg::Gen>(*t->gpu, sbtg::Json::parse(read_file(t->manifest_path)));
        t->gen->base_dir = native_dir;
        t->gen->route("*", true);
        // Without Python every method a tile needs must be native: a model whose surface-channel providers (vegetation
        // / surface nodes) have no native port yet is refused here, so the host falls back to the Python service
        // instead of failing tiles.
        if (t->gen->manifest.has("tiles") && t->gen->manifest["tiles"].has("providers")) {
            for (const sbtg::Json& p : t->gen->manifest["tiles"]["providers"].items()) {
                const std::string m = "TileBuilder.surface." + p.str();
                if (!t->gen->native(m))
                    throw std::invalid_argument("surface provider '" + p.str() + "' has no native port (" + m + ")");
            }
        }
        std::ostringstream o;
        o << "{\"model_id\":" << json_str(t->gen->manifest.str_or("model_id", "")) << ",\"abi\":" << SBTG_ABI_VERSION
          << ",\"device\":" << device << ",\"manifest\":" << json_str(t->manifest_path) << ",\"methods\":[";
        bool first = true;
        for (auto& [n, fn] : sbtg::MethodRegistry::all()) {
            o << (first ? "" : ",") << json_str(n);
            first = false;
        }
        o << "]}";
        t->info = o.str();
        return t.release();
    } catch (const std::exception& e) {
        sbtg::set_error(e.what());
        return nullptr;
    }
}

void sbtg_terrain_close(sbtg_terrain* t) { delete t; }

const char* sbtg_terrain_info(sbtg_terrain* t) { return t == nullptr ? "" : t->info.c_str(); }

int32_t sbtg_terrain_warmup(sbtg_terrain* t) {
    if (t == nullptr) return fail(SBTG_ERR_ARG, "null terrain");
    try {
        std::lock_guard<std::mutex> lock(t->mu);
        t->gen->warm_models();
        t->gpu->sync();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_set_world(sbtg_terrain* t, int32_t index, const char* world_json) {
    if (t == nullptr || world_json == nullptr || index < 0) return fail(SBTG_ERR_ARG, "bad argument");
    try {
        std::lock_guard<std::mutex> lock(t->mu);
        t->worlds[index] = sbtg::Json::parse(world_json);
        t->world_text[index] = world_json;
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_tile(sbtg_terrain* t, int32_t world, int64_t seed, int32_t tile_x, int32_t tile_z, int32_t lod,
                          sbtg_tile** out) {
    if (t == nullptr || out == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    *out = nullptr;
    try {
        std::lock_guard<std::mutex> lock(t->mu);
        auto w = t->worlds.find(world);
        if (w == t->worlds.end()) throw std::invalid_argument("unknown world " + std::to_string(world));
        const sbtg::Json& wc = w->second;
        // TileBuilder.build's per-build generator state: seed, the world's climate layout (or the default), the
        // coastal shelf for height-curve v2 worlds, and the world itself (block scale, tile geometry).
        std::string layout = wc.str_or("climate_layout", "");
        if (layout.empty()) layout = t->gen->manifest.str_or("default_climate_layout", "belts");
        const bool shelf = wc.i64_or("curve_version", 1) >= 2;
        // BlockScale.px_per_block = horizontal_m / NATIVE_M (30 m model pixels), as the Python state carries it.
        char ppb[64];
        std::snprintf(ppb, sizeof ppb, "%.17g", wc["horizontal_m"].num() / 30.0);
        std::ostringstream st;
        st << "{\"seed\":" << seed << ",\"px_per_block\":" << ppb << ",\"climate_layout\":" << json_str(layout)
           << ",\"sea_shelf\":" << (shelf ? "true" : "false") << ",\"world\":" << t->world_text[world] << "}";
        {
            sbtg::Json j = sbtg::Json::parse(st.str());
            auto& s = t->gen->st;
            if (layout != s.climate_layout) t->gen->clear_caches();
            s.seed = seed;
            s.climate_layout = layout;
            s.sea_shelf = shelf;
            s.raw = j;
        }
        Fields f = t->gen->call("TileBuilder.build", {{seed, tile_x, tile_z, lod}, {}, {}});
        auto tile = std::make_unique<sbtg_tile>();
        tile->planes = host_copy(*t->gpu, sbtg::ops::cast(*t->gpu, f["planes"], DType::I16));
        for (auto& [name, v] : f.items)
            if (name.rfind("surface.", 0) == 0) tile->surface.emplace_back(name.substr(8), host_copy(*t->gpu, v));
        t->gpu->sync();
        ++t->tiles;
        *out = tile.release();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

const int16_t* sbtg_tile_planes(const sbtg_tile* tile, int32_t* planes, int32_t* rows, int32_t* cols) {
    if (tile == nullptr) return nullptr;
    const Tensor& p = tile->planes;
    if (planes != nullptr) *planes = static_cast<int32_t>(p.size(0));
    if (rows != nullptr) *rows = static_cast<int32_t>(p.size(1));
    if (cols != nullptr) *cols = static_cast<int32_t>(p.size(2));
    return static_cast<const int16_t*>(p.ptr);
}

int32_t sbtg_tile_surface_count(const sbtg_tile* tile) {
    return tile == nullptr ? 0 : static_cast<int32_t>(tile->surface.size());
}
const char* sbtg_tile_surface_name(const sbtg_tile* tile, int32_t i) {
    if (tile == nullptr || i < 0 || static_cast<size_t>(i) >= tile->surface.size()) return nullptr;
    return tile->surface[static_cast<size_t>(i)].first.c_str();
}
const uint8_t* sbtg_tile_surface(const sbtg_tile* tile, int32_t i) {
    if (tile == nullptr || i < 0 || static_cast<size_t>(i) >= tile->surface.size()) return nullptr;
    return static_cast<const uint8_t*>(tile->surface[static_cast<size_t>(i)].second.ptr);
}
void sbtg_tile_free(sbtg_tile* tile) { delete tile; }

int32_t sbtg_terrain_stats(sbtg_terrain* t, char* buf, int32_t cap) {
    if (t == nullptr) return 0;
    std::ostringstream o;
    o << "{\"tiles\":" << t->tiles << ",\"launches\":" << t->gpu->launches
      << ",\"pool_live\":" << t->gpu->pool.live_bytes() << ",\"pool_cached\":" << t->gpu->pool.cached_bytes() << "}";
    std::string s = o.str();
    if (buf != nullptr && cap > 0) {
        size_t k = std::min<size_t>(s.size(), static_cast<size_t>(cap - 1));
        std::memcpy(buf, s.data(), k);
        buf[k] = '\0';
    }
    return static_cast<int32_t>(s.size());
}

} // extern "C"
#if !defined(_WIN32)
#pragma GCC visibility pop
#endif
