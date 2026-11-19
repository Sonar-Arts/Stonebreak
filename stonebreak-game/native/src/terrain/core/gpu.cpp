#include "core/gpu.hpp"

#include <fstream>
#include <sstream>

namespace sbtg {

Block::~Block() {
    if (pool != nullptr && ptr != nullptr) pool->give(this);
}

Pool::~Pool() { trim(); }

std::shared_ptr<Block> Pool::take(size_t bytes, bool host) {
    const size_t n = round(std::max<size_t>(bytes, 1));
    void* p = nullptr;
    {
        std::lock_guard<std::mutex> lock(mu_);
        auto& fl = host ? free_host_ : free_dev_;
        auto it = fl.lower_bound(n);
        // Reuse a cached block of this class (or up to 2x larger for big ones: less fragmentation churn).
        if (it != fl.end() && it->first <= (n <= (1u << 20) ? n : 2 * n)) {
            p = it->second;
            const size_t got = it->first;
            fl.erase(it);
            cached_ -= got;
            live_ += got;
            auto b = std::make_shared<Block>();
            b->pool = this;
            b->ptr = p;
            b->bytes = got;
            b->host = host;
            return b;
        }
    }
    p = host ? ci_.host_alloc(rt_, n) : ci_.malloc_(rt_, n);
    if (p == nullptr) {
        trim();                                          // out of memory: hand the cache back and try once more
        p = host ? ci_.host_alloc(rt_, n) : ci_.malloc_(rt_, n);
        if (p == nullptr) throw EngineError(std::string(host ? "ci_host_alloc" : "ci_malloc") + "(" +
                                            std::to_string(n) + "): " + ci_.last_error());
    }
    std::lock_guard<std::mutex> lock(mu_);
    live_ += n;
    auto b = std::make_shared<Block>();
    b->pool = this;
    b->ptr = p;
    b->bytes = n;
    b->host = host;
    return b;
}

void Pool::give(Block* b) {
    std::lock_guard<std::mutex> lock(mu_);
    (b->host ? free_host_ : free_dev_).emplace(b->bytes, b->ptr);
    cached_ += b->bytes;
    live_ -= b->bytes;
    b->ptr = nullptr;
}

void Pool::trim() {
    std::lock_guard<std::mutex> lock(mu_);
    for (auto& [n, p] : free_dev_) ci_.free_(rt_, p);
    for (auto& [n, p] : free_host_) ci_.host_free(rt_, p);
    free_dev_.clear();
    free_host_.clear();
    cached_ = 0;
}

void Pool::trim_to(size_t keep) {
    std::lock_guard<std::mutex> lock(mu_);
    // The caller's stream may still use a cached block's previous contents only through tensors already freed in
    // stream order; cuMemFree waits for outstanding work on the block, so freeing is safe.
    while (cached_ > keep && (!free_dev_.empty() || !free_host_.empty())) {
        auto& fl = !free_dev_.empty() ? free_dev_ : free_host_;
        auto it = std::prev(fl.end());   // largest
        if (&fl == &free_dev_) ci_.free_(rt_, it->second);
        else ci_.host_free(rt_, it->second);
        cached_ -= it->first;
        fl.erase(it);
    }
}

Gpu::Gpu(const Ci& c, ci_runtime* r, void* s) : ci(c), rt(r), stream(s), pool(c, r) {}

Gpu::~Gpu() = default;

Tensor Gpu::empty_shape(DType dt, int ndim, const std::array<int64_t, 4>& shape, bool host) {
    Tensor t;
    t.dtype = dt;
    t.ndim = ndim;
    t.shape = shape;
    for (int i = ndim; i < 4; ++i) t.shape[static_cast<size_t>(i)] = 1;
    for (int i = 0; i < ndim; ++i)
        if (t.shape[static_cast<size_t>(i)] < 0) throw std::invalid_argument("negative tensor dim");
    t.host = host;
    t.mem = pool.take(t.bytes(), host);
    t.ptr = t.mem->ptr;
    return t;
}

Tensor Gpu::empty(DType dt, std::initializer_list<int64_t> shape, bool host) {
    if (shape.size() > 4) throw std::invalid_argument("tensor: more than 4 dims");
    std::array<int64_t, 4> s{1, 1, 1, 1};
    size_t i = 0;
    for (int64_t v : shape) s[i++] = v;
    return empty_shape(dt, static_cast<int>(shape.size()), s, host);
}

Tensor Gpu::zeros(DType dt, std::initializer_list<int64_t> shape) {
    Tensor t = empty(dt, shape);
    memset0(t);
    return t;
}

Tensor Gpu::full(float v, std::initializer_list<int64_t> shape) {
    Tensor t = empty(DType::F32, shape);
    struct A {
        float* p;
        long long n;
        float v;
    } a{t.f32(), t.numel(), v};
    launch(kernel("sbtg_core", "sbtg_fill_f32"), a.n, a);
    return t;
}

void Gpu::memset0(Tensor& t) {
    if (t.bytes() == 0) return;
    if (t.host) {
        sync();
        std::memset(t.ptr, 0, t.bytes());
        return;
    }
    check(ci.memset_(rt, t.ptr, 0, t.bytes(), stream), "ci_memset");
}

void Gpu::copy(Tensor& dst, const Tensor& src) {
    if (dst.bytes() != src.bytes()) throw std::invalid_argument("copy: " + dst.str() + " <- " + src.str());
    if (src.bytes() == 0) return;
    check(ci.memcpy_(rt, dst.ptr, src.ptr, src.bytes(), stream), "ci_memcpy");
}

Tensor Gpu::clone(const Tensor& t) {
    Tensor o = empty_shape(t.dtype, t.ndim, t.shape, t.host);
    copy(o, t);
    return o;
}

Tensor Gpu::to_host(const Tensor& t) {
    Tensor o = empty_shape(t.dtype, t.ndim, t.shape, true);
    copy(o, t);
    return o;
}

Tensor Gpu::to_device(const Tensor& t) {
    Tensor o = empty_shape(t.dtype, t.ndim, t.shape, false);
    copy(o, t);
    return o;
}

Tensor Gpu::upload(const void* data, DType dt, std::initializer_list<int64_t> shape) {
    Tensor o = empty(dt, shape);
    if (o.bytes() > 0) check(ci.memcpy_(rt, o.ptr, data, o.bytes(), stream), "ci_memcpy(upload)");
    return o;
}

void Gpu::sync() const { check(ci.stream_sync(rt, stream), "ci_stream_sync"); }

Tensor Gpu::borrow(void* ptr, DType dt, int ndim, const int64_t* shape, bool host) {
    if (ndim < 0 || ndim > 4) throw std::invalid_argument("borrow: 0..4 dims");
    Tensor t;
    t.ptr = ptr;
    t.dtype = dt;
    t.ndim = ndim;
    for (int i = 0; i < ndim; ++i) t.shape[static_cast<size_t>(i)] = shape[i];
    t.host = host;
    t.mem = std::make_shared<Block>();   // no pool: never freed by us
    t.mem->ptr = ptr;
    t.mem->bytes = t.bytes();
    t.mem->host = host;
    return t;
}

const std::string& Gpu::source(const std::string& label) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = sources_.find(label);
    if (it != sources_.end()) return it->second;
    std::string text;
    if (label.rfind("file:", 0) == 0) {
        std::ifstream f(label.substr(5), std::ios::binary);
        if (!f) throw std::runtime_error("cannot read kernel source " + label.substr(5));
        std::ostringstream ss;
        ss << f.rdbuf();
        text = ss.str();
    } else {
        auto& all = ModuleRegistry::all();
        auto m = all.find(label);
        if (m == all.end()) throw std::runtime_error("no kernel module '" + label + "'");
        text = m->second;
    }
    return sources_.emplace(label, std::move(text)).first->second;
}

ci_kernel* Gpu::kernel(const std::string& label, const std::string& name) {
    const std::string key = label + "\n" + name;
    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = kernels_.find(key);
        if (it != kernels_.end()) return it->second;
    }
    const std::string& src = source(label);
    // Module label in cache file names: the file stem for "file:" sources.
    std::string tag = label;
    if (tag.rfind("file:", 0) == 0) {
        tag = tag.substr(tag.find_last_of("/\\") + 1);
        tag = tag.substr(0, tag.find('.'));
    }
    ci_kernel* k = ci.kernel_get(rt, tag.c_str(), src.c_str(), name.c_str());
    if (k == nullptr) throw EngineError("kernel " + name + " (" + label + "): " + ci.last_error());
    std::lock_guard<std::mutex> lock(mu_);
    kernels_[key] = k;
    return k;
}

} // namespace sbtg
