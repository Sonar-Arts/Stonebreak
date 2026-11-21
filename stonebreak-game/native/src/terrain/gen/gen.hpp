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

#include <atomic>
#include <condition_variable>
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
//
// Lanes (agent PX, 2026-11-19): a CacheCore can be shared by the lanes of one terrain pipeline (several generators,
// each on its own stream, building tiles concurrently). Then it is thread-safe and deduplicates work in flight: a key
// being computed by one lane is "pending" and other lanes wait for it instead of computing it again; values cross
// streams through an event recorded after their production (consumers' streams wait on it, the host never does) and
// the producing pool fences the memory's reuse behind the consumers (Pool / Block::foreign). Values never depend on
// which lane computed them, so tiles stay bit-identical to the sequential pipeline.
struct CiEvent {
    const Ci* ci = nullptr;
    ci_runtime* rt = nullptr;
    void* ev = nullptr;
    CiEvent() = default;
    CiEvent(const CiEvent&) = delete;
    CiEvent& operator=(const CiEvent&) = delete;
    ~CiEvent() {
        if (ev != nullptr) ci->event_destroy(rt, ev);
    }
};

// The pipeline's budget for cache values held in HOST memory (2026-11-20): spilled windows (pinned) and windows that
// live on the host (routes, pageable), over every cache, lane and climate layout of one terrain pipeline. Over the
// limit, the cache that is adding host bytes evicts its least recent host-resident entries (values are pure functions
// of their keys: an evicted window is recomputed when needed again, bit for bit). limit 0 = unlimited.
struct HostBudget {
    std::atomic<size_t> used{0};
    size_t limit = 0;
    std::atomic<long long> evictions{0};
};

class CacheCore {
public:
    using Key = std::vector<int64_t>;
    CacheCore(size_t cap, size_t gpu_cap, bool shared, std::shared_ptr<HostBudget> budget = nullptr)
        : cap_(cap), gpu_cap_(std::min(cap, gpu_cap)), shared_(shared), budget_(std::move(budget)) {}
    ~CacheCore();

private:
    friend class Cache;
    struct Entry {
        Fields v;
        std::list<Key>::iterator it;
        bool gpu = true;
        bool ready = false;
        int owner = -1;                 // pending: the lane computing it
        int lane = 0;                   // ready: the lane whose stream produced v
        std::shared_ptr<CiEvent> ev;    // ready (shared cores): recorded after v was produced
        size_t hb = 0;                  // host bytes of v, counted in host_bytes_ and the budget
    };
    std::mutex mu_;
    std::condition_variable cv_;
    size_t cap_, gpu_cap_;
    bool shared_;
    std::list<Key> gpu_order_, host_order_;
    std::map<Key, Entry> map_;
    std::shared_ptr<HostBudget> budget_;
    size_t host_bytes_ = 0;
    void account(Entry& e);                 // e.v changed: recount its host bytes (lock held)
    void forget(Entry& e);                  // e leaves the cache: give its host bytes back (lock held)
    void enforce_budget(const Key& keep);   // evict least recent host-resident entries while over budget (lock held)
};

// A lane's handle on a CacheCore (its own, or one shared by the pipeline's lanes).
class Cache {
public:
    using Key = CacheCore::Key;
    Cache(std::shared_ptr<CacheCore> core, Gpu* gpu, int lane) : core_(std::move(core)), gpu_(gpu), lane_(lane) {}
    Cache(Gpu* gpu, size_t cap, size_t gpu_cap) : Cache(std::make_shared<CacheCore>(cap, gpu_cap, false), gpu, 0) {}
    bool contains(const Key& k) const;           // ready, or pending in some lane
    Fields get_or(const Key& k, const std::function<Fields()>& fn);
    void put(const Key& k, Fields v);
    // Batch producers: claim(k) = this lane will compute k (it was absent; others now wait for it); release(k) drops
    // a claim that will not be filled (failure).
    bool claim(const Key& k);
    void release(const Key& k);
    void clear();
    size_t size() const;
    size_t cap() const { return core_->cap_; }
    size_t gpu_cap() const { return core_->gpu_cap_; }
    bool shared() const { return core_->shared_; }
    size_t host_bytes() const;

private:
    void settle(std::unique_lock<std::mutex>& l);
    void adopt(CacheCore::Entry& e);             // e.v usable on this lane's stream (shared cores)
    std::shared_ptr<CiEvent> record() const;     // event after the work queued on this lane's stream so far
    Fields to(const Fields& f, bool host);
    void mark(const Fields& f) const;            // this lane's stream will touch these blocks
    std::shared_ptr<CacheCore> core_;
    Gpu* gpu_;
    int lane_;
};

// What the lanes of one terrain pipeline share: their streams (Pool fences), the window caches (per climate layout)
// and the engine models (loaded once with one execution context per lane).
struct Shared {
    explicit Shared(int n) : lanes(n) {}
    ~Shared();
    int lanes;
    LaneStreams streams;
    std::mutex mu;
    std::map<std::string, std::shared_ptr<CacheCore>> cores;      // "<name>|<climate layout>"
    std::map<std::string, ci_model*> models;
    const Ci* ci = nullptr;
    static bool shares(const std::string& name);                    // the window caches
};

class Gen {
public:
    Gen(Gpu& gpu, Json manifest);
    ~Gen();

    // Lanes (agent PX): this generator's lane and the pipeline's shared state (null: a single generator).
    int lane = 0;
    std::shared_ptr<Shared> shared;

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
    // After a top-level call: hand cached pool memory above the budget back to the driver (device blocks above
    // 2 x POOL_CACHE_MB, pinned host blocks above 2 x pool_host_keep, each trimmed on its own).
    void trim_pool();
    void clear_caches();
    // Every cache entry (lane-local and shared) dropped; the caller holds every lane idle. Then the pools can be trimmed.
    void clear_all_caches();
    size_t cache_host_bytes() const;                     // host bytes of this generator's lane-local caches
    // Host-memory budget of the window caches (one per pipeline, shared by the lanes; null = unlimited) and the pinned
    // blocks a lane's pool keeps for reuse.
    std::shared_ptr<HostBudget> host_budget;
    size_t pool_host_keep = size_t{128} << 20;
    ci_model* model(const std::string& name);            // engine model `name` (manifest "models"), or nullptr
    void warm_models();                                  // loads every manifest model + builds its graphs
    std::string base_dir;                                // relative model paths resolve against it
    void set_model(const std::string& name, ci_model* m) { models_[name] = {m, false}; }
    const Json& constants() const { return manifest["constants"]; }
    double c(const std::string& name) const { return manifest["constants"][name].num(); }
    float cf(const std::string& name) const { return manifest["constants"][name].f32(); }

    // ---- stats
    std::map<std::string, long long> native_calls, python_calls;
    std::map<std::string, long long> counters;           // work counters (planner windows computed, ...)
    // SBTG_PROFILE=1: inclusive wall seconds per method (=2: the stream is synchronised around every call, so the
    // time includes the method's GPU work; slows tiles - diagnostics only).
    std::map<std::string, double> prof_s;
    int profile = 0;

private:
    std::set<std::string> routed_, excluded_;   // excluded_: kept on Python while route_all_ is set
    bool route_all_ = false;
    std::map<std::string, Tensor> taps_;
    std::map<std::string, std::unique_ptr<Cache>> caches_;   // lane-local, plus handles on shared cores ("name|layout")
    int depth_ = 0;
    std::map<std::string, std::pair<ci_model*, bool>> models_;   // (model, owned)
};

} // namespace sbtg
