#include "gen/gen.hpp"

#include <chrono>
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

void Cache::mark(const Fields& f) const {
    if (!core_->shared_) return;
    for (auto& [n, t] : f.items)
        if (t.mem && t.mem->pool != nullptr && t.mem->pool->lane != lane_)
            t.mem->foreign.fetch_or(1u << lane_, std::memory_order_relaxed);
}

std::shared_ptr<CiEvent> Cache::record() const {
    if (!core_->shared_) return nullptr;
    auto e = std::make_shared<CiEvent>();
    e->ci = &gpu_->ci;
    e->rt = gpu_->rt;
    e->ev = gpu_->ci.event_create(gpu_->rt);
    if (e->ev == nullptr || gpu_->ci.event_record(gpu_->rt, e->ev, gpu_->stream) != 0) {
        gpu_->sync();   // no event: the value is complete once this stream is idle
        return nullptr;
    }
    return e;
}

void Cache::adopt(CacheCore::Entry& e) {
    if (!core_->shared_ || e.lane == lane_) return;
    if (e.ev) gpu_->check(gpu_->ci.stream_wait(gpu_->rt, gpu_->stream, e.ev->ev), "ci_stream_wait");
    mark(e.v);
}

void Cache::settle(std::unique_lock<std::mutex>&) {
    // GPU tier over its cap: its least recent entries spill to the host tier (Python's popitem(last=False) + _spill);
    // host tier over the rest of the cap: its least recent entries are dropped. The copies run on this lane's stream.
    CacheCore& c = *core_;
    const size_t host_cap = c.cap_ - c.gpu_cap_;
    while (c.gpu_order_.size() > c.gpu_cap_) {
        Key k = c.gpu_order_.back();
        c.gpu_order_.pop_back();
        CacheCore::Entry& e = c.map_.at(k);
        if (host_cap == 0) {
            c.map_.erase(k);
            continue;
        }
        adopt(e);
        e.v = to(e.v, true);
        e.lane = lane_;
        e.ev = record();
        e.gpu = false;
        c.host_order_.push_front(k);
        e.it = c.host_order_.begin();
        if (c.host_order_.size() > host_cap) {
            c.map_.erase(c.host_order_.back());
            c.host_order_.pop_back();
        }
    }
}

bool Cache::contains(const Key& k) const {
    std::lock_guard<std::mutex> l(core_->mu_);
    return core_->map_.count(k) != 0;
}

size_t Cache::size() const {
    std::lock_guard<std::mutex> l(core_->mu_);
    return core_->map_.size();
}

void Cache::clear() {
    std::lock_guard<std::mutex> l(core_->mu_);
    CacheCore& c = *core_;
    for (auto it = c.map_.begin(); it != c.map_.end();) {   // pending entries stay: their lanes fill them
        if (it->second.ready) it = c.map_.erase(it);
        else ++it;
    }
    c.gpu_order_.clear();
    c.host_order_.clear();
}

bool Cache::claim(const Key& k) {
    std::lock_guard<std::mutex> l(core_->mu_);
    auto [it, fresh] = core_->map_.try_emplace(k);
    if (!fresh) return false;
    it->second.owner = lane_;
    return true;
}

void Cache::release(const Key& k) {
    {
        std::lock_guard<std::mutex> l(core_->mu_);
        auto it = core_->map_.find(k);
        if (it != core_->map_.end() && !it->second.ready && it->second.owner == lane_) core_->map_.erase(it);
    }
    core_->cv_.notify_all();
}

Fields Cache::get_or(const Key& k, const std::function<Fields()>& fn) {
    CacheCore& c = *core_;
    std::unique_lock<std::mutex> l(c.mu_);
    for (;;) {
        auto it = c.map_.find(k);
        if (it == c.map_.end()) {
            c.map_[k].owner = lane_;   // pending: other lanes wait for this one
            l.unlock();
            Fields v;
            try {
                v = fn();
            } catch (...) {
                release(k);
                throw;
            }
            put(k, v);
            return v;
        }
        CacheCore::Entry& e = it->second;
        if (e.ready) {
            if (e.gpu) {
                c.gpu_order_.splice(c.gpu_order_.begin(), c.gpu_order_, e.it);
                adopt(e);
                return e.v;
            }
            c.host_order_.erase(e.it);
            adopt(e);
            e.v = to(e.v, false);
            e.lane = lane_;
            e.ev = record();
            e.gpu = true;
            c.gpu_order_.push_front(k);
            e.it = c.gpu_order_.begin();
            Fields v = e.v;
            settle(l);
            return v;
        }
        if (e.owner == lane_) {   // re-entrant need of a key this lane is computing: compute it here too
            l.unlock();
            Fields v = fn();
            put(k, v);
            return v;
        }
        c.cv_.wait(l);
    }
}

void Cache::put(const Key& k, Fields v) {
    CacheCore& c = *core_;
    std::shared_ptr<CiEvent> ev = record();
    {
        std::unique_lock<std::mutex> l(c.mu_);
        auto it = c.map_.find(k);
        if (it != c.map_.end() && it->second.ready) (it->second.gpu ? c.gpu_order_ : c.host_order_).erase(it->second.it);
        CacheCore::Entry& e = c.map_[k];
        e.v = std::move(v);
        e.ready = true;
        e.owner = -1;
        e.gpu = true;
        e.lane = lane_;
        e.ev = std::move(ev);
        c.gpu_order_.push_front(k);
        e.it = c.gpu_order_.begin();
        settle(l);
    }
    c.cv_.notify_all();
}

Shared::~Shared() {
    for (auto& [n, m] : models)
        if (m != nullptr && ci != nullptr) ci->model_destroy(m);
}

bool Shared::shares(const std::string& name) {
    static const std::set<std::string> names = {
        "cells._windows", "cells._windows_h", "cells._relief_windows", "cells._drainage_in", "cells._route_windows",
        "cells._hydro_windows", "_climate_windows", "detail._detail_windows", "detail._sr_windows",
        "detail._sr_windows_look", "detail._sr15_windows", "detail._sr15_windows_look"};
    return names.count(name) != 0;
}

Gen::Gen(Gpu& g, Json m) : gpu(g), manifest(std::move(m)) {
    if (const char* p = std::getenv("SBTG_PROFILE")) profile = std::atoi(p);
}

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
        if (profile > 0) {
            if (profile > 1) gpu.sync();
            const auto t0 = std::chrono::steady_clock::now();
            Fields r = MethodRegistry::all().at(method)(*this, a);
            if (profile > 1) gpu.sync();
            prof_s[method] += std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
            return r;
        }
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
    const bool sh = shared && Shared::shares(name);
    const std::string key = sh ? name + "|" + st.climate_layout : name;
    auto it = caches_.find(key);
    if (it != caches_.end()) return *it->second;
    // Python's GPU shares (generator.py / generator2.py _LRU(gpu, host)); everything else stays on the GPU.
    static const std::map<std::string, size_t> gpu_caps = {
        {"cells._windows", 768}, {"cells._hydro_windows", 16}, {"cells._drainage_in", 8},
        {"cells._relief_windows", 32}, {"_climate_windows", 32}, {"detail._detail_windows", 48},
        {"detail._sr_windows", 32}, {"detail._sr15_windows", 32}};
    auto g = gpu_caps.find(name);
    size_t gc = g != gpu_caps.end() ? g->second : cap;
    if (sh) {   // one core per (name, climate layout) for every lane: layouts never mix, so nothing is cleared
        std::lock_guard<std::mutex> l(shared->mu);
        auto& core = shared->cores[key];
        if (!core) core = std::make_shared<CacheCore>(cap, gc, true);
        return *caches_.emplace(key, std::make_unique<Cache>(core, &gpu, lane)).first->second;
    }
    return *caches_.emplace(key, std::make_unique<Cache>(&gpu, cap, gc)).first->second;
}

void Gen::clear_caches() {
    for (auto& [n, c] : caches_)
        if (!c->shared()) c->clear();
}

ci_model* Gen::model(const std::string& name) {
    auto it = models_.find(name);
    if (it != models_.end()) return it->second.first;
    if (!manifest.has("models") || !manifest["models"].has(name)) return nullptr;
    std::unique_lock<std::mutex> shl;
    if (shared) {   // lanes: one model per pipeline with an execution context per lane
        shl = std::unique_lock<std::mutex>(shared->mu);
        auto s = shared->models.find(name);
        if (s != shared->models.end()) {
            models_[name] = {s->second, false};
            return s->second;
        }
    }
    const Json& m = manifest["models"][name];
    ci_model_options o{};
    o.max_batch = static_cast<int32_t>(m.i64_or("max_batch", 1));
    o.contexts = shared ? shared->lanes : 1;
    o.graphs = 1;
    std::string path = m["path"].str();
    if (!base_dir.empty() && !path.empty() && path[0] != '/' && path.find(':') == std::string::npos)
        path = base_dir + "/" + path;
    ci_model* h = gpu.ci.model_load(gpu.rt, path.c_str(), &o);
    if (h == nullptr) throw EngineError("ci_model_load(" + path + "): " + gpu.ci.last_error());
    if (shared) {
        shared->ci = &gpu.ci;
        shared->models[name] = h;
        models_[name] = {h, false};
        return h;
    }
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
