// Stage module "tiles_biomes": biomes.py (rule classifier, swamp overlay, majority filters), the classifier's
// FastNoiseLite fields, world/ocean.py (sea growth) and the tile assembly kernels of world/tiles.py.
// Bit parity: one IEEE fp32 op per torch / numpy op, in their order (no contraction: __f*_rn everywhere).
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
// torch clamp family (NaN propagates)
__device__ __forceinline__ float t_cmin(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_cmax(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return t_cmax(t_cmin(x, lo), hi); }
__device__ __forceinline__ int iclamp(int x, int lo, int hi) { return x < lo ? lo : (x > hi ? hi : x); }

// ============================================================ FastNoiseLite (Cpp, Auburn @ 8c45924, MIT): Perlin 2D + FBm
// pyfastnoiselite 0.0.7 builds it with FNfloat = float and no FMA (x86-64 baseline): every op below is one rounding.
__constant__ float kGrad2D[256] = {
0.130526192220052f, 0.99144486137381f, 0.38268343236509f, 0.923879532511287f, 0.608761429008721f, 0.793353340291235f, 0.793353340291235f, 0.608761429008721f,
0.923879532511287f, 0.38268343236509f, 0.99144486137381f, 0.130526192220051f, 0.99144486137381f, -0.130526192220051f, 0.923879532511287f, -0.38268343236509f,
0.793353340291235f, -0.60876142900872f, 0.608761429008721f, -0.793353340291235f, 0.38268343236509f, -0.923879532511287f, 0.130526192220052f, -0.99144486137381f,
-0.130526192220052f, -0.99144486137381f, -0.38268343236509f, -0.923879532511287f, -0.608761429008721f, -0.793353340291235f, -0.793353340291235f, -0.608761429008721f,
-0.923879532511287f, -0.38268343236509f, -0.99144486137381f, -0.130526192220052f, -0.99144486137381f, 0.130526192220051f, -0.923879532511287f, 0.38268343236509f,
-0.793353340291235f, 0.608761429008721f, -0.608761429008721f, 0.793353340291235f, -0.38268343236509f, 0.923879532511287f, -0.130526192220052f, 0.99144486137381f,
0.130526192220052f, 0.99144486137381f, 0.38268343236509f, 0.923879532511287f, 0.608761429008721f, 0.793353340291235f, 0.793353340291235f, 0.608761429008721f,
0.923879532511287f, 0.38268343236509f, 0.99144486137381f, 0.130526192220051f, 0.99144486137381f, -0.130526192220051f, 0.923879532511287f, -0.38268343236509f,
0.793353340291235f, -0.60876142900872f, 0.608761429008721f, -0.793353340291235f, 0.38268343236509f, -0.923879532511287f, 0.130526192220052f, -0.99144486137381f,
-0.130526192220052f, -0.99144486137381f, -0.38268343236509f, -0.923879532511287f, -0.608761429008721f, -0.793353340291235f, -0.793353340291235f, -0.608761429008721f,
-0.923879532511287f, -0.38268343236509f, -0.99144486137381f, -0.130526192220052f, -0.99144486137381f, 0.130526192220051f, -0.923879532511287f, 0.38268343236509f,
-0.793353340291235f, 0.608761429008721f, -0.608761429008721f, 0.793353340291235f, -0.38268343236509f, 0.923879532511287f, -0.130526192220052f, 0.99144486137381f,
0.130526192220052f, 0.99144486137381f, 0.38268343236509f, 0.923879532511287f, 0.608761429008721f, 0.793353340291235f, 0.793353340291235f, 0.608761429008721f,
0.923879532511287f, 0.38268343236509f, 0.99144486137381f, 0.130526192220051f, 0.99144486137381f, -0.130526192220051f, 0.923879532511287f, -0.38268343236509f,
0.793353340291235f, -0.60876142900872f, 0.608761429008721f, -0.793353340291235f, 0.38268343236509f, -0.923879532511287f, 0.130526192220052f, -0.99144486137381f,
-0.130526192220052f, -0.99144486137381f, -0.38268343236509f, -0.923879532511287f, -0.608761429008721f, -0.793353340291235f, -0.793353340291235f, -0.608761429008721f,
-0.923879532511287f, -0.38268343236509f, -0.99144486137381f, -0.130526192220052f, -0.99144486137381f, 0.130526192220051f, -0.923879532511287f, 0.38268343236509f,
-0.793353340291235f, 0.608761429008721f, -0.608761429008721f, 0.793353340291235f, -0.38268343236509f, 0.923879532511287f, -0.130526192220052f, 0.99144486137381f,
0.130526192220052f, 0.99144486137381f, 0.38268343236509f, 0.923879532511287f, 0.608761429008721f, 0.793353340291235f, 0.793353340291235f, 0.608761429008721f,
0.923879532511287f, 0.38268343236509f, 0.99144486137381f, 0.130526192220051f, 0.99144486137381f, -0.130526192220051f, 0.923879532511287f, -0.38268343236509f,
0.793353340291235f, -0.60876142900872f, 0.608761429008721f, -0.793353340291235f, 0.38268343236509f, -0.923879532511287f, 0.130526192220052f, -0.99144486137381f,
-0.130526192220052f, -0.99144486137381f, -0.38268343236509f, -0.923879532511287f, -0.608761429008721f, -0.793353340291235f, -0.793353340291235f, -0.608761429008721f,
-0.923879532511287f, -0.38268343236509f, -0.99144486137381f, -0.130526192220052f, -0.99144486137381f, 0.130526192220051f, -0.923879532511287f, 0.38268343236509f,
-0.793353340291235f, 0.608761429008721f, -0.608761429008721f, 0.793353340291235f, -0.38268343236509f, 0.923879532511287f, -0.130526192220052f, 0.99144486137381f,
0.130526192220052f, 0.99144486137381f, 0.38268343236509f, 0.923879532511287f, 0.608761429008721f, 0.793353340291235f, 0.793353340291235f, 0.608761429008721f,
0.923879532511287f, 0.38268343236509f, 0.99144486137381f, 0.130526192220051f, 0.99144486137381f, -0.130526192220051f, 0.923879532511287f, -0.38268343236509f,
0.793353340291235f, -0.60876142900872f, 0.608761429008721f, -0.793353340291235f, 0.38268343236509f, -0.923879532511287f, 0.130526192220052f, -0.99144486137381f,
-0.130526192220052f, -0.99144486137381f, -0.38268343236509f, -0.923879532511287f, -0.608761429008721f, -0.793353340291235f, -0.793353340291235f, -0.608761429008721f,
-0.923879532511287f, -0.38268343236509f, -0.99144486137381f, -0.130526192220052f, -0.99144486137381f, 0.130526192220051f, -0.923879532511287f, 0.38268343236509f,
-0.793353340291235f, 0.608761429008721f, -0.608761429008721f, 0.793353340291235f, -0.38268343236509f, 0.923879532511287f, -0.130526192220052f, 0.99144486137381f,
0.38268343236509f, 0.923879532511287f, 0.923879532511287f, 0.38268343236509f, 0.923879532511287f, -0.38268343236509f, 0.38268343236509f, -0.923879532511287f,
-0.38268343236509f, -0.923879532511287f, -0.923879532511287f, -0.38268343236509f, -0.923879532511287f, 0.38268343236509f, -0.38268343236509f, 0.923879532511287f
};
struct FnlCfg {
    int seed;
    float freq;
    int octaves;
    float lac, gain, weighted, bounding;
};
__device__ __forceinline__ int fnl_floor(float f) { return f >= 0 ? (int)f : (int)f - 1; }
__device__ __forceinline__ float fnl_quintic(float t) {
    return MUL(MUL(MUL(t, t), t), ADD(MUL(t, SUB(MUL(t, 6.0f), 15.0f)), 10.0f));
}
__device__ __forceinline__ float fnl_lerp(float a, float b, float t) { return ADD(a, MUL(t, SUB(b, a))); }
__device__ __forceinline__ float fnl_grad(int seed, int xp, int yp, float xd, float yd) {
    int h = (int)((unsigned)(seed ^ xp ^ yp) * 0x27d4eb2du);
    h ^= h >> 15;
    h &= 127 << 1;
    return ADD(MUL(xd, kGrad2D[h]), MUL(yd, kGrad2D[h | 1]));
}
__device__ float fnl_perlin(int seed, float x, float y) {
    int x0 = fnl_floor(x), y0 = fnl_floor(y);
    float xd0 = SUB(x, (float)x0), yd0 = SUB(y, (float)y0);
    float xd1 = SUB(xd0, 1.0f), yd1 = SUB(yd0, 1.0f);
    float xs = fnl_quintic(xd0), ys = fnl_quintic(yd0);
    const unsigned PX = 501125321u, PY = 1136930381u;
    int xa = (int)((unsigned)x0 * PX), ya = (int)((unsigned)y0 * PY);
    int xb = (int)((unsigned)xa + PX), yb = (int)((unsigned)ya + PY);
    float xf0 = fnl_lerp(fnl_grad(seed, xa, ya, xd0, yd0), fnl_grad(seed, xb, ya, xd1, yd0), xs);
    float xf1 = fnl_lerp(fnl_grad(seed, xa, yb, xd0, yd1), fnl_grad(seed, xb, yb, xd1, yd1), xs);
    return MUL(fnl_lerp(xf0, xf1, ys), 1.4247691104677813f);
}
__device__ float fnl_fbm(const FnlCfg& c, float x, float y) {
    x = MUL(x, c.freq);
    y = MUL(y, c.freq);
    int seed = c.seed;
    float sum = 0.0f, amp = c.bounding;
    for (int i = 0; i < c.octaves; ++i) {
        float n = fnl_perlin(seed++, x, y);
        sum = ADD(sum, MUL(n, amp));
        float m = ADD(n, 1.0f);
        m = m < 2.0f ? m : 2.0f;
        amp = MUL(amp, fnl_lerp(1.0f, MUL(m, 0.5f), c.weighted));
        x = MUL(x, c.lac);
        y = MUL(y, c.lac);
        amp = MUL(amp, c.gain);
    }
    return sum;
}
// biomes._noise(n, i0, j0, h, w, step) for nf fields: out (nf, h, w); x = col * step + j0, y = row * step + i0 (fp32).
struct NoiseArgs {
    float* out;
    const float* xs;     // optional per-column / per-row coordinates (classifier_noise's np.arange fill)
    const float* ys;
    long long hw;
    int h, w, nf;
    float i0, j0, step;
    FnlCfg cfg[8];
};
extern "C" __global__ void tiles_noise(const NoiseArgs a) {
    long long t = gtid();
    if (t >= a.hw * a.nf) return;
    int f = (int)(t / a.hw);
    long long p = t - (long long)f * a.hw;
    int r = (int)(p / a.w), c = (int)(p - (long long)r * a.w);
    float x = a.xs ? a.xs[c] : ADD(MUL((float)c, a.step), a.j0);
    float y = a.ys ? a.ys[r] : ADD(MUL((float)r, a.step), a.i0);
    a.out[t] = fnl_fbm(a.cfg[f], x, y);
}

// ============================================================ biomes._compute_climate_vars
struct CV {
    float tree_moisture, growing_season, coldest_month;
    bool hard_frost, tropical;
};
__device__ CV climate_vars(float temp, float t_season, float precip, float p_cv) {
    CV o;
    float t_std = MUL(t_season, 1.0f / 100.0f);
    float t_eff = t_cmin(ADD(temp, MUL(0.5f, t_std)), 0.0f);
    float pet = t_cmin(ADD(ADD(250.0f, MUL(25.0f, t_eff)), MUL(0.7f, MUL(t_eff, t_eff))), 250.0f);
    float aridity = DIV(precip, t_cmin(pet, 1.0f));
    float pen = SUB(1.0f, MUL(0.35f, t_cmax(MUL(p_cv, 1.0f / 100.0f), 1.0f)));
    o.tree_moisture = MUL(aridity, pen);
    float amplitude = MUL(t_std, 1.414f);
    float x = DIV(SUB(5.0f, temp), t_cmin(amplitude, 0.1f));
    float gs = MUL(365.0f, SUB(0.5f, MUL(asinf(t_clamp(x, -1.0f, 1.0f)), 1.0f / 3.14159f)));
    if (x >= 1.0f) gs = 0.0f;
    if (x <= -1.0f) gs = 365.0f;
    o.growing_season = gs;
    o.coldest_month = SUB(temp, MUL(2.0f, t_std));
    o.hard_frost = o.coldest_month < -10.0f;
    o.tropical = (temp >= 18.0f) && (t_std < 5.0f);
    return o;
}

// Constants of biomes.py (module level; the manifest's live values).
struct BiomeK {
    float hyper_arid, snow_cover_we, snow_cover_tmax;
    float sw_min_moist, sw_inv_moist_span, sw_min_temp, sw_snow_margin, sw_max_reg, sw_max_elev, sw_dry, sw_wet_minus_dry;
    float soil0, inv_soil_span, soil_min, bog_minus_min;
    short id_plains, id_snowy_plains, id_desert, id_swamp, id_forest, id_taiga, id_snowy_taiga, id_savanna,
        id_windswept, id_jungle, id_grove, id_snowy_slopes, id_frozen_peaks, id_stony_peaks, id_forest_sparse,
        id_taiga_sparse, id_snowy_taiga_sparse;
    short ocean_frozen, ocean_cold, ocean_warm, ocean_normal;
};

// A (SH, SW) source frame read at output (r, c) + origin (R0, C0).
struct Frame {
    int SH, SW, R0, C0;
};
__device__ __forceinline__ long long fidx(const Frame& f, int r, int c) {
    return (long long)(f.R0 + r) * f.SW + (f.C0 + c);
}

// ============================================================ biomes._classify_biome
// elev / climate: frame f (climate C planes of SH x SW); elev_padded(r + a, c + b) = epad[clamp(fpad.R0 + r + a - 1)]
// [clamp(fpad.C0 + c + b - 1)] (replicate: the sidecar path pads the frame, the others read the frame's own ring).
// noise: (5, h, w) at the output's columns: temp, temp_fine, precip, snow, snow_fine.
struct ClassifyArgs {
    const float* elev;
    const float* clim;
    const float* epad;   // elev_padded source, frame fpad
    const float* noise;
    short* out;
    Frame f, fpad;
    int h, w, C;
    float inv_px;   // 1 / pixel_size_m (fp32 reciprocal: torch "/ python scalar")
    BiomeK k;
};
extern "C" __global__ void tiles_classify(const ClassifyArgs a) {
    long long t = gtid();
    long long hw = (long long)a.h * a.w;
    if (t >= hw) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const BiomeK& K = a.k;
    const long long fp = (long long)a.f.SH * a.f.SW;
    const long long i = fidx(a.f, r, c);
    const float elev = a.elev[i];
    const float alt_m = t_cmin(elev, 0.0f);
    float temp = a.clim[i];
    const float t_season = a.clim[fp + i];
    const float c2 = a.clim[2 * fp + i];
    float precip = t_cmin(c2, 0.0f);
    const float p_cv = a.clim[3 * fp + i];
    // numpy fp32: 0.4 * coarse + 0.2 * fine; 1.0 + 0.2 * precip; 3.0 * snow + 2.0 * snow_fine
    const float n_t = a.noise[t], n_tf = a.noise[hw + t], n_p = a.noise[2 * hw + t], n_s = a.noise[3 * hw + t],
                n_sf = a.noise[4 * hw + t];
    temp = ADD(temp, ADD(MUL(0.4f, n_t), MUL(0.2f, n_tf)));
    precip = MUL(precip, ADD(1.0f, MUL(0.2f, n_p)));
    const float snow_noise = ADD(MUL(3.0f, n_s), MUL(2.0f, n_sf));
    CV cv = climate_vars(temp, t_season, precip, p_cv);
    float tree_moisture = cv.tree_moisture;
    const bool ext = a.C >= 7;
    if (ext) {
        float p_noise = DIV(precip, t_cmin(c2, 1e-6f));
        tree_moisture = MUL(MUL(t_cmin(a.clim[4 * fp + i], 0.0f), p_noise),
                            SUB(1.0f, MUL(0.35f, t_cmax(MUL(p_cv, 1.0f / 100.0f), 1.0f))));
    }
    const float growing_season = cv.growing_season;
    // slope: Sobel / 8 (cuDNN: sequential row-major accumulation from 0, measured), sqrt(dx^2 + dy^2) / px
    float dx = 0.0f, dy = 0.0f;
    const float kx[9] = {-0.125f, 0.0f, 0.125f, -0.25f, 0.0f, 0.25f, -0.125f, 0.0f, 0.125f};
    const float ky[9] = {-0.125f, -0.25f, -0.125f, 0.0f, 0.0f, 0.0f, 0.125f, 0.25f, 0.125f};
    for (int u = 0; u < 3; ++u)
        for (int v = 0; v < 3; ++v) {
            int rr = iclamp(a.fpad.R0 + r + u - 1, 0, a.fpad.SH - 1);
            int cc = iclamp(a.fpad.C0 + c + v - 1, 0, a.fpad.SW - 1);
            float p = a.epad[(long long)rr * a.fpad.SW + cc];
            dx = ADD(dx, MUL(p, kx[u * 3 + v]));
            dy = ADD(dy, MUL(p, ky[u * 3 + v]));
        }
    const float slope = MUL(sqrtf(ADD(MUL(dx, dx), MUL(dy, dy))), a.inv_px);
    // Every decision below is an int flag combined with & / | / ^: the bool version (&&, ||, !) of this rule was
    // miscompiled by the system NVRTC (13.x; 2026-11-19: a predicated store under a true condition did not happen).
    const int is_steep = slope > 0.78f;
    const float gs_factor = t_clamp(MUL(SUB(growing_season, 60.0f), 1.0f / 90.0f), 0.0f, 1.0f);
    const float eff = MUL(tree_moisture, gs_factor);
    const float mf = t_clamp(MUL(SUB(tree_moisture, 0.35f), 1.0f / 0.45f), 0.0f, 1.0f);
    const float bare_threshold = ADD(0.7f, MUL((float)(1.19 - 0.7), mf));
    int none = eff < 0.2f;
    const int too_arid = tree_moisture < 0.05f;
    const int too_cold = growing_season < 60.0f;
    const int barren = too_arid | too_cold;
    int sparse = (1 ^ none) & (eff < 0.5f);
    int forest = (1 ^ none) & (eff >= 0.5f) & (eff < 0.8f);
    int dense = (1 ^ none) & (eff >= 0.8f) & (eff < 1.3f);
    int rain = (1 ^ none) & (eff >= 1.3f);
    const int slope_medium = (slope >= 0.62f) & (slope < bare_threshold);
    const int slope_bare = slope >= bare_threshold;
    const int had = forest | dense | rain;
    sparse = sparse | (slope_medium & had);
    forest = forest & (1 ^ slope_medium);
    dense = dense & (1 ^ slope_medium);
    rain = rain & (1 ^ slope_medium);
    none = none | slope_bare;
    sparse = sparse & (1 ^ slope_bare);
    forest = forest & (1 ^ slope_bare);
    dense = dense & (1 ^ slope_bare);
    rain = rain & (1 ^ slope_bare);
    const float snow_temp = ADD(temp, snow_noise);
    int would = (snow_temp < 0.0f) & (precip > 150.0f);
    if (ext)
        would = would | ((MUL(t_cmin(a.clim[6 * fp + i], 0.0f), ADD(1.0f, MUL(0.06f, snow_noise))) > K.snow_cover_we) &&
                          (snow_temp < K.snow_cover_tmax));
    const int has_snow = would & (1 ^ is_steep);
    const int is_ocean = elev < 0.0f;
    const int mountains = alt_m > 2500.0f;
    const int frozen = temp < -5.0f;
    const int cold = (temp >= -5.0f) & (temp < 5.0f);
    const int cool = (temp >= 5.0f) & (temp < 12.0f);
    const int temperate = (temp >= 12.0f) & (temp < 20.0f);
    const int warm = (temp >= 20.0f) & (temp < 26.0f);
    const int hot = temp >= 26.0f;
    int out = K.id_plains;
    int unassigned = true;
    if (is_ocean) {   // branch 1
        int of = frozen, oc = cold & (1 ^ frozen), ow = warm | hot;
        int on = (1 ^ of) & (1 ^ oc) & (1 ^ ow);
        if (of) out = K.ocean_frozen;
        if (oc) out = K.ocean_cold;
        if (ow) out = K.ocean_warm;
        if (on) out = K.ocean_normal;
        unassigned = false;
    }
    const int mtn = mountains & unassigned;
    if (mtn) {        // branch 2
        if (slope_bare & has_snow) out = K.id_frozen_peaks;
        if (slope_bare & (1 ^ has_snow)) out = K.id_stony_peaks;
        const int soil = (1 ^ slope_bare);
        if (soil & has_snow & none) out = K.id_snowy_slopes;
        if (soil & has_snow & (sparse | forest)) out = K.id_snowy_taiga_sparse;
        if (soil & has_snow & (dense | rain)) out = K.id_snowy_taiga;
        if (soil & (1 ^ has_snow) & none & barren) out = K.id_windswept;
        const int steppe = soil & (1 ^ has_snow) & none & (1 ^ barren) & ((tree_moisture < 0.35f) | (precip < 350.0f));
        if (steppe) out = K.id_plains;
        if (soil & (1 ^ has_snow) & none & (1 ^ barren) & (1 ^ steppe)) out = K.id_plains;
        if (soil & (1 ^ has_snow) & (sparse | forest)) out = K.id_taiga_sparse;
        if (soil & (1 ^ has_snow) & (dense | rain)) out = K.id_taiga;
        unassigned = false;
    }
    int land = unassigned;
    // 3A
    const int snowy_barren = land & has_snow & none;
    if (snowy_barren) out = K.id_snowy_plains;
    land = land & (1 ^ snowy_barren);
    // 3B
    const int sfs = land & has_snow & (sparse | forest);
    if (sfs) out = K.id_snowy_taiga_sparse;
    const int sfd = land & has_snow & (dense | rain);
    if (sfd) out = K.id_snowy_taiga;
    land = land & (1 ^ (sfs | sfd));
    // 3D
    const int dry_barren = land & (1 ^ has_snow) & none;
    const int hyper_arid = too_arid | (precip < K.hyper_arid);
    const int desert = dry_barren & (1 ^ too_cold) & (hyper_arid | warm | hot);
    if (desert) out = K.id_desert;
    const int cold_barren = dry_barren & too_cold & (1 ^ desert);
    if (cold_barren) out = K.id_grove;
    if (dry_barren & (1 ^ desert) & (1 ^ cold_barren)) out = K.id_plains;
    land = land & (1 ^ dry_barren);
    // 3E
    const int sfl = land & (1 ^ has_snow) & (sparse | forest);
    if (sfl & hot) out = K.id_jungle;
    if (sfl & warm & sparse & (1 ^ slope_medium)) out = K.id_savanna;
    if (sfl & warm & forest) out = K.id_forest_sparse;
    if (sfl & temperate) out = K.id_forest_sparse;
    if (sfl & (cool | cold)) out = K.id_taiga_sparse;
    land = land & (1 ^ sfl);
    // 3G
    const int dl = land & (1 ^ has_snow) & dense;
    const int jd = dl & hot;
    if (jd) out = K.id_jungle;
    const int td = dl & (cool | cold) & (1 ^ jd);
    if (td) out = K.id_taiga;
    if (dl & (1 ^ jd) & (1 ^ td)) out = K.id_forest;
    land = land & (1 ^ dl);
    // 3H
    const int rl = land & (1 ^ has_snow) & rain;
    const int jr = rl & (hot | (warm & cv.tropical));
    if (jr) out = K.id_jungle;
    const int tr = rl & (cool | cold) & (1 ^ jr);
    if (tr) out = K.id_taiga;
    if (rl & (1 ^ jr) & (1 ^ tr)) out = K.id_forest;
    land = land & (1 ^ rl);
    // 3I
    if (land) out = K.id_plains;
    // bare slope override
    const int lowland_bare = slope_bare & (1 ^ is_ocean) & (1 ^ mountains);
    if (lowland_bare & has_snow) out = K.id_frozen_peaks;
    if (lowland_bare & (1 ^ has_snow)) out = K.id_stony_peaks;
    a.out[t] = (short)out;
}

// ============================================================ biomes.swamp_candidates
// elev / regional / climate on frame f; noise (6, h, w): temp, temp_fine, precip, snow, snow_fine, swamp.
struct SwampArgs {
    const float* elev;
    const float* regional;
    const float* clim;
    const float* noise;
    bool* out;
    Frame f;
    int h, w, C;
    BiomeK k;
};
extern "C" __global__ void tiles_swamp_candidates(const SwampArgs a) {
    long long t = gtid();
    long long hw = (long long)a.h * a.w;
    if (t >= hw) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const BiomeK& K = a.k;
    const long long fp = (long long)a.f.SH * a.f.SW;
    const long long i = fidx(a.f, r, c);
    const float c0 = a.clim[i], c1 = a.clim[fp + i], c2 = a.clim[2 * fp + i], c3 = a.clim[3 * fp + i];
    const float n_t = a.noise[t], n_tf = a.noise[hw + t], n_p = a.noise[2 * hw + t], n_s = a.noise[3 * hw + t],
                n_sf = a.noise[4 * hw + t], n_sw = a.noise[5 * hw + t];
    const float temp = ADD(ADD(c0, MUL(0.4f, n_t)), MUL(0.2f, n_tf));
    const float precip = MUL(t_cmin(c2, 0.0f), ADD(1.0f, MUL(0.2f, n_p)));
    const float snow_temp = ADD(ADD(temp, MUL(3.0f, n_s)), MUL(2.0f, n_sf));
    CV cv = climate_vars(temp, c1, precip, c3);
    const float gsf = t_clamp(MUL(SUB(cv.growing_season, 60.0f), 1.0f / 90.0f), 0.0f, 1.0f);
    float moisture = MUL(cv.tree_moisture, gsf);
    const bool ext = a.C >= 7;
    if (ext) {
        float p_noise = DIV(precip, t_cmin(c2, 1e-6f));
        moisture = MUL(MUL(MUL(t_cmin(a.clim[4 * fp + i], 0.0f), p_noise),
                           SUB(1.0f, MUL(0.35f, t_cmax(MUL(c3, 1.0f / 100.0f), 1.0f)))), gsf);
    }
    const float wet = t_clamp(MUL(SUB(moisture, K.sw_min_moist), K.sw_inv_moist_span), 0.0f, 1.0f);
    float threshold = ADD(K.sw_dry, MUL(K.sw_wet_minus_dry, wet));
    float min_temp = K.sw_min_temp;
    bool soil_ok = true;
    if (ext) {
        const float c5 = a.clim[5 * fp + i];
        const float soil_w = t_clamp(MUL(SUB(c5, K.soil0), K.inv_soil_span), 0.0f, 1.0f);
        threshold = SUB(threshold, MUL(0.25f, soil_w));
        min_temp = ADD(K.sw_min_temp, MUL(K.bog_minus_min, (soil_w > 0.5f) ? 1.0f : 0.0f));
        soil_ok = c5 >= K.soil_min;
    }
    const int patch = n_sw > threshold;
    const float e = a.elev[i], reg = a.regional[i];
    // int flags combined with & (a bool && chain of the rule classifier was miscompiled by NVRTC, see tiles_classify)
    a.out[t] = (patch & (int)soil_ok & (moisture >= K.sw_min_moist) & (temp >= min_temp) &
                (snow_temp >= K.sw_snow_margin) & (1 ^ (int)cv.hard_frost) & (e >= 0.0f) & (e < K.sw_max_elev) &
                (reg < K.sw_max_reg)) != 0;
}

// biomes.majority(ids, size): mode of the replicate-padded size x size window; ties -> the smallest id (torch.unique
// sorts, argmax takes the first maximum).
struct MajorityArgs {
    const short* src;
    short* dst;
    int H, W, size;
};
extern "C" __global__ void tiles_majority(const MajorityArgs a) {
    long long t = gtid();
    if (t >= (long long)a.H * a.W) return;
    const int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W);
    const int rad = a.size / 2;
    // window histogram over the (few) distinct ids; more than 24 distinct ids -> the quadratic count
    const int MAXD = 24;
    short vals[MAXD];
    int cnt[MAXD];
    int nd = 0, overflow = 0;
    for (int u = -rad; u <= rad; ++u)
        for (int v = -rad; v <= rad; ++v) {
            short id = a.src[(long long)iclamp(r + u, 0, a.H - 1) * a.W + iclamp(c + v, 0, a.W - 1)];
            int j = 0;
            while (j < nd && vals[j] != id) ++j;
            if (j < nd) cnt[j] += 1;
            else if (nd < MAXD) { vals[nd] = id; cnt[nd] = 1; ++nd; }
            else overflow = 1;
        }
    short best = 0;
    int best_n = -1;
    if (!overflow) {
        for (int j = 0; j < nd; ++j)
            if (cnt[j] > best_n || (cnt[j] == best_n && vals[j] < best)) { best_n = cnt[j]; best = vals[j]; }
        a.dst[t] = best;
        return;
    }
    for (int u = -rad; u <= rad; ++u)
        for (int v = -rad; v <= rad; ++v) {
            short id = a.src[(long long)iclamp(r + u, 0, a.H - 1) * a.W + iclamp(c + v, 0, a.W - 1)];
            if (best_n >= 0 && id == best) continue;
            int n = 0;
            for (int p = -rad; p <= rad; ++p)
                for (int q = -rad; q <= rad; ++q)
                    n += a.src[(long long)iclamp(r + p, 0, a.H - 1) * a.W + iclamp(c + q, 0, a.W - 1)] == id;
            if (n > best_n || (n == best_n && id < best)) { best_n = n; best = id; }
        }
    a.dst[t] = best;
}

// biomes.smooth_mask(mask, size): replicate-padded window majority (count / size^2 > 0.5).
struct SmoothArgs {
    const bool* src;
    bool* dst;
    int H, W, size;
};
extern "C" __global__ void tiles_smooth_mask(const SmoothArgs a) {
    long long t = gtid();
    if (t >= (long long)a.H * a.W) return;
    const int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W);
    const int rad = a.size / 2;
    int n = 0;
    for (int u = -rad; u <= rad; ++u)
        for (int v = -rad; v <= rad; ++v)
            n += a.src[(long long)iclamp(r + u, 0, a.H - 1) * a.W + iclamp(c + v, 0, a.W - 1)] ? 1 : 0;
    a.dst[t] = 2 * n > a.size * a.size;
}

__device__ __forceinline__ bool is_ocean_id(short v, const BiomeK& K) {
    return v == K.ocean_frozen || v == K.ocean_cold || v == K.ocean_warm || v == K.ocean_normal;
}
// TileBuilder._classify, sidecar branch: biome = where(ocean(rule), rule, where(side == SWAMP, clean, side)) with
// clean = majority(majority(rule_m, 9), 5) unless that is an ocean id (then rule); all frames cropped by mb.
struct SidecarMixArgs {
    const short* side;    // frame (H, W)
    const short* rule;    // frame
    const short* clean;   // frame
    short* out;           // (h, w)
    int H, W, mb, h, w;
    BiomeK k;
};
extern "C" __global__ void tiles_sidecar_mix(const SidecarMixArgs a) {
    long long t = gtid();
    if (t >= (long long)a.h * a.w) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const long long i = (long long)(r + a.mb) * a.W + (c + a.mb);
    const short rule = a.rule[i], side = a.side[i];
    short clean = a.clean[i];
    if (is_ocean_id(clean, a.k)) clean = rule;
    a.out[t] = is_ocean_id(rule, a.k) ? rule : (side == a.k.id_swamp ? clean : side);
}
// biomes.apply_swamps(biome, crop(swamps)): swamps frame (H, W) read at (r + R0, c + C0).
struct ApplySwampsArgs {
    short* biome;   // (h, w) in place
    const bool* swamps;
    int h, w, SW, R0, C0;
    BiomeK k;
};
extern "C" __global__ void tiles_apply_swamps(const ApplySwampsArgs a) {
    long long t = gtid();
    if (t >= (long long)a.h * a.w) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const short b = a.biome[t];
    const bool host = b == a.k.id_forest || b == a.k.id_forest_sparse || b == a.k.id_jungle;
    if (host && a.swamps[(long long)(r + a.R0) * a.SW + (c + a.C0)]) a.biome[t] = a.k.id_swamp;
}

// TileBuilder._red_deserts: float cell coordinates of the sample centres. dint: d integer (si * d exact in int64,
// then + d / 2 in fp32), else fp32 (si * d) + d / 2; / CELL_PX = * (1 / CELL_PX).
struct DesertCoordArgs {
    float* ci;
    float* cj;
    long long i1, j1;
    int h, w, dint;
    long long d_i;
    float d_f, half_d, inv_cell;
};
extern "C" __global__ void tiles_desert_coords(const DesertCoordArgs a) {
    long long t = gtid();
    if (t >= (long long)a.h * a.w) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    float vi, vj;
    if (a.dint) {
        vi = (float)((a.i1 + r) * a.d_i);
        vj = (float)((a.j1 + c) * a.d_i);
    } else {
        vi = MUL((float)(a.i1 + r), a.d_f);
        vj = MUL((float)(a.j1 + c), a.d_f);
    }
    a.ci[t] = MUL(ADD(vi, a.half_d), a.inv_cell);
    a.cj[t] = MUL(ADD(vj, a.half_d), a.inv_cell);
}
struct RedArgs {
    short* biome;
    const bool* red;
    long long n;
    short desert, red_id;
};
extern "C" __global__ void tiles_red_apply(const RedArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    if (a.biome[t] == a.desert && a.red[t]) a.biome[t] = a.red_id;
}

// ============================================================ world/ocean.py
// grow step: out = dilate(m, 1) & through (8-connected, max_pool 3x3 with -inf padding).
struct GrowArgs {
    const bool* m;
    const bool* through;
    bool* out;
    int H, W, r;
    int and_through;
};
extern "C" __global__ void tiles_dilate(const GrowArgs a) {
    long long t = gtid();
    if (t >= (long long)a.H * a.W) return;
    const int y = (int)(t / a.W), x = (int)(t - (long long)y * a.W);
    bool v = false;
    for (int u = -a.r; u <= a.r && !v; ++u) {
        int yy = y + u;
        if (yy < 0 || yy >= a.H) continue;
        for (int q = -a.r; q <= a.r; ++q) {
            int xx = x + q;
            if (xx < 0 || xx >= a.W) continue;
            if (a.m[(long long)yy * a.W + xx]) { v = true; break; }
        }
    }
    a.out[t] = a.and_through ? (v && a.through[t]) : v;
}
// Elementwise helpers: sea seed (height < 0 & land < SEA_LAND), sub-sea, and/or/not combos.
struct SeaSeedArgs {
    const float* height;
    const float* land;
    bool* sub;
    bool* seed;
    long long n;
    float sea_land;
};
extern "C" __global__ void tiles_sea_seed(const SeaSeedArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    bool s = a.height[t] < 0.0f;
    a.sub[t] = s;
    a.seed[t] = s && (a.land[t] < a.sea_land);
}
// sea_mask_for_samples gather: out[r, c] = OR_{a,b < k} sea[clamp((si * d) // CP + a, max ci1 - 1) - ci0][...]
struct SeaGatherArgs {
    const bool* sea;
    bool* out;
    long long s1, t1, d, cp, ci0, cj0, ci1, cj1;
    int h, w, k, SW;
};
__device__ __forceinline__ long long fdiv(long long a, long long b) {
    long long q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}
extern "C" __global__ void tiles_sea_gather(const SeaGatherArgs a) {
    long long t = gtid();
    if (t >= (long long)a.h * a.w) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    bool v = false;
    for (int p = 0; p < a.k; ++p)
        for (int q = 0; q < a.k; ++q) {
            long long ri = fdiv((a.s1 + r) * a.d, a.cp) + p;
            long long rj = fdiv((a.t1 + c) * a.d, a.cp) + q;
            if (ri > a.ci1 - 1) ri = a.ci1 - 1;
            if (rj > a.cj1 - 1) rj = a.cj1 - 1;
            v = v || a.sea[(ri - a.ci0) * a.SW + (rj - a.cj0)];
        }
    a.out[t] = v;
}
// sub (height < sea_level), seed = sub & sea, through = sub & ~wet.
struct OceanPrepArgs {
    const long long* height;   // int64 block heights
    const bool* wet;
    const bool* sea;
    bool* sub;
    bool* through;
    bool* seed;
    long long n, sea_level;
};
extern "C" __global__ void tiles_ocean_prep(const OceanPrepArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    bool s = a.height[t] < a.sea_level;
    bool th = s && !a.wet[t];
    a.sub[t] = s;
    a.through[t] = th;
    a.seed[t] = th && a.sea[t];
}
// ocean.classify_elev(elev, sub & ~ocean): where(lake, elev.clamp_min(LAKE), elev), in place.
struct ClassifyElevArgs {
    float* elev;
    const bool* sub;
    const bool* ocean;
    long long n;
    float lake;
};
extern "C" __global__ void tiles_classify_elev(const ClassifyElevArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    if (a.sub[t] && !a.ocean[t]) a.elev[t] = t_cmin(a.elev[t], a.lake);
}

// flag[0] = 1 if any biome == id (desert.any())
struct AnyArgs {
    const short* biome;
    int* flag;
    long long n;
    short id;
};
extern "C" __global__ void tiles_any_id(const AnyArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    if (a.biome[t] == a.id) atomicOr(a.flag, 1);
}

// FX4 alpine_zones (world/tiles.py): above the treeline (growing season < TREELINE_GS_DAYS with the treeline noise, and
// elev >= ALPINE_MIN_M + ALPINE_MIN_NOISE_M * n1) tree ids -> rock, snowy tree ids -> snow. In place on biome (h, w);
// elev / temp / tseason read on frame f; noise (5, h, w) at the zone coordinates (planes 3, 4 = snow, snow fine).
struct AlpineZoneArgs {
    short* biome;
    const float* elev;
    const float* temp;
    const float* tseason;
    const float* noise;
    Frame f;
    int h, w, n_tree, n_snowy;
    short tree[16], snowy[16];
    short rock, snow;
    float noise_c, gs_days, min_m, min_noise_m;
};
extern "C" __global__ void tiles_alpine_zones(const AlpineZoneArgs a) {
    long long t = gtid();
    const long long hw = (long long)a.h * a.w;
    if (t >= hw) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const long long i = fidx(a.f, r, c);
    const float na = a.noise[3 * hw + t], nb = a.noise[4 * hw + t];
    const float n0 = ADD(MUL(0.6f, na), MUL(0.4f, nb));   // numpy fp32: 0.6 * a + 0.4 * b
    const float tt = ADD(a.temp[i], MUL(a.noise_c, n0));
    const float amp = t_cmin(MUL(MUL(a.tseason[i], 1.0f / 100.0f), 1.414f), 0.1f);
    const float x = DIV(SUB(5.0f, tt), amp);
    float gs = MUL(365.0f, SUB(0.5f, MUL(asinf(t_clamp(x, -1.0f, 1.0f)), 1.0f / 3.14159f)));
    if (x >= 1.0f) gs = 0.0f;
    if (x <= -1.0f) gs = 365.0f;
    const int above = (gs < a.gs_days) & (a.elev[i] >= ADD(a.min_m, MUL(a.min_noise_m, nb)));
    if (!above) return;
    const short b = a.biome[t];
    int tree = 0, snowy = 0;
    for (int q = 0; q < a.n_tree; ++q) tree |= b == a.tree[q];
    for (int q = 0; q < a.n_snowy; ++q) snowy |= b == a.snowy[q];
    if (tree) a.biome[t] = a.rock;
    else if (snowy) a.biome[t] = a.snow;
}
