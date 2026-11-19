// libstonebreak_terrain core kernels: torch-exact building blocks shared by every stage (module "sbtg_core").
//
// Bit-parity rules (the Python generator is the reference; see the stage parity tests):
//  - every elementwise op is ONE IEEE fp32 op in torch's order: use the __f*_rn intrinsics (MUL/ADD/SUB/DIV below) so
//    NVRTC never contracts a*b+c into an FMA, except where torch's own kernel fuses (marked);
//  - "tensor / python scalar" in torch is a multiply by the fp32 reciprocal; "tensor / tensor" is a true division;
//  - transcendental functions (expf, sinf, logf, powf, tanhf, ...) are the CUDA libm ones torch calls too;
//  - torch.clamp & co keep NaN: t_clamp*, t_minimum/t_maximum below.
// Every kernel takes ONE argument struct by value; its host twin lives in core/ops.hpp (keep them in sync).

typedef unsigned int u32;
typedef unsigned long long u64;

#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }

struct FillArgs {
    float* p;
    long long n;
    float v;
};
extern "C" __global__ void sbtg_fill_f32(const FillArgs a) {
    long long i = gtid();
    if (i < a.n) a.p[i] = a.v;
}

// ---------------------------------------------------------------------------------------------- copy / layout
// Region copy over P planes, any element size (1, 2, 4, 8 bytes):
//   dst[p, dr0 + y, dc0 + x] = src[p, sr0 + y, sc0 + x]   for y < h, x < w
struct Copy2dArgs {
    const char* src;
    char* dst;
    long long P;
    int SH, SW, sr0, sc0;
    int DH, DW, dr0, dc0;
    int h, w, es;
};
extern "C" __global__ void sbtg_copy2d(const Copy2dArgs a) {
    long long i = gtid();
    const long long hw = (long long)a.h * a.w;
    if (i >= hw * a.P) return;
    long long p = i / hw;
    int r = (int)(i - p * hw);
    int y = r / a.w, x = r - y * a.w;
    long long s = (p * a.SH + a.sr0 + y) * (long long)a.SW + a.sc0 + x;
    long long d = (p * a.DH + a.dr0 + y) * (long long)a.DW + a.dc0 + x;
    switch (a.es) {
    case 1: ((unsigned char*)a.dst)[d] = ((const unsigned char*)a.src)[s]; break;
    case 2: ((unsigned short*)a.dst)[d] = ((const unsigned short*)a.src)[s]; break;
    case 4: ((u32*)a.dst)[d] = ((const u32*)a.src)[s]; break;
    default: ((u64*)a.dst)[d] = ((const u64*)a.src)[s]; break;
    }
}

// Nearest upsample by integer factors (repeat_interleave along both axes): (P, h, w) -> (P, h ky, w kx).
struct RepeatArgs {
    const char* src;
    char* dst;
    long long P;
    int h, w, ky, kx, es;
};
extern "C" __global__ void sbtg_repeat2d(const RepeatArgs a) {
    long long i = gtid();
    const int H = a.h * a.ky, W = a.w * a.kx;
    const long long HW = (long long)H * W;
    if (i >= HW * a.P) return;
    long long p = i / HW;
    int r = (int)(i - p * HW);
    int Y = r / W, X = r - Y * W;
    long long s = (p * a.h + Y / a.ky) * (long long)a.w + X / a.kx;
    switch (a.es) {
    case 1: ((unsigned char*)a.dst)[i] = ((const unsigned char*)a.src)[s]; break;
    case 2: ((unsigned short*)a.dst)[i] = ((const unsigned short*)a.src)[s]; break;
    case 4: ((u32*)a.dst)[i] = ((const u32*)a.src)[s]; break;
    default: ((u64*)a.dst)[i] = ((const u64*)a.src)[s]; break;
    }
}

// ---------------------------------------------------------------------------------------------- pooling
// F.avg_pool2d(x, k) (stride k, no padding, ceil_mode False): sum over rows then columns in fp32, then / (k*k)
// (a true division, as torch's avg_pool2d kernel).
struct AvgPoolArgs {
    const float* src;
    float* dst;
    long long P;
    int H, W, k;
};
extern "C" __global__ void sbtg_avgpool(const AvgPoolArgs a) {
    long long i = gtid();
    const int h = a.H / a.k, w = a.W / a.k;
    const long long hw = (long long)h * w;
    if (i >= hw * a.P) return;
    long long p = i / hw;
    int r = (int)(i - p * hw);
    int y = r / w, x = r - y * w;
    const float* s = a.src + (p * a.H + (long long)y * a.k) * a.W + (long long)x * a.k;
    float acc = 0.0f;
    for (int dy = 0; dy < a.k; ++dy)
        for (int dx = 0; dx < a.k; ++dx) acc = ADD(acc, s[(long long)dy * a.W + dx]);
    a.dst[i] = DIV(acc, (float)(a.k * a.k));
}

// F.max_pool2d(x, k) (stride k): max, NaN-propagating like torch.
struct MaxPoolArgs {
    const float* src;
    float* dst;
    long long* idx;     // optional flat index within the plane (return_indices), or nullptr
    long long P;
    int H, W, k;
};
extern "C" __global__ void sbtg_maxpool(const MaxPoolArgs a) {
    long long i = gtid();
    const int h = a.H / a.k, w = a.W / a.k;
    const long long hw = (long long)h * w;
    if (i >= hw * a.P) return;
    long long p = i / hw;
    int r = (int)(i - p * hw);
    int y = r / w, x = r - y * w;
    const float* s = a.src + p * a.H * (long long)a.W;
    long long best_i = (long long)y * a.k * a.W + (long long)x * a.k;
    float best = s[best_i];
    for (int dy = 0; dy < a.k; ++dy)
        for (int dx = 0; dx < a.k; ++dx) {
            long long j = (long long)(y * a.k + dy) * a.W + x * a.k + dx;
            float v = s[j];
            if (v > best || v != v) {
                best = v;
                best_i = j;
                if (v != v) { dy = a.k; break; }
            }
        }
    a.dst[i] = best;
    if (a.idx != nullptr) a.idx[i] = best_i;
}

// ---------------------------------------------------------------------------------------------- blur
// One pass of data/descriptors.blur (reflect padding, 2r+1 taps from the manifest) over P (H, W) planes:
// axis 0 = along W, 1 = along H. Accumulated in tap order, FMA (torch's depthwise conv kernel) or not.
struct BlurArgs {
    const float* src;
    float* dst;
    const float* k;
    long long P;
    int H, W, r, axis, fma;
};
__device__ __forceinline__ int reflect(int i, int n) {
    if (i < 0) return -i;
    if (i >= n) return 2 * n - 2 - i;
    return i;
}
extern "C" __global__ void sbtg_blur(const BlurArgs a) {
    long long i = gtid();
    const long long hw = (long long)a.H * a.W;
    if (i >= hw * a.P) return;
    long long p = i / hw;
    int yx = (int)(i - p * hw);
    int y = yx / a.W, x = yx - y * a.W;
    const float* s = a.src + p * hw;
    float acc = 0.0f;
    const int K = 2 * a.r + 1;
    for (int t = 0; t < K; ++t) {
        float v = a.axis == 0 ? s[(long long)y * a.W + reflect(x - a.r + t, a.W)] : s[(long long)reflect(y - a.r + t, a.H) * a.W + x];
        acc = a.fma ? fmaf(a.k[t], v, acc) : ADD(acc, MUL(a.k[t], v));
    }
    a.dst[i] = acc;
}

// ---------------------------------------------------------------------------------------------- bilinear
// F.interpolate(mode="bilinear", align_corners=False) of (P, h, w) to (P, H, W); `sy`, `sx` = torch's
// area_pixel_compute_scale (1/scale_factor, or in/out in fp32 for size=). fm 1 = torch's FMA contraction (measured).
struct BilinearArgs {
    const float* src;
    float* dst;
    long long P;
    int h, w, H, W;
    float sy, sx;
    int fm;
};
extern "C" __global__ void sbtg_bilinear(const BilinearArgs a) {
    long long i = gtid();
    const long long HW = (long long)a.H * a.W;
    if (i >= HW * a.P) return;
    long long p = i / HW;
    int r = (int)(i - p * HW);
    int Y = r / a.W, X = r - Y * a.W;
    const float* s = a.src + p * a.h * (long long)a.w;
    float fy = fmaf(a.sy, (float)Y + 0.5f, -0.5f), fx = fmaf(a.sx, (float)X + 0.5f, -0.5f);
    fy = fy < 0.0f ? 0.0f : fy;
    fx = fx < 0.0f ? 0.0f : fx;
    int y1 = (int)fy, x1 = (int)fx;
    int yp = y1 < a.h - 1 ? 1 : 0, xp = x1 < a.w - 1 ? 1 : 0;
    float l1 = SUB(fy, (float)y1), l0 = SUB(1.0f, l1);
    float k1 = SUB(fx, (float)x1), k0 = SUB(1.0f, k1);
    float va = s[(long long)y1 * a.w + x1], vb = s[(long long)y1 * a.w + x1 + xp];
    float vc = s[(long long)(y1 + yp) * a.w + x1], vd = s[(long long)(y1 + yp) * a.w + x1 + xp];
    float out;
    if (a.fm == 1) {
        float A = fmaf(k0, va, MUL(k1, vb)), B = fmaf(k0, vc, MUL(k1, vd));
        out = fmaf(l0, A, MUL(l1, B));
    } else {
        out = ADD(MUL(l0, ADD(MUL(k0, va), MUL(k1, vb))), MUL(l1, ADD(MUL(k0, vc), MUL(k1, vd))));
    }
    a.dst[i] = out;
}

// ---------------------------------------------------------------------------------------------- casts
// dtype codes as core/tensor.hpp DType: 0 f32, 1 f64, 2 i32, 3 i64, 4 u8, 5 bool, 6 i16.
struct CastArgs {
    const void* src;
    void* dst;
    long long n;
    int from, to;
};
__device__ __forceinline__ double cast_load(const void* p, long long i, int t) {
    switch (t) {
    case 0: return ((const float*)p)[i];
    case 1: return ((const double*)p)[i];
    case 2: return ((const int*)p)[i];
    case 3: return (double)((const long long*)p)[i];
    case 4: return ((const unsigned char*)p)[i];
    case 5: return ((const unsigned char*)p)[i] ? 1.0 : 0.0;
    default: return ((const short*)p)[i];
    }
}
extern "C" __global__ void sbtg_cast(const CastArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    // Integer -> integer stays exact (through long long); float -> integer truncates toward zero (torch .long()).
    if (a.from >= 2 && a.from != 5 && a.to >= 2) {
        long long v = a.from == 2 ? ((const int*)a.src)[i] : a.from == 3 ? ((const long long*)a.src)[i]
                    : a.from == 4 ? ((const unsigned char*)a.src)[i] : ((const short*)a.src)[i];
        switch (a.to) {
        case 2: ((int*)a.dst)[i] = (int)v; return;
        case 3: ((long long*)a.dst)[i] = v; return;
        case 4: ((unsigned char*)a.dst)[i] = (unsigned char)v; return;
        case 5: ((unsigned char*)a.dst)[i] = v != 0; return;
        default: ((short*)a.dst)[i] = (short)v; return;
        }
    }
    if (a.from == 0 && a.to == 3) { ((long long*)a.dst)[i] = (long long)((const float*)a.src)[i]; return; }
    if (a.from == 0 && a.to == 2) { ((int*)a.dst)[i] = (int)((const float*)a.src)[i]; return; }
    if (a.from == 3 && a.to == 0) { ((float*)a.dst)[i] = (float)((const long long*)a.src)[i]; return; }
    double v = cast_load(a.src, i, a.from);
    switch (a.to) {
    case 0: ((float*)a.dst)[i] = a.from == 1 ? (float)((const double*)a.src)[i] : (float)v; return;
    case 1: ((double*)a.dst)[i] = v; return;
    case 2: ((int*)a.dst)[i] = (int)v; return;
    case 3: ((long long*)a.dst)[i] = (long long)v; return;
    case 4: ((unsigned char*)a.dst)[i] = (unsigned char)(int)v; return;
    case 5: ((unsigned char*)a.dst)[i] = v != 0.0; return;
    default: ((short*)a.dst)[i] = (short)(int)v; return;
    }
}
