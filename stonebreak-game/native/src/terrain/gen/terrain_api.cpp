// C ABI of the standalone terrain pipeline (include/stonebreak/terrain.h, "terrain (ABI 2)"): the native generator
// with every method native, its own engine runtime and stream, no Python. Hosted by the game's JVM (FFM, phase 3) or
// by the Python service in native mode.
#include "stonebreak/terrain.h"

#include "gen/gen.hpp"

#include <atomic>
#include <cstdio>
#include <cstdlib>
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

// One lane of the pipeline (agent PX, 2026-11-19): a generator on its own stream and memory pool. Lanes build tiles
// concurrently (one thread each); they share the engine runtime, the models (one execution context per lane) and the
// window caches (sbtg::Shared), so a window is computed once whichever lane needs it first, and the GPU stays busy
// while another lane waits on the host (routing, guards, readbacks). Lane 0 always exists.
struct sbtg_lane {
    void* stream = nullptr;
    std::unique_ptr<sbtg::Gpu> gpu;
    std::unique_ptr<sbtg::Gen> gen;
    std::mutex mu;
    std::atomic<long long> tiles{0};
};

struct sbtg_terrain {
    sbtg::Ci ci;
    ci_runtime* rt = nullptr;
    std::vector<std::unique_ptr<sbtg_lane>> lanes;
    std::shared_ptr<sbtg::Shared> shared;    // null: one lane
    std::string manifest_text;
    std::string native_dir;
    std::map<int32_t, sbtg::Json> worlds;
    std::map<int32_t, std::string> world_text;
    std::mutex wmu;                          // worlds
    std::mutex mu;                           // lane layout (set_lanes) + warm-up + release
    bool warmed = false;
    std::shared_ptr<sbtg::HostBudget> budget = std::make_shared<sbtg::HostBudget>();   // every lane's caches
    size_t pool_host_keep = size_t{128} << 20;
    std::atomic<long long> releases{0};
    std::string info;
    std::string manifest_path;

    ~sbtg_terrain() {
        for (auto& l : lanes) {
            if (l && l->gpu) {
                try {
                    l->gpu->sync();
                } catch (...) {
                }
            }
        }
        for (auto& l : lanes)
            if (l) l->gen.reset();
        shared.reset();                       // shared caches (blocks of every lane's pool) and the models
        for (auto& l : lanes)
            if (l) l->gpu.reset();
        for (auto& l : lanes)
            if (l && l->stream != nullptr && ci.stream_destroy != nullptr) ci.stream_destroy(rt, l->stream);
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

// A lane: its stream, pool and generator (the manifest parsed again: a lane's generator state is its own).
std::unique_ptr<sbtg_lane> make_lane(sbtg_terrain& t, int id) {
    auto l = std::make_unique<sbtg_lane>();
    l->stream = t.ci.stream_create(t.rt);
    if (l->stream == nullptr) throw sbtg::EngineError(std::string("ci_stream_create: ") + t.ci.last_error());
    l->gpu = std::make_unique<sbtg::Gpu>(t.ci, t.rt, l->stream);
    l->gpu->pool.lane = id;
    l->gen = std::make_unique<sbtg::Gen>(*l->gpu, sbtg::Json::parse(t.manifest_text));
    l->gen->base_dir = t.native_dir;
    l->gen->lane = id;
    l->gen->host_budget = t.budget;
    l->gen->pool_host_keep = t.pool_host_keep;
    l->gen->route("*", true);
    return l;
}

// Lanes 1..n-1 (and the shared state) on a pipeline that has only lane 0 and has not loaded a model yet.
void set_lanes(sbtg_terrain& t, int n) {
    if (n < 1 || n > 8) throw std::invalid_argument("lanes must be 1..8");
    if (static_cast<int>(t.lanes.size()) == n) return;
    if (t.lanes.size() != 1 || t.warmed || t.lanes[0]->tiles != 0)
        throw std::logic_error("sbtg_terrain_set_lanes: only once, before the warm-up and the first tile");
    if (n == 1) return;
    auto sh = std::make_shared<sbtg::Shared>(n);
    sh->ci = &t.ci;
    sh->streams.streams.push_back(t.lanes[0]->stream);
    for (int i = 1; i < n; ++i) {
        t.lanes.push_back(make_lane(t, i));
        sh->streams.streams.push_back(t.lanes.back()->stream);
    }
    for (auto& l : t.lanes) {
        l->gen->shared = sh;
        l->gpu->pool.lanes = &sh->streams;
    }
    t.shared = sh;
}

int32_t build_tile(sbtg_terrain* t, int32_t lane, int32_t world, int64_t seed, int32_t tile_x, int32_t tile_z,
                   int32_t lod, sbtg_tile** out);

} // namespace

namespace {
int32_t build_tile(sbtg_terrain* t, int32_t lane, int32_t world, int64_t seed, int32_t tile_x, int32_t tile_z,
                   int32_t lod, sbtg_tile** out) {
    if (t == nullptr || out == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    *out = nullptr;
    if (lane < 0 || static_cast<size_t>(lane) >= t->lanes.size())
        return fail(SBTG_ERR_ARG, "no lane " + std::to_string(lane) + " (sbtg_terrain_set_lanes)");
    try {
        sbtg_lane& L = *t->lanes[static_cast<size_t>(lane)];
        std::lock_guard<std::mutex> lock(L.mu);
        sbtg::Gen& gen = *L.gen;
        sbtg::Gpu& gpu = *L.gpu;
        sbtg::Json wc;
        std::string wtext;
        {
            std::lock_guard<std::mutex> wl(t->wmu);
            auto w = t->worlds.find(world);
            if (w == t->worlds.end()) throw std::invalid_argument("unknown world " + std::to_string(world));
            wc = w->second;
            wtext = t->world_text[world];
        }
        // TileBuilder.build's per-build generator state: seed, the world's climate layout (or the default), the
        // coastal shelf for height-curve v2 worlds, and the world itself (block scale, tile geometry).
        std::string layout = wc.str_or("climate_layout", "");
        if (layout.empty()) layout = gen.manifest.str_or("default_climate_layout", "belts");
        const bool shelf = wc.i64_or("curve_version", 1) >= 2;
        // BlockScale.px_per_block = horizontal_m / NATIVE_M (30 m model pixels), as the Python state carries it.
        char ppb[64];
        std::snprintf(ppb, sizeof ppb, "%.17g", wc["horizontal_m"].num() / 30.0);
        std::ostringstream st;
        st << "{\"seed\":" << seed << ",\"px_per_block\":" << ppb << ",\"climate_layout\":" << json_str(layout)
           << ",\"sea_shelf\":" << (shelf ? "true" : "false") << ",\"world\":" << wtext << "}";
        {
            sbtg::Json j = sbtg::Json::parse(st.str());
            auto& s = gen.st;
            if (layout != s.climate_layout) gen.clear_caches();
            s.seed = seed;
            s.climate_layout = layout;
            s.sea_shelf = shelf;
            s.raw = j;
        }
        Fields f = gen.call("TileBuilder.build", {{seed, tile_x, tile_z, lod}, {}, {}});
        auto tile = std::make_unique<sbtg_tile>();
        tile->planes = host_copy(gpu, sbtg::ops::cast(gpu, f["planes"], DType::I16));
        for (auto& [name, v] : f.items)
            if (name.rfind("surface.", 0) == 0) tile->surface.emplace_back(name.substr(8), host_copy(gpu, v));
        gpu.sync();
        ++L.tiles;
        *out = tile.release();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}
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
        if (options != nullptr && options->host_cache_bytes > 0)
            t->budget->limit = static_cast<size_t>(options->host_cache_bytes);
        if (options != nullptr && options->pool_host_keep_bytes > 0)
            t->pool_host_keep = static_cast<size_t>(options->pool_host_keep_bytes);
        t->manifest_path = std::string(native_dir) + "/manifest.json";
        t->manifest_text = read_file(t->manifest_path);
        t->native_dir = native_dir;
        t->lanes.push_back(make_lane(*t, 0));
        sbtg::Gen* gen0 = t->lanes[0]->gen.get();
        // Without Python every method a tile needs must be native: a model whose surface-channel providers (vegetation
        // / surface nodes) have no native port yet is refused here, so the host falls back to the Python service
        // instead of failing tiles.
        if (gen0->manifest.has("tiles") && gen0->manifest["tiles"].has("providers")) {
            for (const sbtg::Json& p : gen0->manifest["tiles"]["providers"].items()) {
                const std::string m = "TileBuilder.surface." + p.str();
                if (!gen0->native(m))
                    throw std::invalid_argument("surface provider '" + p.str() + "' has no native port (" + m + ")");
            }
        }
        std::ostringstream o;
        // $SBTG_LANES: the lane count when the host does not set one (sbtg_terrain_set_lanes).
        if (const char* e = std::getenv("SBTG_LANES")) set_lanes(*t, std::max(1, std::atoi(e)));
        o << "{\"model_id\":" << json_str(gen0->manifest.str_or("model_id", "")) << ",\"abi\":" << SBTG_ABI_VERSION
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
        std::lock_guard<std::mutex> l0(t->lanes[0]->mu);
        t->lanes[0]->gen->warm_models();   // the models are shared: lane 0 loads them (a context per lane)
        t->lanes[0]->gpu->sync();
        t->warmed = true;
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_release(sbtg_terrain* t) {
    if (t == nullptr) return fail(SBTG_ERR_ARG, "null terrain");
    try {
        std::lock_guard<std::mutex> lock(t->mu);
        std::vector<std::unique_lock<std::mutex>> held;   // every lane idle (waits for tiles in progress)
        for (auto& l : t->lanes) held.emplace_back(l->mu);
        for (auto& l : t->lanes) l->gpu->sync();
        for (auto& l : t->lanes) {   // all caches first (shared windows hold blocks of every lane's pool) ...
            std::lock_guard<std::recursive_mutex> g(l->gen->lock);
            l->gen->clear_all_caches();
        }
        for (auto& l : t->lanes) {   // ... then every pool gives its blocks back
            l->gpu->sync();
            l->gpu->pool.trim();
        }
        ++t->releases;
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_trim(sbtg_terrain* t) {
    if (t == nullptr) return fail(SBTG_ERR_ARG, "null terrain");
    try {
        for (auto& l : t->lanes) {   // one lane at a time: the others keep building
            std::lock_guard<std::mutex> g(l->mu);
            l->gpu->sync();
            l->gpu->pool.trim();
        }
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_set_world(sbtg_terrain* t, int32_t index, const char* world_json) {
    if (t == nullptr || world_json == nullptr || index < 0) return fail(SBTG_ERR_ARG, "bad argument");
    try {
        std::lock_guard<std::mutex> lock(t->wmu);
        t->worlds[index] = sbtg::Json::parse(world_json);
        t->world_text[index] = world_json;
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_tile(sbtg_terrain* t, int32_t world, int64_t seed, int32_t tile_x, int32_t tile_z, int32_t lod,
                          sbtg_tile** out) {
    return build_tile(t, 0, world, seed, tile_x, tile_z, lod, out);
}

int32_t sbtg_terrain_tile_lane(sbtg_terrain* t, int32_t lane, int32_t world, int64_t seed, int32_t tile_x,
                               int32_t tile_z, int32_t lod, sbtg_tile** out) {
    return build_tile(t, lane, world, seed, tile_x, tile_z, lod, out);
}

int32_t sbtg_terrain_set_lanes(sbtg_terrain* t, int32_t lanes) {
    if (t == nullptr) return fail(SBTG_ERR_ARG, "null terrain");
    try {
        std::lock_guard<std::mutex> lock(t->mu);
        set_lanes(*t, lanes);
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_terrain_lanes(sbtg_terrain* t) { return t == nullptr ? 0 : static_cast<int32_t>(t->lanes.size()); }

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
    // Totals over the lanes; a lane busy with a tile is skipped for the per-method maps (never blocks a status call).
    long long tiles = 0, launches = 0;
    size_t live = 0, cached = 0, host_live = 0, host_cached = 0;
    std::map<std::string, long long> counters, native;
    std::map<std::string, double> prof;
    std::vector<long long> per_lane;
    bool profile = false;
    for (auto& lp : t->lanes) {
        sbtg_lane& L = *lp;
        std::unique_lock<std::mutex> l(L.mu, std::try_to_lock);
        tiles += L.tiles;
        per_lane.push_back(L.tiles);
        if (!l.owns_lock()) continue;
        launches += L.gpu->launches;
        live += L.gpu->pool.live_bytes();
        cached += L.gpu->pool.cached_bytes();
        host_live += L.gpu->pool.live_host_bytes();
        host_cached += L.gpu->pool.cached_host_bytes();
        for (auto& [n, v] : L.gen->counters) counters[n] += v;
        for (auto& [n, v] : L.gen->native_calls) native[n] += v;
        for (auto& [n, v] : L.gen->prof_s) prof[n] += v;
        profile = profile || L.gen->profile > 0;
    }
    std::ostringstream o;
    o << "{\"tiles\":" << tiles << ",\"lanes\":" << t->lanes.size() << ",\"lane_tiles\":[";
    for (size_t i = 0; i < per_lane.size(); ++i) o << (i ? "," : "") << per_lane[i];
    o << "],\"launches\":" << launches << ",\"pool_live\":" << live << ",\"pool_cached\":" << cached
      << ",\"pool_host_live\":" << host_live << ",\"pool_host_cached\":" << host_cached
      << ",\"host_cache_bytes\":" << t->budget->used.load() << ",\"host_cache_limit\":" << t->budget->limit
      << ",\"host_evictions\":" << t->budget->evictions.load() << ",\"releases\":" << t->releases.load();
    auto dump = [&o](const char* name, const auto& m) {
        o << ",\"" << name << "\":{";
        bool first = true;
        for (auto& [n, v] : m) {
            o << (first ? "" : ",") << json_str(n) << ":" << v;
            first = false;
        }
        o << "}";
    };
    dump("counters", counters);
    dump("native", native);
    if (profile) dump("prof_s", prof);
    o << "}";
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
