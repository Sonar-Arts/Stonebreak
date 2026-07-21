#include "cenda/kernels.h"

#include <zstd.h>

extern "C" {

int64_t ck_zstd_bound(int64_t src_size) {
    if (src_size < 0) {
        return -1;
    }
    return static_cast<int64_t>(ZSTD_compressBound(static_cast<size_t>(src_size)));
}

int64_t ck_zstd_compress(uint8_t* dst, int64_t dst_cap,
                         const uint8_t* src, int64_t src_size, int32_t level) {
    if (dst == nullptr || src == nullptr || dst_cap <= 0 || src_size < 0) {
        return -1;
    }
    const size_t written = ZSTD_compress(dst, static_cast<size_t>(dst_cap),
                                         src, static_cast<size_t>(src_size), level);
    if (ZSTD_isError(written)) {
        return -1;
    }
    return static_cast<int64_t>(written);
}

int64_t ck_zstd_decompress(uint8_t* dst, int64_t dst_cap,
                           const uint8_t* src, int64_t src_size) {
    if (dst == nullptr || src == nullptr || dst_cap <= 0 || src_size <= 0) {
        return -1;
    }
    const size_t written = ZSTD_decompress(dst, static_cast<size_t>(dst_cap),
                                           src, static_cast<size_t>(src_size));
    if (ZSTD_isError(written)) {
        return -1;
    }
    return static_cast<int64_t>(written);
}

} // extern "C"
