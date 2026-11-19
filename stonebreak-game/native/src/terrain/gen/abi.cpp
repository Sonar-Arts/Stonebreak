// C ABI of the native generator (include/stonebreak/terrain.h, "generator (ABI 2)").
#include "stonebreak/terrain.h"

#include "gen/gen.hpp"

#include <cstring>
#include <sstream>

namespace sbtg {
const Ci* ctx_ci(const sbtg_ctx* ctx);
ci_runtime* ctx_rt(const sbtg_ctx* ctx);
void set_error(const std::string& msg);
} // namespace sbtg

using sbtg::DType;
using sbtg::Fields;
using sbtg::Tensor;

struct sbtg_fields {
    Fields f;
    sbtg::Gpu* gpu = nullptr;   // where sbtg_fields_add copies to (set by the generator before the callback)
};

struct sbtg_gen {
    std::unique_ptr<sbtg::Gpu> gpu;
    std::unique_ptr<sbtg::Gen> gen;
    void* own_stream = nullptr;
    sbtg_python_fn py = nullptr;
    void* py_user = nullptr;
    std::string py_error;
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

Tensor from_c(const sbtg_tensor& t) {
    return sbtg::Gpu::borrow(t.data, static_cast<DType>(t.dtype), t.ndim, t.shape, t.host != 0);
}
void to_c(const Tensor& t, sbtg_tensor* o) {
    std::memset(o, 0, sizeof *o);
    o->data = t.ptr;
    o->dtype = static_cast<int32_t>(t.dtype);
    o->ndim = t.ndim;
    for (int i = 0; i < 4; ++i) o->shape[i] = i < t.ndim ? t.shape[static_cast<size_t>(i)] : 1;
    o->host = t.host ? 1 : 0;
}

} // namespace

#if !defined(_WIN32)
#pragma GCC visibility push(default)
#endif
extern "C" {

sbtg_gen* sbtg_gen_create(sbtg_ctx* ctx, const char* manifest_json, void* stream) {
    if (ctx == nullptr || manifest_json == nullptr) {
        sbtg::set_error("sbtg_gen_create: null argument");
        return nullptr;
    }
    try {
        auto g = std::make_unique<sbtg_gen>();
        const sbtg::Ci& ci = *sbtg::ctx_ci(ctx);
        ci_runtime* rt = sbtg::ctx_rt(ctx);
        g->gpu = std::make_unique<sbtg::Gpu>(ci, rt, stream);
        g->gen = std::make_unique<sbtg::Gen>(*g->gpu, sbtg::Json::parse(manifest_json));
        sbtg_gen* raw = g.get();
        g->gen->python = [raw](const std::string& method, const sbtg::Args& a) -> Fields {
            if (raw->py == nullptr) throw std::logic_error("method '" + method + "' is not native (no python host)");
            std::vector<sbtg_tensor> ts(a.t.size());
            for (size_t k = 0; k < a.t.size(); ++k) to_c(a.t[k], &ts[k]);
            sbtg_fields out;
            out.gpu = raw->gpu.get();
            int32_t r = raw->py(raw->py_user, method.c_str(), a.i.data(), static_cast<int32_t>(a.i.size()),
                                a.f.data(), static_cast<int32_t>(a.f.size()), ts.data(),
                                static_cast<int32_t>(ts.size()), &out);
            if (r != 0) throw std::runtime_error("python " + method + " failed (" + std::to_string(r) + ")");
            return out.f;
        };
        return g.release();
    } catch (const std::exception& e) {
        sbtg::set_error(e.what());
        return nullptr;
    }
}

void sbtg_gen_destroy(sbtg_gen* g) {
    if (g == nullptr) return;
    void* s = g->own_stream;
    const sbtg::Ci* ci = g->gpu ? &g->gpu->ci : nullptr;
    ci_runtime* rt = g->gpu ? g->gpu->rt : nullptr;
    if (g->gpu) g->gpu->sync();
    g->gen.reset();
    g->gpu.reset();
    if (s != nullptr && ci != nullptr) ci->stream_destroy(rt, s);
    delete g;
}

int32_t sbtg_gen_set_state(sbtg_gen* g, const char* json) {
    if (g == nullptr || json == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    try {
        std::lock_guard<std::recursive_mutex> lock(g->gen->lock);
        sbtg::Json j = sbtg::Json::parse(json);
        auto& st = g->gen->st;
        bool cache_change = (j.has("climate_layout") && j["climate_layout"].str() != st.climate_layout);
        // Generator knobs (relief gain, hill transfer, ...): Python's setters clear every LRU on a change.
        if (j.has("knobs")) {
            const bool had = st.raw.has("knobs");
            for (auto& [k, v] : j["knobs"].members())
                if (had && (!st.raw["knobs"].has(k) || st.raw["knobs"][k].num() != v.num())) cache_change = true;
        }
        if (j.has("seed")) st.seed = j["seed"].i64();
        if (j.has("climate_layout")) st.climate_layout = j["climate_layout"].str();
        if (j.has("sea_shelf")) st.sea_shelf = j["sea_shelf"].boolean();
        if (j.has("look_build")) st.look_build = j["look_build"].boolean();
        st.raw = j;
        // Python clears every LRU on a layout switch (they are keyed by seed only).
        if (cache_change) g->gen->clear_caches();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_gen_set_python(sbtg_gen* g, sbtg_python_fn fn, void* user) {
    if (g == nullptr) return fail(SBTG_ERR_ARG, "null generator");
    g->py = fn;
    g->py_user = user;
    return SBTG_OK;
}

int32_t sbtg_gen_set_model(sbtg_gen* g, const char* name, void* model) {
    if (g == nullptr || name == nullptr || model == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    g->gen->set_model(name, static_cast<ci_model*>(model));
    return SBTG_OK;
}

int32_t sbtg_gen_route(sbtg_gen* g, const char* method, int32_t native) {
    if (g == nullptr || method == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    try {
        g->gen->route(method, native != 0);
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_gen_clear_caches(sbtg_gen* g) {
    if (g == nullptr) return fail(SBTG_ERR_ARG, "null generator");
    std::lock_guard<std::recursive_mutex> lock(g->gen->lock);
    g->gen->clear_caches();
    return SBTG_OK;
}

int32_t sbtg_gen_call(sbtg_gen* g, const char* method, const int64_t* iargs, int32_t n_i, const double* fargs,
                      int32_t n_f, const sbtg_tensor* targs, int32_t n_t, sbtg_fields** out) {
    if (g == nullptr || method == nullptr || out == nullptr || (n_i > 0 && iargs == nullptr) ||
        (n_f > 0 && fargs == nullptr) || (n_t > 0 && targs == nullptr))
        return fail(SBTG_ERR_ARG, "null argument");
    *out = nullptr;
    try {
        sbtg::Args a;
        a.i.assign(iargs, iargs + n_i);
        a.f.assign(fargs, fargs + n_f);
        for (int32_t k = 0; k < n_t; ++k) a.t.push_back(from_c(targs[k]));
        auto res = std::make_unique<sbtg_fields>();
        res->gpu = g->gpu.get();
        res->f = g->gen->call(method, a);
        *out = res.release();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_gen_methods(char* buf, int32_t cap) {
    std::string s;
    for (auto& [n, fn] : sbtg::MethodRegistry::all()) s += n + "\n";
    if (buf != nullptr && cap > 0) {
        size_t k = std::min<size_t>(s.size(), static_cast<size_t>(cap - 1));
        std::memcpy(buf, s.data(), k);
        buf[k] = '\0';
    }
    return static_cast<int32_t>(s.size());
}

int32_t sbtg_gen_stats(sbtg_gen* g, char* buf, int32_t cap) {
    if (g == nullptr) return 0;
    std::ostringstream o;
    o << "{\"launches\":" << g->gpu->launches << ",\"pool_live\":" << g->gpu->pool.live_bytes()
      << ",\"pool_cached\":" << g->gpu->pool.cached_bytes() << ",\"native\":{";
    bool first = true;
    for (auto& [n, c] : g->gen->native_calls) {
        o << (first ? "" : ",") << "\"" << n << "\":" << c;
        first = false;
    }
    o << "},\"python\":{";
    first = true;
    for (auto& [n, c] : g->gen->python_calls) {
        o << (first ? "" : ",") << "\"" << n << "\":" << c;
        first = false;
    }
    o << "}}";
    std::string s = o.str();
    if (buf != nullptr && cap > 0) {
        size_t k = std::min<size_t>(s.size(), static_cast<size_t>(cap - 1));
        std::memcpy(buf, s.data(), k);
        buf[k] = '\0';
    }
    return static_cast<int32_t>(s.size());
}

sbtg_fields* sbtg_fields_new(void) { return new sbtg_fields(); }
int32_t sbtg_fields_count(const sbtg_fields* f) { return f == nullptr ? 0 : static_cast<int32_t>(f->f.items.size()); }
const char* sbtg_fields_name(const sbtg_fields* f, int32_t i) {
    if (f == nullptr || i < 0 || static_cast<size_t>(i) >= f->f.items.size()) return nullptr;
    return f->f.items[static_cast<size_t>(i)].first.c_str();
}
int32_t sbtg_fields_get(const sbtg_fields* f, int32_t i, sbtg_tensor* t) {
    if (f == nullptr || t == nullptr || i < 0 || static_cast<size_t>(i) >= f->f.items.size())
        return fail(SBTG_ERR_ARG, "bad field index");
    to_c(f->f.items[static_cast<size_t>(i)].second, t);
    return SBTG_OK;
}
int32_t sbtg_fields_add(sbtg_fields* f, const char* name, const sbtg_tensor* t) {
    if (f == nullptr || name == nullptr || t == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    if (f->gpu == nullptr) return fail(SBTG_ERR_STATE, "fields not bound to a generator");
    try {
        Tensor src = from_c(*t);
        Tensor dst = f->gpu->empty_shape(src.dtype, src.ndim, src.shape, false);
        f->gpu->copy(dst, src);
        f->f.set(name, std::move(dst));
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}
void sbtg_fields_free(sbtg_fields* f) { delete f; }

} // extern "C"
#if !defined(_WIN32)
#pragma GCC visibility pop
#endif
