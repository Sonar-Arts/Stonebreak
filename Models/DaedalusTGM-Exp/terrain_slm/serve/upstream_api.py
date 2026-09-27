"""DaedalusTGM-Exp model server: drop-in replacement for upstream's `terrain_diffusion.inference.minecraft_api`.

Speaks the exact contract terrain-bridge's UpstreamClient uses:
  GET /health  -> {"status": "ok", "seed": ..., "name": "DaedalusTGM-Exp", "model": ...}
  GET /terrain?i1&j1&i2&j2[&scale=1][&downscale=1][&noise=1.0][&elev_only=0][&water=0][&format=json]
      -> elevation int16 LE (floor of metres), then biome id int16 LE (unless elev_only),
         then -- only with water=1 -- the river water SURFACE int16 LE (floor of metres,
         WATER_NONE where dry); headers X-Height / X-Width / X-Dtype: int16-le.
Elevation always has the rivers carved in (terrain_slm.world.rivers), whether or not the
water plane is requested, so every client sees the same ground.

`downscale=D` (with scale=1) serves blocks coarser than the 30 m model grid: each block
averages D x D native pixels (the 1:4 world is D=2, 60 m blocks). That path also settles
water at BLOCK resolution against the bridge's own height curve (built from the same
TERRAIN_BRIDGE_* environment the bridge reads): at least one block of water per river
column, and dry neighbours raised to the water level, so nothing spills once quantised.
Coordinates are in target-resolution pixels; native pixels are 30 m, `scale` upsamples
(bilinear + upstream's slope-scaled detail noise), and biomes come from upstream's own
classifier (vendored in terrain_slm.biomes) so ids stay game-compatible.

Accepts the same CLI flags TerrainServiceProcessManager passes to upstream
(model path, --no-compile, --device, --cache-size, --port, --hdf5-file, --seed).
"""
from __future__ import annotations

import argparse
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
from terrain_slm.world import rivers as RV
from terrain_slm.world.generator import GenConfig, WorldGenerator

NATIVE_M = 30.0
WATER_NONE = -32768
CURVE = None  # bridge HeightCurve, for block-resolution water settling (downscale path)


def _bridge_curve(seed: int):
    """The bridge's metres->blocks curve from the TERRAIN_BRIDGE_* environment (the process
    manager passes both services the same values), so blocks settled here are the blocks
    the bridge will produce."""
    if str(BRIDGE_DIR) not in sys.path:
        sys.path.insert(0, str(BRIDGE_DIR))
    from bridge.config import BridgeConfig
    from bridge.height_mapping import HeightCurve

    return HeightCurve.from_config(BridgeConfig.from_env(seed=seed))


def _downsampled(i1: int, j1: int, i2: int, j2: int, d: int, want_water: bool):
    """Blocks of d x d native pixels, with water settled at block resolution.

    Computes one block of margin all round (for the classifier's slope and the neighbour
    rule), then crops. Returns (elev_m, biome, water_m or None), all (h, w).
    """
    h, w = i2 - i1, j2 - j1
    n1, m1 = (i1 - 1) * d, (j1 - 1) * d
    elev_n, surf_n = GEN.terrain(n1, m1, (i2 + 1) * d, (j2 + 1) * d)
    pool = lambda x: torch.nn.functional.avg_pool2d(x[None, None], d)[0, 0]
    minpool = lambda x: -torch.nn.functional.max_pool2d(-x[None, None], d)[0, 0]
    wet_n = ~torch.isnan(surf_n)
    wet_frac = pool(wet_n.float())
    wet = wet_frac >= 0.25
    # Riverbed = the channel floor inside the block, not the bank average; water top = the
    # lowest wet surface in it (conservative).
    bed = minpool(elev_n)
    top = minpool(torch.where(wet_n, surf_n, torch.full_like(surf_n, float("inf"))))
    elev = torch.where(wet, bed, pool(elev_n))
    wet = wet & torch.isfinite(top) & (elev > 0.5)

    ci0, cj0 = n1, m1
    clim_n = GEN.climate_native(ci0, cj0, ci0 + (h + 2) * d, cj0 + (w + 2) * d, elev_n)
    climate = torch.nn.functional.avg_pool2d(clim_n[None], d)[0]

    water = None
    if CURVE is not None:
        c = CURVE
        hb = torch.from_numpy(c.to_block_height(elev.cpu().numpy()).astype(np.int32)).to(elev.device)
        lb = torch.from_numpy(c.to_block_height(torch.nan_to_num(top, posinf=0.0).cpu().numpy())
                              .astype(np.int32)).to(elev.device)
        lb = torch.where(wet, torch.maximum(lb, hb + 1), torch.zeros_like(lb))
        # Neighbour rule at block resolution: a dry column beside water must stand at least
        # as high as that water, or the water would pour into it. One local pass: raise it.
        held = torch.nn.functional.max_pool2d(torch.where(wet, lb, torch.full_like(lb, -1))[None, None].float(),
                                              3, 1, 1)[0, 0].int()
        dry_beside = (~wet) & (held >= 0) & (elev > 0.5)
        hb = torch.where(dry_beside, torch.maximum(hb, held), hb)
        # Back to metres that land mid-band on exactly those blocks.
        to_m = lambda b: torch.from_numpy(c.to_elevation(b.cpu().numpy().astype(np.float64) + 0.5)
                                          .astype(np.float32)).to(elev.device)
        land = elev > 0.5
        elev = torch.where(land | wet, to_m(hb), elev)
        water = torch.where(wet, to_m(lb), torch.full_like(elev, float("nan")))
    elif want_water:
        water = torch.where(wet, top, torch.full_like(top, float("nan")))

    crop = lambda x: x[..., 1:-1, 1:-1]
    biome = B._classify_biome(crop(elev), crop(climate), i1, j1, elev_padded=elev, pixel_size_m=NATIVE_M * d)
    return crop(elev), biome, (crop(water) if water is not None else None)
app = Flask(__name__)
GEN: WorldGenerator | None = None


def _binary(elev: torch.Tensor, biome: torch.Tensor | None, water: torch.Tensor | None = None) -> Response:
    e = np.clip(np.floor(elev.detach().float().cpu().numpy()), -32767, 32767).astype("<i2")
    payload = e.tobytes()
    if biome is not None:
        payload += biome.detach().cpu().numpy().astype("<i2").tobytes()
    if water is not None:
        wv = water.detach().float().cpu().numpy()
        wi = np.where(np.isnan(wv), WATER_NONE, np.clip(np.floor(wv), -32767, 32767)).astype("<i2")
        payload += wi.tobytes()
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
        down = request.args.get("downscale", default=1, type=int)
        if down < 1 or (down > 1 and scale != 1):
            raise ValueError("downscale must be >= 1 and needs scale=1")
        if down > 1:
            with GEN.lock:
                elev, biome, water = _downsampled(i1, j1, i2, j2, down, want_water)
            if request.args.get("elev_only", default=0, type=int) == 1:
                return _json(elev) if as_json else _binary(elev, None)
            return _json(elev) if as_json else _binary(elev, biome, water if want_water else None)
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
    ap.add_argument("model_path", nargs="?", default="checkpoints/v2")
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
    print(f"[{MODEL_NAME}] model {GEN.model_id} on {device}, seed {args.seed}, "
          f"t_start {args.t_start}, steps {args.steps}, river threshold {GEN.cfg.river_log_threshold}, "
          f"{regions} cached regions", flush=True)
    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
