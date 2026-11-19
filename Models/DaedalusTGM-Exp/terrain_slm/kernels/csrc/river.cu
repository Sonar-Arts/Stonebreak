// One step of the river pipeline's two fixed-point loops (river/geometry.py), each the same arithmetic as the torch
// step it replaces, one launch instead of ~30, so the Python loop can run several steps per convergence check:
//
//   mono_step   monotone_downstream: best = lv; for each 8-neighbour k (in _N8 order) flagged upstream in ups (bit k):
//               best = where(up, minimum(best, lv[neighbour] or +inf past the edge), best). torch.minimum's NaN rule:
//               a NaN operand wins, else fminf.
//   grow_step   Centrelines' hysteresis growth: nxt = max_pool2d(grown, 3, 1, 1) * weak on 0/1 floats.
//
// Each step writes changed[step] = 1 when some element differs from its input (`!(a == b)`: torch.equal's rule, so
// NaN counts as a change and -0 == +0 does not). Self-contained: no includes, no torch (cuda_river.py mirrors the
// argument structs).

struct MonoArgs {
    const float* lv;            // (H, W) in
    const unsigned char* ups;   // (H, W) bit k: neighbour k is upstream (and the pixel is on the line)
    float* out;                 // (H, W) out
    int* changed;               // changed[step]
    int H, W, step;
};

__device__ __forceinline__ float torch_minimum(float a, float b) {
    if (a != a) return a;
    if (b != b) return b;
    return fminf(a, b);
}

extern "C" __global__ void mono_step(const MonoArgs a) {
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= a.H * a.W) return;
    const int y = idx / a.W, x = idx % a.W;
    const float v0 = a.lv[idx];
    float best = v0;
    const unsigned int u = a.ups[idx];
    if (u) {
        const int di[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
        const int dj[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
#pragma unroll
        for (int k = 0; k < 8; ++k) {
            if (u >> k & 1u) {
                const int yy = y + di[k], xx = x + dj[k];
                const float s = (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) ? a.lv[yy * a.W + xx] : __int_as_float(0x7f800000);
                best = torch_minimum(best, s);
            }
        }
    }
    a.out[idx] = best;
    if (!(best == v0)) a.changed[a.step] = 1;
}

struct GrowArgs {
    const float* grown;         // (H, W) 0/1
    const float* weak;          // (H, W) 0/1
    float* out;                 // (H, W)
    int* changed;
    int H, W, step;
};

extern "C" __global__ void grow_step(const GrowArgs a) {
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= a.H * a.W) return;
    const int y = idx / a.W, x = idx % a.W;
    float m = -__int_as_float(0x7f800000);
    for (int dy = -1; dy <= 1; ++dy) {
        const int yy = y + dy;
        if (yy < 0 || yy >= a.H) continue;
        for (int dx = -1; dx <= 1; ++dx) {
            const int xx = x + dx;
            if (xx < 0 || xx >= a.W) continue;
            m = fmaxf(m, a.grown[yy * a.W + xx]);
        }
    }
    const float v = __fmul_rn(m, a.weak[idx]);
    a.out[idx] = v;
    if (!(v == a.grown[idx])) a.changed[a.step] = 1;
}
