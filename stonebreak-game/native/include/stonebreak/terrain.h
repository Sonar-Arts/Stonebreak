/* libstonebreak_terrain - Stonebreak's DaedalusTGM terrain pipeline in C++ (phase 2 of the native engine; design:
 * Dev Working/docs/DaedalusTGM-Exp-2-Native-Engine.md). Game-specific orchestration on top of Open Mason's engine
 * runtime (Cenda ci_*: kernels, memory, streams, networks), which this library opens at run time (the path of the
 * libcenda_kernels already loaded by the host) - no CUDA toolkit, no link dependency.
 *
 * Phase 2 is hosted by the Python TGMPipe service (ctypes): the host owns the generator's caches and calls the stages
 * that have been ported. Ported so far: the planner stage (window gather, the planner network, patch blur, output
 * transforms, hill transfer, sea merge, optional shelf remap) and the relief window (trend preblur, conditioning,
 * white noise, the relief network, gate / gain / floor). Numbers: bit-identical to the Python stage given the same network
 * output (the network itself is the engine's fp32 planner, see the engine docs).
 *
 * Threading: a context is used by one thread at a time (the service's GPU thread). Errors: negative results,
 * message in sbtg_last_error (thread-local). */
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SBTG_ABI_VERSION 2

typedef struct sbtg_ctx sbtg_ctx;

enum { SBTG_OK = 0, SBTG_ERR_ARG = -1, SBTG_ERR_ENGINE = -2, SBTG_ERR_STATE = -3 };

int32_t sbtg_abi_version(void);
const char* sbtg_last_error(void);

/* A context on an engine runtime (ci_runtime*, owned by the host). `cenda_lib`: the path of libcenda_kernels (already
 * loaded by the host; this library binds the same instance). */
sbtg_ctx* sbtg_create(const char* cenda_lib, void* ci_runtime);
void sbtg_destroy(sbtg_ctx* ctx);

/* Planner stage configuration (copied). Blur kernels: 2r+1 taps each, computed by the host exactly like
 * data/descriptors.gaussian_kernel1d. */
typedef struct sbtg_planner_config {
    void* model;               /* ci_model* of the exported planner (tensor kind, (B, 11, win, win) in and out) */
    int32_t win, n_in, n_out;  /* 64, 11, 11 */
    float height_scale;        /* planner.HEIGHT_SCALE_M */
    float logacc_scale;        /* planner.LOGACC_SCALE */
    float desc_mean[8], desc_std[8];
    const float* k_patch;  int32_t r_patch;   /* PATCH_BLUR_CELLS */
    const float* k_hill;   int32_t r_hill;    /* HILL_TRANSFER_SIGMA_CELLS */
    const float* k_sea;    int32_t r_sea;     /* SEA_DETAIL_SIGMA_CELLS */
    float hill_transfer;       /* 0 = off */
    int32_t sea_detail;        /* 0 = v4's rule */
    float sea_detail_max_m;    /* SEA_DETAIL_MAX_M */
    float sea_floor_m;         /* -5 */
    float shelf_slope, shelf_depth_m, shelf_keep, shelf_keep_m;   /* slope >= 1: no remap */
    int32_t blur_fma;          /* the blur's accumulation (parity-calibrated by the host) */
    int32_t reserved[8];
} sbtg_planner_config;

int32_t sbtg_planner_configure(sbtg_ctx* ctx, const sbtg_planner_config* cfg);

/* Planner windows: `n` windows at cell offsets `offs` (n pairs row, col, host memory) of an input over cells
 * (n_in, H, W) (device, fp32) and the trend over the same cells (H, W) (device); each window's (n_out, win, win)
 * result goes to outs[i] (device pointers, host array). `override_out` (device, (n, n_out, win, win), optional) replaces
 * the network's output (parity tests). Ordered on `stream` (CUstream, 0 = legacy default stream); returns without
 * waiting. */
int32_t sbtg_planner_windows(sbtg_ctx* ctx, const float* input, const float* trend, int32_t H, int32_t W,
                             const int32_t* offs, int32_t n, float* const* outs, const float* override_out,
                             void* stream);

/* Relief stage (generator._relief_window). n = RELIEF_EXT cells, m = the preblur margin (ceil(3 sigma)); the trend
 * preblur taps (2m'+1, m' <= m) come from the host like the planner's. */
typedef struct sbtg_relief_config {
    void* model;               /* ci_model* of the exported relief sampler (flow kind, cond (B, 10, n, n)) */
    int32_t n, m;
    const float* k_blur;  int32_t r_blur;
    float gain;                /* relief_gain (scalar) */
    float scale;               /* relief.RELIEF_SCALE_M */
    float floor_m;             /* RELIEF_FLOOR_M */
    float height_scale, slope_scale, wild_offset, wild_scale;   /* planner.HEIGHT_SCALE_M, relief.SLOPE_SCALE_M, WILD_NORM */
    int64_t noise_stream;      /* RELIEF_NOISE_STREAM */
    int32_t reserved[8];
} sbtg_relief_config;

int32_t sbtg_relief_configure(sbtg_ctx* ctx, const sbtg_relief_config* cfg);

/* One relief window: layout / climate fields over (N, N) = (n + 2m)^2 cells starting at cell (ci0 - m, cj0 - m)
 * (device, fp32: trend, wild, t0, tseason, precip, pcv), the window's first cell (ci0, cj0) and the world seed ->
 * out (n, n) (device). Ordered on `stream`. */
int32_t sbtg_relief_window(sbtg_ctx* ctx, const float* const fields[6], int64_t ci0, int64_t cj0, int64_t seed,
                           float* out, void* stream);

/* Detail window (generator._detail_window, lowland boost off): cell fields over nc x nc cells (the window's samples
 * plus DETAIL_MARGIN_CELLS) -> the window's heights (E x E samples). Attempts = DETAIL_RETRY (planner-height blur taps,
 * climate on / off); the guard (finite and |x| < x_limit) costs one sync per attempt, as in the generator. */
typedef struct sbtg_detail_config {
    void* model;               /* ci_model* of the exported Detail2 (flow kind, gates) */
    int32_t nc, f, E, ms, S;   /* cells, samples per cell, window samples, crop offset (samples), expert styles */
    int32_t n_attempts;
    const float* k_attempt[8]; int32_t r_attempt[8]; int32_t use_climate[8];
    const float* k_base;  int32_t r_base;     /* detail.BASE_SIGMA (samples) */
    const float* k_river; int32_t r_river;    /* 0.7 cells */
    int32_t up_fma;            /* the bilinear upsample's fused-product pattern (parity-calibrated by the host) */
    float height_scale, slope_scale, wild_offset, wild_scale, logacc_scale, x_limit, asinh_m;
    int64_t noise_stream;
    int32_t reserved[8];
} sbtg_detail_config;

int32_t sbtg_detail_configure(sbtg_ctx* ctx, const sbtg_detail_config* cfg);

/* fields: raw planner height, wild, t0, tseason, precip, pcv, logacc (or NULL), river (or NULL), gates (S, nc, nc)
 * (or NULL) - device, fp32, (nc, nc). si0 / sj0: the window's first sample (noise coordinates). *attempt: the attempt
 * that passed the guard, or -1 (the smooth base alone). Synchronises `stream` once per attempt. */
int32_t sbtg_detail_window(sbtg_ctx* ctx, const float* const fields[9], int64_t si0, int64_t sj0, int64_t seed,
                           float has_flow, float* out, int32_t* attempt, void* stream);

/* Climate cells (generator2.climate_cells, canonical sampling): the cross-faded climate grid (7, gh, gw) (device)
 * whose first point is grid (gi0, gj0), sampled bilinearly at cells [ci0, ci0 + hc) x [cj0, cj0 + wc) (grid spacing
 * `grid_cells` cells) and denormalised (climatenet.OUT_NORM mean / std) into seven (hc, wc) outputs: t0, tseason,
 * precip, pcv, aridity, soil, snow. */
int32_t sbtg_climate_cells(sbtg_ctx* ctx, const float* grid, int32_t gh, int32_t gw, int64_t gi0, int64_t gj0,
                           int64_t ci0, int64_t cj0, int32_t hc, int32_t wc, double grid_cells, const float mean[7],
                           const float stdv[7], float* const outs[7], void* stream);

/* Counters: [0] planner calls, [1] planner windows, [2] kernel launches issued by the library, [3] engine requests,
 * [4] relief windows, [5] detail attempts. */
int32_t sbtg_stats(const sbtg_ctx* ctx, double* values, int32_t n);

/* ================================================================================================ generator (ABI 2)
 * The native generator (src/terrain/gen): Python's WorldGenerator2 + TileBuilder, method for method. A method runs
 * natively when the library implements it AND the host routed it native (sbtg_gen_route); every other call goes to
 * the host's python callback (the Python generator answers; phase-2 hosting). Methods keep their Python names.
 * One generator is used by one thread at a time (calls are serialised); all its work runs on `stream` (a CUstream as
 * void*; NULL = the legacy default stream, which is what torch's default stream is). */
typedef struct sbtg_gen sbtg_gen;
typedef struct sbtg_fields sbtg_fields;

/* dtype: 0 f32, 1 f64, 2 i32, 3 i64, 4 u8, 5 bool, 6 i16, 7 f16. Contiguous, row-major; host = 1 for host memory. */
typedef struct sbtg_tensor {
    void* data;
    int32_t dtype;
    int32_t ndim;
    int64_t shape[4];
    int32_t host;
    int32_t reserved;
} sbtg_tensor;

/* The python callback: compute `method` and add its results to `out` (sbtg_fields_add copies them). 0 = ok. */
typedef int32_t (*sbtg_python_fn)(void* user, const char* method, const int64_t* iargs, int32_t n_i,
                                  const double* fargs, int32_t n_f, const sbtg_tensor* targs, int32_t n_t,
                                  sbtg_fields* out);

/* `manifest_json`: export/native_manifest.py's document (constants, blur taps, models, kernel sources). */
sbtg_gen* sbtg_gen_create(sbtg_ctx* ctx, const char* manifest_json, void* stream);
void sbtg_gen_destroy(sbtg_gen* gen);
/* Per-world state as JSON: {"seed": int, "climate_layout": str, "sea_shelf": bool, ...}. */
int32_t sbtg_gen_set_state(sbtg_gen* gen, const char* state_json);
int32_t sbtg_gen_set_python(sbtg_gen* gen, sbtg_python_fn fn, void* user);
/* An engine model the host already loaded (shared, not owned), under its manifest name. */
int32_t sbtg_gen_set_model(sbtg_gen* gen, const char* name, void* ci_model);
/* Route `method` ("*" = every implemented one) native (1) or to the python callback (0). */
int32_t sbtg_gen_route(sbtg_gen* gen, const char* method, int32_t native);
int32_t sbtg_gen_clear_caches(sbtg_gen* gen);
/* Calls `method`; *out receives the results (free with sbtg_fields_free). */
int32_t sbtg_gen_call(sbtg_gen* gen, const char* method, const int64_t* iargs, int32_t n_i, const double* fargs,
                      int32_t n_f, const sbtg_tensor* targs, int32_t n_t, sbtg_fields** out);
/* Newline-separated names of the methods this library implements; returns the length (buf may be NULL). */
int32_t sbtg_gen_methods(char* buf, int32_t cap);
/* JSON counters (native / python calls per method, kernel launches, pool bytes); returns the length. */
int32_t sbtg_gen_stats(sbtg_gen* gen, char* buf, int32_t cap);

sbtg_fields* sbtg_fields_new(void);
int32_t sbtg_fields_count(const sbtg_fields* f);
const char* sbtg_fields_name(const sbtg_fields* f, int32_t i);
int32_t sbtg_fields_get(const sbtg_fields* f, int32_t i, sbtg_tensor* t);
/* Adds a COPY of `t` (device or host memory) under `name`, on the generator's stream. */
int32_t sbtg_fields_add(sbtg_fields* f, const char* name, const sbtg_tensor* t);
void sbtg_fields_free(sbtg_fields* f);

/* ================================================================================================ terrain (ABI 2)
 * The whole tile pipeline in-process, without Python (phase 3: the game's JVM through FFM; also what the Python
 * service runs in native mode). `native_dir` holds the release export (export/native_release.py): manifest.json + the
 * .cim networks it names. The library opens `cenda_lib` (libcenda_kernels with ci ABI 2) and creates its own engine
 * runtime on `device`. Calls on one sbtg_terrain are serialised; tiles are built on its own stream. */
typedef struct sbtg_terrain sbtg_terrain;
typedef struct sbtg_tile sbtg_tile;

typedef struct sbtg_terrain_options {
    int32_t device;          /* CUDA device index */
    int32_t flags;           /* reserved, 0 */
    const char* cache_dir;   /* compiled-kernel cache (NULL = none) */
    const char* nvrtc_path;  /* NVRTC library (NULL = engine default search) */
    int64_t reserved[6];
} sbtg_terrain_options;

sbtg_terrain* sbtg_terrain_open(const char* cenda_lib, const char* native_dir, const sbtg_terrain_options* options);
void sbtg_terrain_close(sbtg_terrain* t);
/* JSON: {"model_id", "abi", "device", "methods": [...], "manifest": path}. Valid until the next call. */
const char* sbtg_terrain_info(sbtg_terrain* t);
/* Compiles kernels, loads every network and builds their CUDA graphs now. */
int32_t sbtg_terrain_warmup(sbtg_terrain* t);
/* World `index` of the handshake: `world_json` = WorldConfig.to_dict() (the HELLO world). */
int32_t sbtg_terrain_set_world(sbtg_terrain* t, int32_t index, const char* world_json);
/* Builds a tile (TileBuilder.build): seed, tile coordinates, level of detail. *out on success. */
int32_t sbtg_terrain_tile(sbtg_terrain* t, int32_t world, int64_t seed, int32_t tile_x, int32_t tile_z, int32_t lod,
                          sbtg_tile** out);
/* int16 planes (planes, rows, cols), host memory owned by the tile. */
const int16_t* sbtg_tile_planes(const sbtg_tile* tile, int32_t* planes, int32_t* rows, int32_t* cols);
/* Surface channels (TGMPipe v3): count, then name + uint8 (rows, cols) plane each; 255 = no data. */
int32_t sbtg_tile_surface_count(const sbtg_tile* tile);
const char* sbtg_tile_surface_name(const sbtg_tile* tile, int32_t i);
const uint8_t* sbtg_tile_surface(const sbtg_tile* tile, int32_t i);
void sbtg_tile_free(sbtg_tile* tile);
/* JSON counters of the generator (as sbtg_gen_stats) + tiles built; returns the length. */
int32_t sbtg_terrain_stats(sbtg_terrain* t, char* buf, int32_t cap);

/* Lanes (agent PX, 2026-11-19; additive to ABI 2): `lanes` generators on their own streams and memory pools that build
 * tiles CONCURRENTLY (one host thread per lane), sharing the engine models (one execution context per lane) and the
 * window caches (a window in flight in one lane is waited for, not recomputed, by the others). Tiles are bit-identical
 * whatever lane builds them, in whatever order. Call once, before sbtg_terrain_warmup and the first tile ($SBTG_LANES
 * sets it at open time); 1 = the sequential pipeline. sbtg_terrain_tile builds on lane 0. */
int32_t sbtg_terrain_set_lanes(sbtg_terrain* t, int32_t lanes);
int32_t sbtg_terrain_lanes(sbtg_terrain* t);
/* sbtg_terrain_tile on lane `lane` (0 .. lanes-1); calls on one lane are serialised, different lanes run at once. */
int32_t sbtg_terrain_tile_lane(sbtg_terrain* t, int32_t lane, int32_t world, int64_t seed, int32_t tile_x,
                               int32_t tile_z, int32_t lod, sbtg_tile** out);

#ifdef __cplusplus
}
#endif
