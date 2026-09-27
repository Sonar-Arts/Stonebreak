"""DaedalusTGM-Exp model server: drop-in replacement for upstream's `terrain_diffusion.inference.minecraft_api`.

Speaks the exact contract terrain-bridge's UpstreamClient uses:
  GET /health  -> {"status": "ok", "seed": ..., "name": "DaedalusTGM-Exp", "model": ...}
  GET /terrain?i1&j1&i2&j2[&scale=1][&downscale=1][&noise=1.0][&elev_only=0][&water=0][&format=json]
      -> elevation int16 LE (floor of metres), then biome id int16 LE (unless elev_only),
         then -- only with water=1 -- the river water SURFACE int16 LE (floor of metres,
         WATER_NONE where dry), then -- only with water=1&river3d=1 on the downscale path --
         the 3D river planes in BLOCKS: tunnel floor, tunnel roof, flow octant (-1 = none);
         headers X-Height / X-Width / X-Dtype: int16-le.
Elevation always has the rivers carved in (terrain_slm.river pipeline), whether or not the
water plane is requested, so every client sees the same ground.

`downscale=D` (with scale=1) serves blocks coarser than the 30 m model grid: each block
averages D x D native pixels (the 1:4 world is D=2, 60 m blocks). That path runs the river
pipeline's block stages against the bridge's own height curve (built from the same
TERRAIN_BRIDGE_* environment the bridge reads): quantise, contain (dry neighbours raised to
the water), undercut banks and overhangs, flow octants -- so nothing spills once quantised.
Coordinates are in target-resolution pixels; native pixels are 30 m, `scale` upsamples
(bilinear + upstream's slope-scaled detail noise), and biomes come from upstream's own
classifier (vendored in terrain_slm.biomes) so ids stay game-compatible.

Accepts the same CLI flags TerrainServiceProcessManager passes to upstream
(model path, --no-compile, --device, --cache-size, --port, --hdf5-file, --seed).
"""
from __future__ import annotations

import argparse
import math
import os
import sys
import time
from pathlib import Path

import numpy as np
import torch
from flask import Flask, Response, jsonify, request

from terrain_slm import MODEL_NAME
from terrain_slm import biomes as B
from terrain_slm.paths import BRIDGE_DIR, MODEL_DIR
from terrain_slm.data.descriptors import CELL_PX
from terrain_slm.river import pipeline as RP
from terrain_slm.river.scale import BlockScale
from terrain_slm.world.generator import GenConfig, WorldGenerator

NATIVE_M = 30.0
WATER_NONE = -32768
CURVE = None  # bridge HeightCurve: the game's metres->blocks mapping (downscale path)


def _bridge_curve(seed: int):
    """The bridge's metres->blocks curve from the TERRAIN_BRIDGE_* environment (the process
    manager passes both services the same values), so blocks settled here are the blocks
    the bridge will produce."""
    if str(BRIDGE_DIR) not in sys.path:
        sys.path.insert(0, str(BRIDGE_DIR))
    from bridge.config import BridgeConfig
    from bridge.height_mapping import HeightCurve

    return HeightCurve.from_config(BridgeConfig.from_env(seed=seed))


def _downsampled(i1: int, j1: int, i2: int, j2: int, d: int):
    """Blocks of d x d native pixels, built by the river pipeline's block stages.

    Runs the pipeline over the request plus BLOCK_MARGIN columns (the block stages look at
    neighbours), then crops. Heights and water levels come back as MID-BAND metres, so the
    bridge's own curve maps them to exactly the blocks decided here; the river planes are
    already blocks. Returns (elev_m, biome, water_m (NaN dry), (floor, roof, flow)), all (h, w).
    """
    mb = RP.BLOCK_MARGIN
    n1, m1, n2, m2 = (i1 - mb) * d, (j1 - mb) * d, (i2 + mb) * d, (j2 + mb) * d
    field, ctx = GEN.river_field(n1, m1, n2, m2)
    cols = GEN.river.columns(field, ctx, d)
    sc = GEN.scale
    mid = lambda b: sc.to_metres(b.float() + 0.5)
    elev = mid(cols.height)
    water = torch.where(cols.wet, mid(cols.water), torch.full_like(elev, float("nan")))
    clim_n = GEN.climate_native(n1, m1, n2, m2, field.carved)
    climate = torch.nn.functional.avg_pool2d(clim_n[None], d)[0]
    crop = lambda x: x[..., mb:-mb, mb:-mb]
    pad1 = lambda x: x[..., mb - 1 : -(mb - 1), mb - 1 : -(mb - 1)]
    biome = B._classify_biome(crop(elev), crop(climate), i1, j1, elev_padded=pad1(elev), pixel_size_m=NATIVE_M * d)
    river = tuple(crop(p) for p in (cols.floor, cols.roof, cols.flow))
    return crop(elev), biome, crop(water), river


OVERVIEW_MIN_PX = 8  # at a cell (8 native px, 4 blocks) or coarser per block: serve from cells alone
# D8 class (drow, dcol) -> flow octant (0 = +x, toward +z); class 8 = terminal. Must match data/build.py.
_D8_OCTANT = [round(math.atan2(dc, dr) / (math.pi / 4)) % 8
              for dr, dc in ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))] + [-1]


def _overview(i1: int, j1: int, i2: int, j2: int, d: int):
    """Far-zoom preview blocks of d native pixels (d a multiple of 8, i.e. whole 240 m cells),
    straight from the model's cell fields: coarse height, hydro-sidecar rivers (one sample
    wide) and their D8 flow. No synth, refiner or river pipeline, so it costs a tiny fraction
    of a full tile; for the terrain mapper zoomed out, not for building chunks. Same return
    contract as `_downsampled`.
    """
    k = d // CELL_PX
    h, w = i2 - i1, j2 - j1
    c = GEN.overview_cells((i1 - 1) * k, (j1 - 1) * k, (h + 2) * k, (w + 2) * k)
    pool = lambda x: torch.nn.functional.avg_pool2d(x[None], k)[0]
    elev = pool(c["height"][None])[0]
    peak, idx = torch.nn.functional.max_pool2d(c["logacc"][None, None], k, return_indices=True)
    wet = (peak[0, 0] >= GEN.cfg.river_log_threshold) & (elev > 0.5)
    sc = GEN.scale
    hb = torch.floor(sc.to_blocks(elev)).clamp(0, sc.world_height - 1)
    mid = lambda b: sc.to_metres(b + 0.5)
    elev_m = mid(hb)
    water_m = torch.where(wet, mid(hb + 1), torch.full_like(elev_m, float("nan")))
    none = torch.full(elev.shape, -1, dtype=torch.long, device=elev.device)
    flow = none.clone()
    if c["d8"] is not None:
        cls = c["d8"].flatten()[idx[0, 0].flatten()].view(elev.shape).clamp(0, 8)
        flow = torch.where(wet, torch.tensor(_D8_OCTANT, device=elev.device)[cls], none)
    climate = pool(c["climate"])
    crop = lambda x: x[..., 1:-1, 1:-1]
    biome = B._classify_biome(crop(elev_m), crop(climate), i1, j1, elev_padded=elev_m, pixel_size_m=NATIVE_M * d)
    return crop(elev_m), biome, crop(water_m), (crop(none), crop(none), crop(flow))


app = Flask(__name__)
GEN: WorldGenerator | None = None


def _binary(elev: torch.Tensor, biome: torch.Tensor | None, water: torch.Tensor | None = None,
            river: tuple[torch.Tensor, ...] | None = None) -> Response:
    e = np.clip(np.floor(elev.detach().float().cpu().numpy()), -32767, 32767).astype("<i2")
    payload = e.tobytes()
    if biome is not None:
        payload += biome.detach().cpu().numpy().astype("<i2").tobytes()
    if water is not None:
        wv = water.detach().float().cpu().numpy()
        wi = np.where(np.isnan(wv), WATER_NONE, np.clip(np.floor(wv), -32767, 32767)).astype("<i2")
        payload += wi.tobytes()
    if river is not None:
        for plane in river:  # already blocks: tunnel floor, tunnel roof, flow octant (-1 = none)
            payload += plane.detach().cpu().numpy().astype("<i2").tobytes()
    resp = Response(payload, mimetype="application/octet-stream")
    resp.headers["X-Height"] = str(e.shape[0])
    resp.headers["X-Width"] = str(e.shape[1])
    resp.headers["X-Dtype"] = "int16-le"
    return resp


def _json(elev: torch.Tensor):
    a = elev.detach().float().cpu().numpy()
    return jsonify({"dtype": "float32", "shape": list(a.shape), "elev": a.tolist()})


def _quad():
    vals = [request.args.get(k, type=int) for k in ("i1", "j1", "i2", "j2")]
    if any(v is None for v in vals):
        raise ValueError("missing i1/j1/i2/j2")
    i1, j1, i2, j2 = vals
    if i2 <= i1 or j2 <= j1:
        raise ValueError("Expected i2>i1 and j2>j1")
    return i1, j1, i2, j2


def _upsampled(i1, j1, i2, j2, scale, noise_scale):
    """Port of upstream `_get_upsampled`, with our generator as the native source."""
    n1, m1 = i1 // scale, j1 // scale
    n2, m2 = -(-i2 // scale), -(-j2 // scale)
    pi1, pj1, pi2, pj2 = n1 - 2, m1 - 2, n2 + 2, m2 + 2
    elev_n, surf_n = GEN.terrain(pi1, pj1, pi2, pj2)
    clim_n = GEN.climate_native(pi1, pj1, pi2, pj2, elev_n)
    up = lambda x: torch.nn.functional.interpolate(x, scale_factor=scale, mode="bilinear", align_corners=False)
    elev_up = up(elev_n[None, None])[0, 0]
    pad = 2 * scale
    ci1 = pad + (i1 - n1 * scale)
    cj1 = pad + (j1 - m1 * scale)
    ci2, cj2 = ci1 + (i2 - i1), cj1 + (j2 - j1)
    smooth = elev_up[ci1:ci2, cj1:cj2]
    padded = elev_up[ci1 - 1 : ci2 + 1, cj1 - 1 : cj2 + 1]
    climate = up(clim_n[None])[0][:, ci1:ci2, cj1:cj2]
    pixel_m = NATIVE_M / scale
    # Water: dilate the surface into dry neighbours so bilinear edges stay sane, then keep
    # pixels whose upsampled wet fraction is at least half.
    wet_n = (~torch.isnan(surf_n)).float()
    filled = torch.nan_to_num(surf_n, nan=-1e6)
    for _ in range(2):
        filled = torch.maximum(filled, torch.nn.functional.max_pool2d(filled[None, None], 3, 1, 1)[0, 0])
    filled = torch.where(filled < -1e5, elev_n, filled)
    wet_up = up(wet_n[None, None])[0, 0][ci1:ci2, cj1:cj2]
    surf_up = up(filled[None, None])[0, 0][ci1:ci2, cj1:cj2]
    wet = wet_up >= 0.5
    near_water = up(wet_n[None, None])[0, 0][ci1 - 1 : ci2 + 1, cj1 - 1 : cj2 + 1]
    near_water = torch.nn.functional.max_pool2d(near_water[None, None], 3, 1, 0)[0, 0] > 0.01
    elev = smooth
    if noise_scale > 0:
        h, w = smooth.shape
        xx, yy = np.meshgrid(np.arange(j1, j1 + w, dtype=np.float32), np.arange(i1, i1 + h, dtype=np.float32))
        coords = np.array([xx.ravel(), yy.ravel()], dtype=np.float32)
        nc = torch.from_numpy(B._ELEV_NOISE_COARSE.gen_from_coords(coords).astype(np.float32).reshape(h, w)).to(smooth)
        nf = torch.from_numpy(B._ELEV_NOISE_FINE.gen_from_coords(coords).astype(np.float32).reshape(h, w)).to(smooth)
        sx = torch.tensor([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]], dtype=smooth.dtype, device=smooth.device).view(1, 1, 3, 3) / 8
        sy = torch.tensor([[-1, -2, -1], [0, 0, 0], [1, 2, 1]], dtype=smooth.dtype, device=smooth.device).view(1, 1, 3, 3) / 8
        dx = torch.nn.functional.conv2d(padded[None, None], sx)[0, 0]
        dy = torch.nn.functional.conv2d(padded[None, None], sy)[0, 0]
        slope = (torch.sqrt(dx**2 + dy**2) / (40.0 * pixel_m / 90.0)).clamp(0, 1) ** 1.5
        amp_c = noise_scale * 100.0 * slope * pixel_m / NATIVE_M
        amp_f = noise_scale * 70.0 * slope * pixel_m / NATIVE_M
        # No detail noise on or beside water: it would lift beds above the surface.
        elev = smooth + (nc * amp_c + nf * amp_f) * ((smooth >= 0) & ~near_water).float()
    elev = torch.where(wet, torch.minimum(elev, surf_up - 0.5), elev)
    water = torch.where(wet, surf_up, torch.full_like(surf_up, float("nan")))
    return elev, smooth, climate, padded, pixel_m, water


@app.get("/health")
def health():
    return jsonify({"status": "ok", "seed": GEN.seed, "name": MODEL_NAME, "model": GEN.model_id})


@app.get("/terrain")
def terrain():
    try:
        t0 = time.time()
        scale = request.args.get("scale", default=1, type=int)
        if scale < 1:
            raise ValueError("scale must be >= 1")
        i1, j1, i2, j2 = _quad()
        as_json = request.args.get("format") == "json"
        want_water = request.args.get("water", default=0, type=int) == 1
        want_river3d = want_water and request.args.get("river3d", default=0, type=int) == 1
        down = request.args.get("downscale", default=1, type=int)
        if down < 1 or (down > 1 and scale != 1):
            raise ValueError("downscale must be >= 1 and needs scale=1")
        if down > 1:
            with GEN.lock:
                build = _overview if (down >= OVERVIEW_MIN_PX and down % OVERVIEW_MIN_PX == 0) else _downsampled
                elev, biome, water, river = build(i1, j1, i2, j2, down)
            if request.args.get("elev_only", default=0, type=int) == 1:
                return _json(elev) if as_json else _binary(elev, None)
            return _json(elev) if as_json else _binary(elev, biome, water if want_water else None,
                                                      river if want_river3d else None)
        if scale == 1:
            with GEN.lock:
                if request.args.get("elev_only", default=0, type=int) == 1:
                    elev, _ = GEN.terrain(i1, j1, i2, j2)
                    return _json(elev) if as_json else _binary(elev, None)
                padded, surf = GEN.terrain(i1 - 1, j1 - 1, i2 + 1, j2 + 1)
                elev, water = padded[1:-1, 1:-1], surf[1:-1, 1:-1]
                climate = GEN.climate_native(i1, j1, i2, j2, elev)
                biome = B._classify_biome(elev, climate, i1, j1, elev_padded=padded, pixel_size_m=NATIVE_M)
        else:
            noise = float(request.args.get("noise", "1.0"))
            with GEN.lock:
                elev, smooth, climate, padded, pixel_m, water = _upsampled(i1, j1, i2, j2, scale, noise)
                biome = B._classify_biome(smooth, climate, i1, j1, elev_padded=padded, pixel_size_m=pixel_m)
        resp = _json(elev) if as_json else _binary(elev, biome, water if want_water else None)
        app.logger.debug("terrain %s in %.0f ms", (i1, j1, i2, j2, scale), (time.time() - t0) * 1000)
        return resp
    except Exception as e:  # upstream returns 400 with the message
        return jsonify({"error": str(e)}), 400


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("model_path", nargs="?", default="checkpoints/v3")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--port", type=int, default=int(os.getenv("PORT", "8010")))
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--cache-size", default="4G")
    ap.add_argument("--t-start", type=float, default=float(os.getenv("TERRAIN_SLM_T_START", "0.6")))
    ap.add_argument("--steps", type=int, default=int(os.getenv("TERRAIN_SLM_STEPS", "8")))
    ap.add_argument("--amp-scale", type=float, default=float(os.getenv("TERRAIN_SLM_AMP_SCALE", "1.0")))
    env_rt = os.getenv("TERRAIN_SLM_RIVER_THRESHOLD")
    ap.add_argument("--river-threshold", type=float, default=float(env_rt) if env_rt else None,
                    help="river density knob: log1p(upslope cells) where a river starts (lower = more "
                         "rivers); default: calibrated for the model (4.5 with relief, 3.3 without)")
    # Accepted for argv compatibility with upstream; unused here.
    ap.add_argument("--no-compile", action="store_true")
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--hdf5-file", default=None)
    args, unknown = ap.parse_known_args(argv)
    if unknown:
        print(f"[{MODEL_NAME}] ignoring upstream flags: {unknown}", flush=True)

    device = args.device
    if device == "cuda" and torch.cuda.device_count() > 1:
        device = os.getenv("TERRAIN_SLM_DEVICE", "cuda:1")  # keep GPU 0 for rendering
    model_dir = Path(args.model_path)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    # Regions are 384^2 fp32 (~0.6 MB); size the cache from --cache-size like upstream.
    units = {"K": 1 << 10, "M": 1 << 20, "G": 1 << 30}
    cs = args.cache_size.strip().upper()
    cache_bytes = int(float(cs[:-1]) * units[cs[-1]]) if cs[-1] in units else int(cs)
    regions = max(64, min(8192, cache_bytes // (384 * 384 * 4)))

    global GEN, CURVE
    try:
        CURVE = _bridge_curve(args.seed)
    except Exception as e:  # noqa: BLE001 - settling degrades to metre-level water
        print(f"[{MODEL_NAME}] bridge height curve unavailable ({e}); block settling off", flush=True)
    GEN = WorldGenerator(model_dir, args.seed, device,
                         GenConfig(t_start=args.t_start, steps=args.steps, amp_scale=args.amp_scale,
                                   river_log_threshold=args.river_threshold),
                         cache_regions=regions)
    if CURVE is not None:
        GEN.set_block_scale(BlockScale.from_curve(CURVE, device))
    print(f"[{MODEL_NAME}] rivers: {GEN.river.describe()}", flush=True)
    print(f"[{MODEL_NAME}] model {GEN.model_id} on {device}, seed {args.seed}, "
          f"t_start {args.t_start}, steps {args.steps}, river threshold {GEN.cfg.river_log_threshold}, "
          f"{regions} cached regions", flush=True)
    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
