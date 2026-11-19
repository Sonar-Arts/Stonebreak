#include "gen/gen.hpp"

#include <cstdio>
#include <cstdlib>

namespace sbtg {

Fields Cache::to(const Fields& f, bool host) {
    Fields o;
    for (auto& [n, t] : f.items) {
        if (t.host == host || t.mem == nullptr || t.mem->pool == nullptr) {
            o.items.emplace_back(n, t);   // already there, or borrowed memory: keep as is
        } else {
            o.items.emplace_back(n, host ? gpu_->to_host(t) : gpu_->to_device(t));
        }
    }
    return o;
}

void Cache::settle() {
    // GPU tier over its cap: its least recent entries spill to the host tier (Python's popitem(last=False) + _spill);
    // host tier over the rest of the cap: its least recent entries are dropped.
    const size_t host_cap = cap_ - gpu_cap_;
    while (gpu_order_.size() > gpu_cap_) {
        Key k = gpu_order_.back();
        gpu_order_.pop_back();
        Entry& e = map_.at(k);
        if (host_cap == 0) {
            map_.erase(k);
            continue;
        }
        e.v = to(e.v, true);
        e.gpu = false;
        host_order_.push_front(k);
        e.it = host_order_.begin();
        if (host_order_.size() > host_cap) {
            map_.erase(host_order_.back());
            host_order_.pop_back();
        }
    }
}

Fields Cache::get_or(const Key& k, const std::function<Fields()>& fn) {
    auto it = map_.find(k);
    if (it != map_.end()) {
        Entry& e = it->second;
        if (e.gpu) {
            gpu_order_.splice(gpu_order_.begin(), gpu_order_, e.it);
            return e.v;
        }
        host_order_.erase(e.it);
        e.v = to(e.v, false);
        e.gpu = true;
        gpu_order_.push_front(k);
        e.it = gpu_order_.begin();
        Fields v = e.v;
        settle();
        return v;
    }
    Fields v = fn();
    put(k, v);
    return v;
}

void Cache::put(const Key& k, Fields v) {
    auto it = map_.find(k);
    if (it != map_.end()) {
        Entry& e = it->second;
        (e.gpu ? gpu_order_ : host_order_).erase(e.it);
        map_.erase(it);
    }
    gpu_order_.push_front(k);
    map_.emplace(k, Entry{std::move(v), gpu_order_.begin(), true});
    settle();
}

Gen::Gen(Gpu& g, Json m) : gpu(g), manifest(std::move(m)) {}

Gen::~Gen() {
    for (auto& [n, mo] : models_)
        if (mo.second && mo.first != nullptr) gpu.ci.model_destroy(mo.first);
}

void Gen::route(const std::string& method, bool on) {
    if (method == "*") {
        route_all_ = on;
        excluded_.clear();
        if (!on) routed_.clear();
        return;
    }
    if (on) {
        if (MethodRegistry::all().count(method) == 0) throw std::invalid_argument("no native method '" + method + "'");
        routed_.insert(method);
        excluded_.erase(method);
    } else {
        routed_.erase(method);
        excluded_.insert(method);
    }
}

bool Gen::native(const std::string& method) const {
    if (MethodRegistry::all().count(method) == 0 || excluded_.count(method) != 0) return false;
    return route_all_ || routed_.count(method) != 0;
}

Fields Gen::call(const std::string& method, const Args& a) {
    std::lock_guard<std::recursive_mutex> guard(lock);
    struct Depth {
        Gen& g;
        explicit Depth(Gen& gg) : g(gg) { ++g.depth_; }
        ~Depth() {
            if (--g.depth_ == 0) {
                try {
                    g.trim_pool();
                } catch (...) {
                }
            }
        }
    } depth(*this);
    if (native(method)) {
        ++native_calls[method];
        return MethodRegistry::all().at(method)(*this, a);
    }
    if (!python) throw std::logic_error("method '" + method + "' is not native and there is no Python host");
    ++python_calls[method];
    return python(method, a);
}

void Gen::trim_pool() {
    // Torch-like: keep a working set cached (a tile's peak), hand the rest back. cuMemFree can synchronise, so only
    // well above the budget.
    const size_t budget = static_cast<size_t>(manifest.has("constants") && manifest["constants"].has("POOL_CACHE_MB")
                                                  ? manifest["constants"]["POOL_CACHE_MB"].i64()
                                                  : 768) << 20;
    if (gpu.pool.cached_bytes() > 2 * budget) gpu.pool.trim_to(budget);
}

const Tensor& Gen::taps(double sigma) {
    char key[64];
    std::snprintf(key, sizeof key, "%.17g", sigma);
    auto it = taps_.find(key);
    if (it != taps_.end()) return it->second;
    const Json& all = manifest["taps"];
    // The manifest keys taps by Python's repr(float(sigma)); try that form and the %.17g one.
    std::string k = key;
    if (!all.has(k)) {
        for (int p = 1; p <= 17; ++p) {
            char s[64];
            std::snprintf(s, sizeof s, "%.*g", p, sigma);
            if (std::strtod(s, nullptr) == sigma) {
                k = s;
                if (k.find_first_of(".en") == std::string::npos) k += ".0";
                break;
            }
        }
    }
    if (!all.has(k))
        throw std::out_of_range("no blur taps for sigma " + std::string(key) +
                                " in the manifest: add it to export/native_manifest.BLUR_SIGMAS");
    std::vector<float> v = all[k].f32s();
    Tensor t = gpu.upload(v.data(), DType::F32, {static_cast<int64_t>(v.size())});
    return taps_.emplace(key, std::move(t)).first->second;
}

Cache& Gen::cache(const std::string& name, size_t cap) {
    auto it = caches_.find(name);
    if (it != caches_.end()) return *it->second;
    // Python's GPU shares (generator.py / generator2.py _LRU(gpu, host)); everything else stays on the GPU.
    static const std::map<std::string, size_t> gpu_caps = {
        {"cells._windows", 768}, {"cells._hydro_windows", 16}, {"cells._drainage_in", 8},
        {"cells._relief_windows", 32}, {"_climate_windows", 32}, {"detail._detail_windows", 48},
        {"detail._sr_windows", 32}, {"detail._sr15_windows", 32}};
    auto g = gpu_caps.find(name);
    size_t gc = g != gpu_caps.end() ? g->second : cap;
    return *caches_.emplace(name, std::make_unique<Cache>(&gpu, cap, gc)).first->second;
}

void Gen::clear_caches() {
    for (auto& [n, c] : caches_) c->clear();
}

ci_model* Gen::model(const std::string& name) {
    auto it = models_.find(name);
    if (it != models_.end()) return it->second.first;
    if (!manifest.has("models") || !manifest["models"].has(name)) return nullptr;
    const Json& m = manifest["models"][name];
    ci_model_options o{};
    o.max_batch = static_cast<int32_t>(m.i64_or("max_batch", 1));
    o.contexts = 1;
    o.graphs = 1;
    std::string path = m["path"].str();
    if (!base_dir.empty() && !path.empty() && path[0] != '/' && path.find(':') == std::string::npos)
        path = base_dir + "/" + path;
    ci_model* h = gpu.ci.model_load(gpu.rt, path.c_str(), &o);
    if (h == nullptr) throw EngineError("ci_model_load(" + path + "): " + gpu.ci.last_error());
    models_[name] = {h, true};
    return h;
}

void Gen::warm_models() {
    if (!manifest.has("models")) return;
    for (auto& [name, m] : manifest["models"].members()) {
        ci_model* h = model(name);
        std::vector<uint32_t> masks{0};
        if (m.has("warm_masks")) {
            masks.clear();
            for (int64_t v : m["warm_masks"].i64s()) masks.push_back(static_cast<uint32_t>(v));
        }
        std::vector<int32_t> batches;
        for (int32_t b = 1; b <= static_cast<int32_t>(m.i64_or("max_batch", 1)); ++b) batches.push_back(b);
        if (gpu.ci.model_warmup(h, masks.data(), static_cast<int32_t>(masks.size()), batches.data(),
                                static_cast<int32_t>(batches.size())) != 0)
            throw EngineError("ci_model_warmup(" + name + "): " + gpu.ci.last_error());
    }
}

} // namespace sbtg
