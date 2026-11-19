// Device / host tensors of the native terrain pipeline: contiguous, row-major, up to 4 dims, memory from the Gpu's
// caching pool (stream-ordered reuse, like torch's caching allocator on one stream).
#pragma once

#include <array>
#include <cstdint>
#include <initializer_list>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace sbtg {

// Wire values of sbtg_tensor.dtype (include/stonebreak/terrain.h): keep in sync.
enum class DType : int32_t { F32 = 0, F64 = 1, I32 = 2, I64 = 3, U8 = 4, BOOL = 5, I16 = 6, F16 = 7 };

inline size_t dtype_size(DType d) {
    switch (d) {
    case DType::F32: case DType::I32: return 4;
    case DType::F64: case DType::I64: return 8;
    case DType::U8: case DType::BOOL: return 1;
    case DType::I16: case DType::F16: return 2;
    }
    return 0;
}
inline const char* dtype_name(DType d) {
    switch (d) {
    case DType::F32: return "f32"; case DType::F64: return "f64"; case DType::I32: return "i32";
    case DType::I64: return "i64"; case DType::U8: return "u8"; case DType::BOOL: return "bool";
    case DType::I16: return "i16"; case DType::F16: return "f16";
    }
    return "?";
}

class Pool;

// One allocation; returned to its pool (or freed, for external memory: never) when the last tensor drops it.
struct Block {
    Pool* pool = nullptr;   // nullptr: memory owned by someone else (a borrowed host / torch pointer)
    void* ptr = nullptr;
    size_t bytes = 0;
    bool host = false;
    Block() = default;
    Block(const Block&) = delete;
    Block& operator=(const Block&) = delete;
    ~Block();
};

struct Tensor {
    std::shared_ptr<Block> mem;
    void* ptr = nullptr;
    DType dtype = DType::F32;
    int ndim = 0;
    std::array<int64_t, 4> shape{1, 1, 1, 1};
    bool host = false;

    bool defined() const { return ptr != nullptr || (mem && numel() == 0); }
    int64_t numel() const {
        int64_t n = 1;
        for (int i = 0; i < ndim; ++i) n *= shape[static_cast<size_t>(i)];
        return n;
    }
    size_t bytes() const { return static_cast<size_t>(numel()) * dtype_size(dtype); }
    int64_t size(int d) const {
        if (d < 0) d += ndim;
        if (d < 0 || d >= ndim) throw std::out_of_range("tensor dim");
        return shape[static_cast<size_t>(d)];
    }
    template <typename T> T* data() const { return static_cast<T*>(ptr); }
    float* f32() const { check(DType::F32); return static_cast<float*>(ptr); }
    int64_t* i64() const { check(DType::I64); return static_cast<int64_t*>(ptr); }
    int32_t* i32() const { check(DType::I32); return static_cast<int32_t*>(ptr); }
    void check(DType d) const {
        if (dtype != d) throw std::invalid_argument(std::string("tensor is ") + dtype_name(dtype) + ", not " + dtype_name(d));
    }
    // Same memory, new shape (numel must match).
    Tensor view(std::initializer_list<int64_t> s) const {
        Tensor t = *this;
        t.ndim = static_cast<int>(s.size());
        if (t.ndim > 4) throw std::invalid_argument("tensor: more than 4 dims");
        t.shape = {1, 1, 1, 1};
        int i = 0;
        for (int64_t v : s) t.shape[static_cast<size_t>(i++)] = v;
        if (t.numel() != numel()) throw std::invalid_argument("tensor view: numel differs");
        return t;
    }
    // Plane k of the leading dim (contiguous sub-tensor, shares memory).
    Tensor operator[](int64_t k) const {
        if (ndim < 1 || k < 0 || k >= shape[0]) throw std::out_of_range("tensor index");
        Tensor t = *this;
        t.ndim = ndim - 1;
        for (int i = 0; i < 3; ++i) t.shape[static_cast<size_t>(i)] = shape[static_cast<size_t>(i + 1)];
        t.shape[3] = 1;
        t.ptr = static_cast<char*>(ptr) + static_cast<size_t>(k) * t.bytes();
        return t;
    }
    std::string str() const {
        std::string s = std::string(dtype_name(dtype)) + "[";
        for (int i = 0; i < ndim; ++i) s += (i ? "," : "") + std::to_string(shape[static_cast<size_t>(i)]);
        return s + (host ? "]h" : "]");
    }
};

// Named results of a generator method (Python: a dict of tensors; a single tensor is {"": t}).
struct Fields {
    std::vector<std::pair<std::string, Tensor>> items;
    bool has(const std::string& k) const {
        for (auto& [n, t] : items)
            if (n == k) return true;
        return false;
    }
    const Tensor& operator[](const std::string& k) const {
        for (auto& [n, t] : items)
            if (n == k) return t;
        throw std::out_of_range("no field '" + k + "'");
    }
    void set(const std::string& k, Tensor t) {
        for (auto& [n, v] : items)
            if (n == k) {
                v = std::move(t);
                return;
            }
        items.emplace_back(k, std::move(t));
    }
    bool empty() const { return items.empty(); }
    const Tensor& one() const {
        if (items.size() != 1) throw std::logic_error("Fields::one: " + std::to_string(items.size()) + " fields");
        return items[0].second;
    }
    static Fields of(Tensor t) {
        Fields f;
        f.items.emplace_back("", std::move(t));
        return f;
    }
};

} // namespace sbtg
