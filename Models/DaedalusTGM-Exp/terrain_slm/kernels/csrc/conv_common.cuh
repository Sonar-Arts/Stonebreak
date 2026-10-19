// Shared device helpers for the convolution kernels (conv.cu, hconv.cu): raw-PTX fp16 conversions,
// cp.async, ldmatrix, mma.sync m16n8k16 (fp16 in, fp32 accumulate) and the 8-channel epilogue store.
#pragma once

typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;

#define F_FILM 1
#define F_RES 2
#define F_RAW 4
#define F_SILU 8
#define F_EULER 16

__device__ __forceinline__ u32 smem_u32(const void* p) {
    u32 a;
    asm("{ .reg .u64 t; cvta.to.shared.u64 t, %1; cvt.u32.u64 %0, t; }" : "=r"(a) : "l"(p));
    return a;
}

__device__ __forceinline__ void cp_async16(u32 dst, const void* src, bool ok) {
    asm volatile("cp.async.cg.shared.global [%0], [%1], 16, %2;\n" ::"r"(dst), "l"(src), "r"(ok ? 16 : 0));
}
__device__ __forceinline__ void cp_commit() { asm volatile("cp.async.commit_group;\n" ::); }
template <int N>
__device__ __forceinline__ void cp_wait() { asm volatile("cp.async.wait_group %0;\n" ::"n"(N)); }

__device__ __forceinline__ void ldm_x4(u32* r, u32 addr) {
    asm volatile("ldmatrix.sync.aligned.m8n8.x4.shared.b16 {%0,%1,%2,%3}, [%4];\n"
                 : "=r"(r[0]), "=r"(r[1]), "=r"(r[2]), "=r"(r[3]) : "r"(addr));
}
__device__ __forceinline__ void ldm_x4_t(u32* r, u32 addr) {
    asm volatile("ldmatrix.sync.aligned.m8n8.x4.trans.shared.b16 {%0,%1,%2,%3}, [%4];\n"
                 : "=r"(r[0]), "=r"(r[1]), "=r"(r[2]), "=r"(r[3]) : "r"(addr));
}
__device__ __forceinline__ void mma16816(float* d, const u32* a, const u32* b) {
    asm volatile("mma.sync.aligned.m16n8k16.row.col.f32.f16.f16.f32 {%0,%1,%2,%3}, {%4,%5,%6,%7}, {%8,%9}, {%0,%1,%2,%3};\n"
                 : "+f"(d[0]), "+f"(d[1]), "+f"(d[2]), "+f"(d[3])
                 : "r"(a[0]), "r"(a[1]), "r"(a[2]), "r"(a[3]), "r"(b[0]), "r"(b[1]));
}

__device__ __forceinline__ u32 pack_h2(float lo, float hi) {
    u32 r;
    asm("cvt.rn.f16x2.f32 %0, %1, %2;" : "=r"(r) : "f"(hi), "f"(lo));
    return r;
}
__device__ __forceinline__ u16 to_h(float v) {
    u16 r;
    asm("cvt.rn.f16.f32 %0, %1;" : "=h"(r) : "f"(v));
    return r;
}
__device__ __forceinline__ float from_h(u16 h) {
    float r;
    asm("cvt.f32.f16 %0, %1;" : "=f"(r) : "h"(h));
    return r;
}
__device__ __forceinline__ float silu(float v) { return __fdividef(v, 1.0f + __expf(-v)); }


// Finish 8 consecutive channels of one output pixel: + residual `rr` (8 fp16, loaded by the caller --
// ahead of time, see hconv.cu), store raw and/or SiLU (16 B each). `off` is the element offset of
// channel 0; `v` already carries bias and FiLM.
template <typename Args>
__device__ __forceinline__ void store8r(const Args& a, long long off, float* v, uint4 rr) {
    if (a.flags & F_RES) {
        u32 rw[4] = {rr.x, rr.y, rr.z, rr.w};
#pragma unroll
        for (int e = 0; e < 4; ++e) {
            v[2 * e] += from_h((u16)(rw[e] & 0xFFFF));
            v[2 * e + 1] += from_h((u16)(rw[e] >> 16));
        }
    }
    if (a.flags & F_RAW)
        *reinterpret_cast<uint4*>(a.out_raw + off) =
            make_uint4(pack_h2(v[0], v[1]), pack_h2(v[2], v[3]), pack_h2(v[4], v[5]), pack_h2(v[6], v[7]));
    if (a.flags & F_SILU)
        *reinterpret_cast<uint4*>(a.out_silu + off) =
            make_uint4(pack_h2(silu(v[0]), silu(v[1])), pack_h2(silu(v[2]), silu(v[3])),
                       pack_h2(silu(v[4]), silu(v[5])), pack_h2(silu(v[6]), silu(v[7])));
}

template <typename Args>
__device__ __forceinline__ void store8(const Args& a, long long off, float* v) {
    uint4 rr = make_uint4(0, 0, 0, 0);
    if (a.flags & F_RES) rr = *reinterpret_cast<const uint4*>(a.res + off);
    store8r(a, off, v, rr);
}
