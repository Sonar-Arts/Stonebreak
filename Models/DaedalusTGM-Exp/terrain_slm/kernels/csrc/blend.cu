// Overlap-add of canonical windows into a request grid (the generator's cross-fades: planner cells, relief trend,
// drainage, detail samples, SR pixels, climate grid), bit-identical to the torch loop it replaces:
//
//     out = zeros(C, H, W)
//     for each window k (in loop order):  out[:, r0:r1, s0:s1] += win_k[:, ...] * fade[...]
//
// i.e. per element an fp32 product (rounded) added in fp32 to the running sum, windows in list order, the sum
// starting at +0. __fmul_rn / __fadd_rn keep NVRTC from contracting the pair into an FMA (which would round once).
// One thread per output element; windows are (C, EH, EW) contiguous fp32 at origin (a0, b0) in out's coordinates
// (negative or past the edge is fine: only the overlap is read). A call with more than BLEND_MAXW windows runs as
// several launches, each continuing the previous sum (accumulate = 1), which keeps the order.
//
// Self-contained: no includes, no torch; the struct below is the whole interface (cuda_blend.py mirrors it).
#define BLEND_MAXW 64

struct BlendArgs {
    float* out;                         // (C, H, W)
    const float* fade;                  // (EH, EW)
    const float* win[BLEND_MAXW];       // (C, EH, EW) each
    int C, H, W, EH, EW;
    int n;                              // windows in this launch
    int accumulate;                     // 0: the sum starts at +0 (first launch); 1: continue out's value
    int a0[BLEND_MAXW];
    int b0[BLEND_MAXW];
};

extern "C" __global__ void blend_kernel(const BlendArgs a) {
    const long long total = (long long)a.C * a.H * a.W;
    const long long idx = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= total) return;
    const int x = (int)(idx % a.W);
    const int y = (int)((idx / a.W) % a.H);
    const int c = (int)(idx / ((long long)a.W * a.H));
    const long long plane = (long long)a.EH * a.EW;
    float acc = a.accumulate ? a.out[idx] : 0.0f;
    for (int k = 0; k < a.n; ++k) {
        const int ry = y - a.a0[k];
        const int rx = x - a.b0[k];
        if ((unsigned)ry < (unsigned)a.EH && (unsigned)rx < (unsigned)a.EW) {
            const long long o = (long long)ry * a.EW + rx;
            acc = __fadd_rn(acc, __fmul_rn(a.win[k][c * plane + o], a.fade[o]));
        }
    }
    a.out[idx] = acc;
}
