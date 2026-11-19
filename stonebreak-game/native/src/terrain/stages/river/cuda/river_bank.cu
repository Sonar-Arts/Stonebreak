// Stage module "river_bank": the learned bank model (river/banks.py BankNet, a small conv UNet) in plain fp32.
//
// Every 3x3 conv (padding 1, stride 1 or 2) is one kernel with the layer's fused extras: SiLU on its input
// (pre-activation), a nearest x2 upsample of its input, a second input concatenated after the first (merge), a
// residual added to its output. Each output accumulates input channels in order, the 9 taps of each in row-major
// order, with FMA, then + bias, then residual + that: the summation torch's fp32 (non-TF32) cuDNN convolution uses here
// (measured: bit-identical to the torch forward with torch.backends.cudnn.allow_tf32 = False). torch's default runs
// these convolutions in TF32, which no CUDA-core kernel reproduces; tests/test_native_river.py measures that gap.
#define TH 16       // output rows per block
#define COG 16      // output channels per block
#define CIC 4       // input channels per shared-memory chunk

__device__ __forceinline__ float silu(float x) { return x / (1.0f + expf(-x)); }

struct ConvArgs {
    const float* in1;       // (C1, H1, W1); read at (y >> up, x >> up) of the (Hin, Win) input grid
    const float* in2;       // (C2, Hin, Win) or nullptr: channels C1.. of the conv input
    const float* w;         // (Co, C1 + C2, 3, 3)
    const float* b;         // (Co)
    const float* res;       // (Co, Ho, Wo) or nullptr: added to the output
    float* out;             // (Co, Ho, Wo)
    int C1, C2, H1, W1, Hin, Win, Co, Ho, Wo, stride, up, silu_in;
};

// 256 threads: 16 rows x 16 column groups of PX consecutive output pixels; S = stride.
template <int S, int PX>
__device__ __forceinline__ void conv3(const ConvArgs& a, float* sm) {
    constexpr int TW = 16 * PX;
    constexpr int PH = TH * S + 2, PW = TW * S + 2;
    float* patch = sm;                          // CIC * PH * PW
    float* wsm = sm + CIC * PH * PW;            // COG * CIC * 9
    const int tilesx = (a.Wo + TW - 1) / TW;
    const int ty0 = (blockIdx.x / tilesx) * TH, tx0 = (blockIdx.x % tilesx) * TW;
    const int co0 = blockIdx.y * COG;
    const int t = threadIdx.x;
    const int ry = t / 16, rx = (t % 16) * PX;
    const int oy = ty0 + ry, ox0 = tx0 + rx;
    const int Ci = a.C1 + a.C2;
    float acc[PX][COG];
#pragma unroll
    for (int p = 0; p < PX; ++p)
#pragma unroll
        for (int k = 0; k < COG; ++k) acc[p][k] = 0.0f;
    const int iy0 = ty0 * S - 1, ix0 = tx0 * S - 1;
    for (int c0 = 0; c0 < Ci; c0 += CIC) {
        const int nc = min(CIC, Ci - c0);
        __syncthreads();
        for (int e = t; e < nc * PH * PW; e += blockDim.x) {
            int c = e / (PH * PW), r = e % (PH * PW);
            int py = r / PW, px = r % PW;
            int iy = iy0 + py, ix = ix0 + px;
            float v = 0.0f;
            if (iy >= 0 && iy < a.Hin && ix >= 0 && ix < a.Win) {
                int ch = c0 + c;
                if (ch < a.C1) {
                    v = a.in1[((long long)ch * a.H1 + (iy >> a.up)) * a.W1 + (ix >> a.up)];
                    if (a.silu_in) v = silu(v);
                } else {
                    v = a.in2[((long long)(ch - a.C1) * a.Hin + iy) * a.Win + ix];
                }
            }
            patch[e] = v;
        }
        for (int e = t; e < COG * nc * 9; e += blockDim.x) {
            int k = e / (nc * 9), r = e % (nc * 9);
            int co = co0 + k;
            wsm[e] = co < a.Co ? a.w[((long long)co * Ci + c0) * 9 + r] : 0.0f;
        }
        __syncthreads();
        for (int c = 0; c < nc; ++c) {
            constexpr int NX = (PX - 1) * S + 3;
            float v[3][NX];
#pragma unroll
            for (int dy = 0; dy < 3; ++dy)
#pragma unroll
                for (int dx = 0; dx < NX; ++dx) v[dy][dx] = patch[(c * PH + ry * S + dy) * PW + rx * S + dx];
#pragma unroll
            for (int k = 0; k < COG; ++k) {
                float wk[9];
#pragma unroll
                for (int q = 0; q < 9; ++q) wk[q] = wsm[(k * nc + c) * 9 + q];
#pragma unroll
                for (int p = 0; p < PX; ++p) {
                    float s = acc[p][k];
#pragma unroll
                    for (int dy = 0; dy < 3; ++dy)
#pragma unroll
                        for (int dx = 0; dx < 3; ++dx) s = fmaf(wk[dy * 3 + dx], v[dy][p * S + dx], s);
                    acc[p][k] = s;
                }
            }
        }
    }
    if (oy >= a.Ho) return;
#pragma unroll
    for (int k = 0; k < COG; ++k) {
        int co = co0 + k;
        if (co >= a.Co) break;
#pragma unroll
        for (int p = 0; p < PX; ++p) {
            int ox = ox0 + p;
            if (ox >= a.Wo) break;
            long long o = ((long long)co * a.Ho + oy) * a.Wo + ox;
            float y = __fadd_rn(acc[p][k], a.b[co]);
            if (a.res) y = __fadd_rn(a.res[o], y);
            a.out[o] = y;
        }
    }
}

extern "C" __global__ void __launch_bounds__(256) rb_conv3_s1(const ConvArgs a) {
    extern __shared__ float sm[];
    conv3<1, 4>(a, sm);
}
extern "C" __global__ void __launch_bounds__(256) rb_conv3_s2(const ConvArgs a) {
    extern __shared__ float sm[];
    conv3<2, 2>(a, sm);
}
