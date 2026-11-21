// The engine runtime's C ABI (Open Mason cenda/infer.h, ci_*), opened at run time from the libcenda_kernels the host
// already loaded: no link dependency, no CUDA toolkit.
#pragma once

#include "cenda/infer.h"

#include <stdexcept>
#include <string>
#include <type_traits>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#undef near   // <minwindef.h> legacy pointer qualifiers; `near` is a field name in the stages
#undef far
#else
#include <dlfcn.h>
#endif

namespace sbtg {

struct Ci {
    decltype(&ci_abi_version) abi_version = nullptr;
    decltype(&ci_last_error) last_error = nullptr;
    decltype(&ci_kernel_get) kernel_get = nullptr;
    decltype(&ci_launch) launch = nullptr;
    decltype(&ci_malloc) malloc_ = nullptr;
    decltype(&ci_free) free_ = nullptr;
    decltype(&ci_memcpy) memcpy_ = nullptr;
    decltype(&ci_run) run = nullptr;
    decltype(&ci_host_alloc) host_alloc = nullptr;
    decltype(&ci_host_free) host_free = nullptr;
    decltype(&ci_memset) memset_ = nullptr;
    decltype(&ci_stream_sync) stream_sync = nullptr;
    decltype(&ci_event_create) event_create = nullptr;
    decltype(&ci_event_destroy) event_destroy = nullptr;
    decltype(&ci_event_record) event_record = nullptr;
    decltype(&ci_stream_wait) stream_wait = nullptr;
    decltype(&ci_stream_create) stream_create = nullptr;
    decltype(&ci_stream_destroy) stream_destroy = nullptr;
    decltype(&ci_event_sync) event_sync = nullptr;
    decltype(&ci_model_info) model_info = nullptr;
    decltype(&ci_model_load) model_load = nullptr;
    decltype(&ci_model_destroy) model_destroy = nullptr;
    decltype(&ci_model_warmup) model_warmup = nullptr;
    decltype(&ci_runtime_create) runtime_create = nullptr;
    decltype(&ci_runtime_destroy) runtime_destroy = nullptr;
    decltype(&ci_cuda_available) cuda_available = nullptr;

    void bind(const char* path) {
#if defined(_WIN32)
        void* lib = reinterpret_cast<void*>(LoadLibraryA(path));
        auto sym = [&](const char* n) { return reinterpret_cast<void*>(GetProcAddress(reinterpret_cast<HMODULE>(lib), n)); };
#else
        void* lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        auto sym = [&](const char* n) { return dlsym(lib, n); };
#endif
        if (lib == nullptr) throw std::runtime_error(std::string("cannot open the engine library ") + path);
        auto get = [&](auto& fn, const char* n) {
            void* p = sym(n);
            if (p == nullptr) throw std::runtime_error(std::string("engine library lacks ") + n);
            fn = reinterpret_cast<std::remove_reference_t<decltype(fn)>>(p);
        };
        get(abi_version, "ci_abi_version");
        // Exact match (review-native F11): another ABI may change ci_request / ci_launch layouts.
        if (abi_version() != CI_ABI_VERSION)
            throw std::runtime_error("engine ci ABI " + std::to_string(abi_version()) + " (this library needs " +
                                     std::to_string(CI_ABI_VERSION) + ")");
        get(last_error, "ci_last_error");
        get(kernel_get, "ci_kernel_get");
        get(launch, "ci_launch");
        get(malloc_, "ci_malloc");
        get(free_, "ci_free");
        get(memcpy_, "ci_memcpy");
        get(run, "ci_run");
        get(host_alloc, "ci_host_alloc");
        get(host_free, "ci_host_free");
        get(memset_, "ci_memset");
        get(stream_sync, "ci_stream_sync");
        get(event_create, "ci_event_create");
        get(event_destroy, "ci_event_destroy");
        get(event_record, "ci_event_record");
        get(stream_wait, "ci_stream_wait");
        get(stream_create, "ci_stream_create");
        get(stream_destroy, "ci_stream_destroy");
        get(event_sync, "ci_event_sync");
        get(model_info, "ci_model_info");
        get(model_load, "ci_model_load");
        get(model_destroy, "ci_model_destroy");
        get(model_warmup, "ci_model_warmup");
        get(runtime_create, "ci_runtime_create");
        get(runtime_destroy, "ci_runtime_destroy");
        get(cuda_available, "ci_cuda_available");
    }
};

struct EngineError : std::runtime_error {
    using std::runtime_error::runtime_error;
};


} // namespace sbtg
