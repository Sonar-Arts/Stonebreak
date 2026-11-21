// Stage module "tiles_tile": world/tiles.py TileBuilder elementwise steps (block quantisation, the 1:1 ground, the
// coastal shelf, overview fields, plane assembly, surface-channel encoding). One IEEE op per torch op, in its order.
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_cmin(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_cmax(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return t_cmax(t_cmin(x, lo), hi); }

// torch.floor(x).clamp(lo, hi) [+ add]; mode 0: float out (+ add), 1: int64 out (.long(), add ignored)
struct QuantArgs {
    const float* x;
    void* y;
    long long n;
    float lo, hi, add;
    int mode;
};
extern "C" __global__ void tiles_quant(const QuantArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    float v = t_clamp(floorf(a.x[t]), a.lo, a.hi);
    if (a.mode == 0) ((float*)a.y)[t] = ADD(v, a.add);
    else ((long long*)a.y)[t] = (long long)v;
}

// TileBuilder._blocks_fine ground: keep = (1 - |carved - base| / KEEP).clamp(0, 1) (per pixel),
// ground = up(carved) + up(keep) * (fine - up(base)) over k x k blocks per pixel.
struct FineGroundArgs {
    const float* carved;
    const float* base;
    const float* fine;
    float* ground;
    int H, W, k;
    float inv_keep;
};
extern "C" __global__ void tiles_fine_ground(const FineGroundArgs a) {
    long long t = gtid();
    long long HW = (long long)a.H * a.k * a.W * a.k;
    if (t >= HW) return;
    int Wf = a.W * a.k;
    int r = (int)(t / Wf), c = (int)(t - (long long)r * Wf);
    long long p = (long long)(r / a.k) * a.W + c / a.k;
    float cv = a.carved[p], bv = a.base[p];
    float keep = t_clamp(SUB(1.0f, MUL(fabsf(SUB(cv, bv)), a.inv_keep)), 0.0f, 1.0f);
    a.ground[t] = ADD(cv, MUL(keep, SUB(a.fine[t], bv)));
}

// swamp_relief.flatten(height, weight, table): where(height >= 0, height + weight * (table - height), height)
struct FlattenArgs {
    const float* h;
    const float* w;
    const float* tb;
    float* y;
    long long n;
};
extern "C" __global__ void tiles_sr_flatten(const FlattenArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float h = a.h[i];
    a.y[i] = h >= 0.0f ? ADD(h, MUL(a.w[i], SUB(a.tb[i], h))) : h;
}

// TileBuilder._overview: wet = (peak >= thr) & (elev > 0.5); flow = where(wet, D8_OCTANT[d8[idx].clamp(0, 8)], NO_FLOW);
// hb / hb + 1 / none planes come from the same pass. idx = max_pool2d's flat indices into d8 (H x W source).
struct OverviewArgs {
    const float* peak;
    const long long* idx;
    const long long* d8;    // may be null
    const float* elev;
    bool* wet;
    long long* flow;
    long long n;
    float thr;
    long long no_flow;
    long long oct[9];
};
extern "C" __global__ void tiles_overview_flow(const OverviewArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    bool w = (a.peak[i] >= a.thr) && (a.elev[i] > 0.5f);
    a.wet[i] = w;
    long long f = a.no_flow;
    if (a.d8 != nullptr) {
        long long cls = a.d8[a.idx[i]];
        cls = cls < 0 ? 0 : (cls > 8 ? 8 : cls);
        if (w) f = a.oct[cls];
    }
    a.flow[i] = f;
}
// overview lakes: elev_m = classify_elev(elev_m, (hb < sea_level) & ~sea_pooled) (sea_pooled = max_pool(sea, k) > 0.5)
struct OvLakeArgs {
    float* elev;
    const long long* hb;
    const float* seap;
    long long n, sea_level;
    float lake;
};
extern "C" __global__ void tiles_overview_lake(const OvLakeArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    if (a.hb[i] < a.sea_level && !(a.seap[i] > 0.5f)) a.elev[i] = t_cmin(a.elev[i], a.lake);
}
struct BoolToF32Args {
    const bool* x;
    float* y;
    long long n;
};
extern "C" __global__ void tiles_bool_f32(const BoolToF32Args a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.y[i] = a.x[i] ? 1.0f : 0.0f;
}

// TileBuilder._planes: (7, h, w) int16. Inputs on frames (crop offsets): height/level/floor/roof/flow/carve int64,
// biome int16, wet bool. Each is read at (r + R0, c + C0) of its own (SW) row stride.
struct PlaneIn {
    const void* p;
    int SW, R0, C0, dt;   // dt: 3 int64, 6 int16, 5 bool
};
struct PlanesArgs {
    PlaneIn height, biome, level, wet, floor_, roof, flow, carve;
    short* out;
    int h, w, has_carve;
    long long bottom, top, sea;
};
__device__ __forceinline__ long long rd(const PlaneIn& q, int r, int c) {
    long long i = (long long)(r + q.R0) * q.SW + (c + q.C0);
    if (q.dt == 6) return ((const short*)q.p)[i];
    if (q.dt == 5) return ((const bool*)q.p)[i] ? 1 : 0;
    if (q.dt == 2) return ((const int*)q.p)[i];
    return ((const long long*)q.p)[i];
}
extern "C" __global__ void tiles_planes(const PlanesArgs a) {
    long long t = gtid();
    long long hw = (long long)a.h * a.w;
    if (t >= hw) return;
    int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    long long height = rd(a.height, r, c), level = rd(a.level, r, c);
    height = height < a.bottom ? a.bottom : (height > a.top ? a.top : height);
    level = level < a.bottom ? a.bottom : (level > a.top ? a.top : level);
    long long fl = rd(a.floor_, r, c);
    long long water = height < a.sea ? a.sea : -1;
    bool river_wet = rd(a.wet, r, c) && ((level > height) || (fl >= 0));
    if (river_wet) water = water > level ? water : level;
    long long carve = a.has_carve ? rd(a.carve, r, c) : 0;
    if (carve >= 32768) carve -= 65536;
    a.out[t] = (short)height;
    a.out[hw + t] = (short)rd(a.biome, r, c);
    a.out[2 * hw + t] = (short)water;
    a.out[3 * hw + t] = (short)fl;
    a.out[4 * hw + t] = (short)rd(a.roof, r, c);
    a.out[5 * hw + t] = (short)rd(a.flow, r, c);
    a.out[6 * hw + t] = (short)carve;
}

// surface_channels.encode: q = rint((v - offset) / scale) (numpy fp32, true division), NaN -> 255, clip 0..254.
struct EncodeArgs {
    const float* v;
    unsigned char* out;
    long long n;
    float offset, scale;
};
extern "C" __global__ void tiles_encode(const EncodeArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float v = a.v[i];
    if (v != v) {
        a.out[i] = 255;
        return;
    }
    float q = rintf(DIV(SUB(v, a.offset), a.scale));
    q = q < 0.0f ? 0.0f : (q > 254.0f ? 254.0f : q);
    a.out[i] = (unsigned char)q;
}

// y = x + add (int64), or y = add when x is null (torch.full)
struct I64Args {
    const long long* x;
    long long* y;
    long long n, add;
};
extern "C" __global__ void tiles_i64(const I64Args a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.y[i] = a.x != nullptr ? a.x[i] + a.add : a.add;
}

// ============================================================ FX4 block look (world/tiles.py, end of file)
// TileBuilder._dry(cols) and despeckle's `ok`: dry land whose 8 neighbours are dry land (outside the frame = wet),
// never on the frame's outer ring.
struct DryArgs {
    const long long* height;
    const long long* water;
    const long long* floor_;
    const long long* roof;
    const long long* flow;
    const bool* wet;
    const bool* dry;     // given: despeckle's own `dry` argument (the columns above are then unused)
    bool* ok;
    int H, W;
    long long sea, no_flow, no_tunnel;
};
__device__ __forceinline__ bool tiles_is_dry(const DryArgs& a, long long i) {
    if (a.dry != nullptr) return a.dry[i];
    long long h = a.height[i];
    return !a.wet[i] && a.flow[i] == a.no_flow && a.floor_[i] == a.no_tunnel && a.roof[i] == a.no_tunnel &&
           h >= a.sea && a.water[i] <= h;
}
extern "C" __global__ void tiles_dry_ok(const DryArgs a) {
    long long t = gtid();
    if (t >= (long long)a.H * a.W) return;
    int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W);
    int ok = (r > 0 && c > 0 && r < a.H - 1 && c < a.W - 1) ? 1 : 0;
    for (int u = -1; u <= 1 && ok; ++u)
        for (int v = -1; v <= 1 && ok; ++v) ok = tiles_is_dry(a, (long long)(r + u) * a.W + (c + v)) ? 1 : 0;
    a.ok[t] = ok != 0;
}
// despeckle: mode 0 = the needle / hole pass (on the original heights), mode 1 = one flat-rule pass over `src`.
// Neighbours replicate at the frame edge (only `ok` columns change, never on the edge).
struct DespeckleArgs {
    const long long* src;
    long long* dst;
    const bool* ok;
    int H, W, mode;
    long long up, down;
};
extern "C" __global__ void tiles_despeckle(const DespeckleArgs a) {
    long long t = gtid();
    if (t >= (long long)a.H * a.W) return;
    int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W);
    const long long h = a.src[t];
    if (!a.ok[t]) {
        a.dst[t] = h;
        return;
    }
    auto at = [&](int dr, int dc) {
        int rr = r + dr, cc = c + dc;
        rr = rr < 0 ? 0 : (rr > a.H - 1 ? a.H - 1 : rr);
        cc = cc < 0 ? 0 : (cc > a.W - 1 ? a.W - 1 : cc);
        return a.src[(long long)rr * a.W + cc];
    };
    const long long n4[4] = {at(-1, 0), at(1, 0), at(0, -1), at(0, 1)};
    long long mx4 = n4[0], mn4 = n4[0];
    for (int q = 1; q < 4; ++q) {
        mx4 = n4[q] > mx4 ? n4[q] : mx4;
        mn4 = n4[q] < mn4 ? n4[q] : mn4;
    }
    if (a.mode == 0) {
        long long out = (h - mx4 >= a.up) ? mx4 + 1 : h;
        out = (mn4 - h >= a.down) ? mn4 - 1 : out;
        a.dst[t] = out;
        return;
    }
    const long long n8[8] = {n4[0], n4[1], n4[2], n4[3], at(-1, -1), at(-1, 1), at(1, -1), at(1, 1)};
    long long lo3 = h, hi3 = h;
    for (int q = 0; q < 8; ++q) {
        lo3 = n8[q] < lo3 ? n8[q] : lo3;
        hi3 = n8[q] > hi3 ? n8[q] : hi3;
    }
    const long long other = h == lo3 ? hi3 : lo3;
    int same8 = 0, other4 = 0;
    for (int q = 0; q < 8; ++q) same8 += n8[q] == h;
    for (int q = 0; q < 4; ++q) other4 += n4[q] == other;
    const long long dh = h - mx4;
    const int single = (mx4 == mn4) & ((dh == 1) | (dh == -1));
    const int domino = ((hi3 - lo3) == 1) & (same8 <= 1) & (other4 >= 3);
    a.dst[t] = single ? mx4 : (domino ? other : h);
}

// alpine_elevation(regional, ground): w = smooth01((reg - L0) / (L1 - L0)); loc = reg + w * (ground.clamp_min(0) - reg);
// loc + (GAIN - 1) * (loc - BASE).clamp_min(0). mode 1 (overview): y = t0 - LAPSE * (that - reg) with reg clamped >= 0.
struct AlpineArgs {
    const float* regional;
    const float* ground;
    const float* t0;     // mode 1
    float* y;
    long long n;
    float l0, inv_span, gain1, base, lapse;
    int mode;
};
extern "C" __global__ void tiles_alpine_elev(const AlpineArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float reg = a.regional[i];
    if (a.mode == 1) reg = t_cmin(reg, 0.0f);
    float s = t_clamp(MUL(SUB(reg, a.l0), a.inv_span), 0.0f, 1.0f);
    float w = MUL(MUL(s, s), SUB(3.0f, MUL(2.0f, s)));
    float loc = ADD(reg, MUL(w, SUB(t_cmin(a.ground[i], 0.0f), reg)));
    float e = ADD(loc, MUL(a.gain1, t_cmin(SUB(loc, a.base), 0.0f)));
    a.y[i] = a.mode == 1 ? SUB(a.t0[i], MUL(a.lapse, SUB(e, reg))) : e;
}
