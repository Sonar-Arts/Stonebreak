// The native terrain pipeline's GPU context: engine runtime + one stream, a caching memory pool, and the kernel
// modules (CUDA sources embedded in this library or read from the DaedalusTGM kernel sources, compiled by the engine's
// NVRTC and disk-cached). Kernel argument structs are passed by value (one struct = the kernel's only parameter).
#pragma once

#include "core/ci.hpp"
#include "core/tensor.hpp"

#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace sbtg {

// The streams of a terrain pipeline's lanes (index = lane id), for cross-lane fences: a block that another lane used
// is reused only after that lane's stream has passed the point where the block was given back.
struct LaneStreams {
    std::vector<void*> streams;
};

// Stream-ordered caching allocator over ci_malloc / ci_host_alloc. Every block is used on the pool's one stream, so a
// freed block can be handed out again at once (the stream orders the old reads before the new writes), exactly like
// torch's allocator on a single stream. Sizes are rounded to 512 B (small) / 2 MiB (large) classes.
// Lanes (agent PX): a block another lane's stream touched (Block::foreign) carries events recorded on those streams
// when it is given back; take() makes this pool's stream wait for them (no host sync) before handing it out again.
class Pool {
public:
    Pool(const Ci& ci, ci_runtime* rt) : ci_(ci), rt_(rt) {}
    int lane = 0;                          // this pool's lane
    void* stream = nullptr;                // this pool's stream (the owning Gpu's)
    const LaneStreams* lanes = nullptr;    // null: a single-lane pipeline (no fences)
    ~Pool();
    Pool(const Pool&) = delete;
    Pool& operator=(const Pool&) = delete;

    std::shared_ptr<Block> take(size_t bytes, bool host);
    void give(Block* b);                 // called by ~Block
    void trim();                         // frees every cached (unused) block
    // Frees cached blocks of one kind (device or pinned host), largest first, until at most keep_bytes of that kind stay.
    void trim_to(size_t keep_bytes, bool host);
    size_t cached_bytes() const { return cached_; }
    size_t cached_host_bytes() const { return cached_host_; }
    size_t cached_device_bytes() const { return cached_ - cached_host_; }
    size_t live_bytes() const { return live_; }
    size_t live_host_bytes() const { return live_host_; }

private:
    static size_t round(size_t n) {
        if (n <= (1u << 20)) return (n + 511) / 512 * 512;
        return (n + (2u << 20) - 1) / (2u << 20) * (2u << 20);
    }
    struct Free {
        void* p;
        std::vector<void*> evs;   // other lanes' events this block's reuse waits for
    };
    void drop(Free& f, bool host);   // frees one cached block (its fences waited first)
    const Ci& ci_;
    ci_runtime* rt_;
    std::mutex mu_;
    std::multimap<size_t, Free> free_dev_, free_host_;
    size_t cached_ = 0, live_ = 0;
    size_t cached_host_ = 0, live_host_ = 0;   // the pinned (host) share of cached_ / live_
};

// Registry of embedded kernel modules: label -> CUDA source. Stage files register theirs with SBTG_MODULE.
struct ModuleRegistry {
    static std::map<std::string, const char*>& all() {
        static std::map<std::string, const char*> m;
        return m;
    }
    ModuleRegistry(const char* label, const char* source) { all()[label] = source; }
};
#define SBTG_CAT2(a, b) a##b
#define SBTG_CAT(a, b) SBTG_CAT2(a, b)
#define SBTG_MODULE(label, source) static ::sbtg::ModuleRegistry SBTG_CAT(sbtg_module_, __LINE__)(label, source)

class Gpu {
public:
    Gpu(const Ci& ci, ci_runtime* rt, void* stream);
    ~Gpu();
    Gpu(const Gpu&) = delete;
    Gpu& operator=(const Gpu&) = delete;

    const Ci& ci;
    ci_runtime* rt;
    void* stream;       // every launch and copy goes here (nullptr = the legacy default stream)

    void check(int32_t r, const char* what) const {
        if (r != 0) throw EngineError(std::string(what) + ": " + ci.last_error());
    }

    // ---- memory
    Tensor empty(DType dt, std::initializer_list<int64_t> shape, bool host = false);
    Tensor empty_like(const Tensor& t) { return empty_shape(t.dtype, t.ndim, t.shape, t.host); }
    Tensor empty_shape(DType dt, int ndim, const std::array<int64_t, 4>& shape, bool host = false);
    Tensor zeros(DType dt, std::initializer_list<int64_t> shape);
    Tensor full(float v, std::initializer_list<int64_t> shape);              // F32
    Tensor clone(const Tensor& t);
    Tensor to_host(const Tensor& t);                 // async into pinned memory; call sync() before reading
    Tensor to_device(const Tensor& t);               // host -> device (async from pinned / sync from pageable)
    Tensor upload(const void* data, DType dt, std::initializer_list<int64_t> shape);   // pageable host -> device
    void copy(Tensor& dst, const Tensor& src);       // same bytes, any devices
    void memset0(Tensor& t);
    void sync() const;                               // waits for this stream
    // A tensor over memory owned elsewhere (a torch tensor, a caller buffer).
    static Tensor borrow(void* ptr, DType dt, int ndim, const int64_t* shape, bool host);

    // ---- kernels
    // Kernel `name` of module `label` (a registered module, or "file:<path>" for a source read from disk once).
    ci_kernel* kernel(const std::string& label, const std::string& name);
    template <typename A>
    void launch(ci_kernel* k, long long threads, const A& args, unsigned block = 256, unsigned smem = 0) {
        if (threads <= 0) return;
        const uint32_t grid[3] = {static_cast<uint32_t>((threads + block - 1) / block), 1, 1};
        check(ci.launch(rt, k, grid, block, smem, &args, sizeof(A), stream), "ci_launch");
        ++launches;
    }
    template <typename A>
    void launch_grid(ci_kernel* k, uint32_t gx, uint32_t gy, uint32_t gz, unsigned block, const A& args,
                     unsigned smem = 0) {
        if (gx == 0 || gy == 0 || gz == 0) return;
        const uint32_t grid[3] = {gx, gy, gz};
        check(ci.launch(rt, k, grid, block, smem, &args, sizeof(A), stream), "ci_launch");
        ++launches;
    }
    // Source text registered or read for `label` (the exact string NVRTC compiles).
    const std::string& source(const std::string& label);

    Pool pool;
    long long launches = 0;

private:
    std::mutex mu_;
    std::unordered_map<std::string, ci_kernel*> kernels_;
    std::unordered_map<std::string, std::string> sources_;
};

} // namespace sbtg
