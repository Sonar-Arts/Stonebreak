// The native DaedalusTGM generator: Python's WorldGenerator2 (+ TileBuilder) method for method, so every method can
// be parity-tested against its Python twin and switched native one at a time.
//
//   Fields Gen::call("controls", Args{{ci0, cj0, hc, wc}})
//
// A method runs natively when it is registered (SBTG_METHOD in a stage file) AND routed native (Gen::route; the
// Python host decides, "*" = everything registered); otherwise the call goes to the host's Python generator through
// the python callback (phase 2 hosting: the service's generator answers, its result is copied into this generator's
// memory). Stage code ALWAYS reaches other methods through call()/the typed helpers below, never directly, so the
// routing stays per method.
//
// Python method names are kept verbatim (leading underscores included). Arguments: Args.i = the Python call's int
// arguments in order, Args.f = its float arguments, Args.t = its tensor arguments (Python order of each kind).
// Results: Python dicts -> named Fields; a tensor -> {"": t}; a tuple -> {"0": .., "1": ..}; None -> empty Fields.
#pragma once

#include "core/gpu.hpp"
#include "core/json.hpp"
#include "core/ops.hpp"

#include <functional>
#include <list>
#include <map>
#include <mutex>
#include <set>
#include <unordered_map>

namespace sbtg {

class Gen;

struct Args {
    std::vector<int64_t> i;
    std::vector<double> f;
    std::vector<Tensor> t;
};

using MethodFn = Fields (*)(Gen&, const Args&);
struct MethodRegistry {
    static std::map<std::string, MethodFn>& all() {
        static std::map<std::string, MethodFn> m;
        return m;
    }
    MethodRegistry(const char* name, MethodFn fn) { all()[name] = fn; }
};
#define SBTG_METHOD(name, fn) static ::sbtg::MethodRegistry SBTG_CAT(sbtg_method_, __LINE__)(name, fn)

// Per-world state the host sets before calls (TileBuilder.build sets the same on the Python generator).
struct GenState {
    int64_t seed = 0;
    std::string climate_layout = "belts";
    bool sea_shelf = false;
    bool look_build = false;   // FX4 block look of the current build (generator2.use_block_look; TileBuilder.build sets it)
    Json raw;           // everything else the host sent (block scale, world config, knobs)
};

// Keyed LRU of method results (Python's _LRU: values are pure functions of the key, so capacity only changes speed and
// memory). Two tiers like Python's: the `gpu_cap` most recent entries on the device, the rest (up to `cap` in total)
// spilled to pinned host memory and copied back on a hit. Keys: the seed and the method's integer arguments.
class Cache {
public:
    Cache(Gpu* gpu, size_t cap, size_t gpu_cap) : gpu_(gpu), cap_(cap), gpu_cap_(std::min(cap, gpu_cap)) {}
    using Key = std::vector<int64_t>;
    bool contains(const Key& k) const { return map_.count(k) != 0; }
    Fields get_or(const Key& k, const std::function<Fields()>& fn);
    void put(const Key& k, Fields v);
    void clear() {
        map_.clear();
        gpu_order_.clear();
        host_order_.clear();
    }
    size_t size() const { return map_.size(); }
    size_t cap() const { return cap_; }
    size_t gpu_cap() const { return gpu_cap_; }

private:
    // Python's _LRU: two ordered tiers (GPU, host), most recent at the front of each.
    struct Entry {
        Fields v;
        std::list<Key>::iterator it;
        bool gpu = true;
    };
    void settle();
    Fields to(const Fields& f, bool host);
    Gpu* gpu_;
    size_t cap_, gpu_cap_;
    std::list<Key> gpu_order_, host_order_;
    std::map<Key, Entry> map_;
};

class Gen {
public:
    Gen(Gpu& gpu, Json manifest);
    ~Gen();

    Gpu& gpu;
    Json manifest;          // export/native_manifest.py: constants, blur taps, models, kernel sources
    GenState st;
    std::recursive_mutex lock;

    // ---- routing
    Fields call(const std::string& method, const Args& a);
    void route(const std::string& method, bool native);
    bool native(const std::string& method) const;
    using PythonFn = std::function<Fields(const std::string&, const Args&)>;
    PythonFn python;        // the host's generator (unset: every unrouted call fails)

    // ---- typed helpers for the common methods (all go through call())
    Fields controls(int64_t ci0, int64_t cj0, int64_t hc, int64_t wc) { return call("controls", {{ci0, cj0, hc, wc}, {}, {}}); }

    // ---- shared resources
    const Tensor& taps(double sigma);                    // gaussian_kernel1d(sigma), from the manifest
    // One per stage cache, created on first use with `cap` entries in total; the GPU share is Python's for the known
    // window caches (gpu_caps()), else everything.
    Cache& cache(const std::string& name, size_t cap);
    // After a top-level call: hand cached pool memory above the budget back to the driver.
    void trim_pool();
    void clear_caches();
    ci_model* model(const std::string& name);            // engine model `name` (manifest "models"), or nullptr
    void warm_models();                                  // loads every manifest model + builds its graphs
    std::string base_dir;                                // relative model paths resolve against it
    void set_model(const std::string& name, ci_model* m) { models_[name] = {m, false}; }
    const Json& constants() const { return manifest["constants"]; }
    double c(const std::string& name) const { return manifest["constants"][name].num(); }
    float cf(const std::string& name) const { return manifest["constants"][name].f32(); }

    // ---- stats
    std::map<std::string, long long> native_calls, python_calls;

private:
    std::set<std::string> routed_, excluded_;   // excluded_: kept on Python while route_all_ is set
    bool route_all_ = false;
    std::map<std::string, Tensor> taps_;
    std::map<std::string, std::unique_ptr<Cache>> caches_;
    int depth_ = 0;
    std::map<std::string, std::pair<ci_model*, bool>> models_;   // (model, owned)
};

} // namespace sbtg
