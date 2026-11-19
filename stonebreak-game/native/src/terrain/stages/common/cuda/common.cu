// Stage module "common": small elementwise kernels shared by several stages. Bit-parity rules: core/cuda/sbtg_core.cu.
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_clamp_min(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }

// generator.soft_cap_peaks: d = (e - KNEE).clamp_min(0); e - d + d / (1 + d / SPAN)
// (torch: "/ python scalar" = * fp32 reciprocal; "/ tensor" = true division).
struct SoftCapArgs {
    const float* src;
    float* dst;
    long long n;
    float knee, inv_span;
};
extern "C" __global__ void sbtg_soft_cap(const SoftCapArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float e = a.src[i];
    float d = t_clamp_min(SUB(e, a.knee), 0.0f);
    a.dst[i] = ADD(SUB(e, d), DIV(d, ADD(1.0f, MUL(d, a.inv_span))));
}
