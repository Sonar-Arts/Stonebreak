"""Train the climate node (models/climatenet.py) on the real planet: geography + latitude belt + ERA5 wind (+ the
upwind sea / rain-shadow fields derived from them) -> climate (plan §5b; targets: data/climate_hybrid, CHELSA 1 km
block-averaged to 0.25 deg + ERA5 soil/wind).

    python -m terrain_slm.train.train_climate --data /mnt/extrastorage/terrain-slm-data --out checkpoints/exp2/climate

Inputs and targets live on ERA5's 0.25 deg grid (721 x 1440). Land elevation and land fraction come from GEBCO
(block means of its 15" grid, built once and cached as raw/era5/geo_025.npz); targets from raw/era5/climatology.npz
(data/climate_era5.py). Validation holds out whole regions (Australia, southern Africa, Patagonia) so the node is
judged on geography it never saw. Loss: masked MSE over land and the first ~250 km of sea (open-ocean climate is
irrelevant to a world's land).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.models import climatenet as C
from terrain_slm.models.mp import force_weight_norm_

H, W = 721, 1440
VAL_BOXES = [(-45, -10, 112, 155), (-35, -15, 12, 35), (-56, -38, -76, -60)]   # (lat0, lat1, lon0, lon1) east+


def gebco_025(gebco: Path, out: Path) -> dict[str, np.ndarray]:
    """GEBCO 15" -> 0.25 deg block means (elevation of land, land fraction), aligned with ERA5 cell centres."""
    if out.exists():
        return dict(np.load(out))
    import rasterio
    from rasterio.windows import Window

    elev = np.zeros((H, W), np.float32)
    land = np.zeros((H, W), np.float32)
    with rasterio.open(gebco) as src:                 # 86400 x 43200, 15" from (-180, 90)
        k = 60                                        # 15" per pixel -> 60 px per 0.25 deg
        for i in range(H):
            lat_c = 90.0 - 0.25 * i                   # ERA5 row centre
            r0 = int(round((90.0 - (lat_c + 0.125)) * 240))
            r1 = int(round((90.0 - (lat_c - 0.125)) * 240))
            r0, r1 = max(0, r0), min(src.height, r1)
            if r1 <= r0:
                continue
            band = src.read(1, window=Window(0, r0, src.width, r1 - r0)).astype(np.float32)
            # ERA5 column j is centred on lon = 0.25 j east; GEBCO column c on lon = -180 + (c + 0.5) / 240.
            band = np.roll(band, -src.width // 2, axis=1)          # now column 0 starts at lon 0
            band = np.concatenate([band[:, -k // 2 :], band[:, : -k // 2]], axis=1)   # centre cells on 0.25 j
            b = band[:, : W * k].reshape(band.shape[0], W, k)
            land_px = b > 0
            land[i] = land_px.mean((0, 2))
            elev[i] = np.where(land_px, b, 0).sum((0, 2)) / np.maximum(land_px.sum((0, 2)), 1)
            if i % 60 == 0:
                print(f"  gebco rows {i}/{H}", flush=True)
    np.savez(out, elev=elev, land=land)
    return {"elev": elev, "land": land}


def build_inputs(geo: dict, clim: dict, forcing_dt: float = 0.0, age_ka: float = 0.0) -> np.ndarray:
    from scipy import ndimage

    lat = 90.0 - 0.25 * np.arange(H)
    land = geo["land"]
    lat_km = 27.8
    blur = lambda a, km: ndimage.gaussian_filter(a, (km / lat_km, km / lat_km), mode=("nearest", "wrap"))
    sea = land < 0.5
    d_land = ndimage.distance_transform_edt(sea) * lat_km
    d_sea = ndimage.distance_transform_edt(~sea) * lat_km
    coast = np.arcsinh(np.where(sea, -d_land, d_sea) / 50.0)
    s = np.sin(np.radians(lat))[:, None].repeat(W, 1)
    elev_km = np.maximum(geo["elev"], 0) / 1000.0 * (land > 0)
    l250, l1000 = blur(land, 250), blur(land, 1000)
    up = global_upwind(land, elev_km, clim["u10"], clim["v10"], l250, l1000)
    const = lambda v: np.full((H, W), v, np.float32)
    x = np.stack([elev_km, land, l250, l1000, blur(land, 2500), coast, s, np.abs(s), clim["u10"] / 5.0,
                  clim["v10"] / 5.0, *up, const(forcing_dt / C.FORCING_DT_SCALE),
                  const(age_ka / C.FORCING_AGE_SCALE)]).astype(np.float32)
    assert x.shape[0] == len(C.IN_NAMES)
    return x, (coast > -np.arcsinh(250 / 50.0)).astype(np.float32)


def global_upwind(land, elev_km, u, v, l250, l1000) -> np.ndarray:
    """climatenet.upwind_features on the 0.25 deg globe: bilinear fetch, longitude wraps, rows clamp at the poles,
    columns shrink with cos latitude (floored at 0.2 so polar cells do not reach around the world)."""
    lat = 90.0 - 0.25 * np.arange(H)
    km_row = 27.8
    km_col = torch.from_numpy((27.8 * np.maximum(np.cos(np.radians(lat)), 0.2))[:, None].astype(np.float32))
    pad = 220                                                  # > 1000 km / (27.8 km * 0.2) columns
    fields = torch.from_numpy(np.stack([land, elev_km]).astype(np.float32))
    padded = torch.cat([fields[:, :, -pad:], fields, fields[:, :, :pad]], dim=2)[None]
    rows = torch.arange(H, dtype=torch.float32)[:, None].expand(H, W)
    cols = torch.arange(W, dtype=torch.float32)[None, :].expand(H, W)

    def fetch(drow, dcol):
        r = (rows + drow).clamp(0, H - 1)
        c = cols + dcol + pad
        grid = torch.stack([c / (W + 2 * pad - 1) * 2 - 1, r / (H - 1) * 2 - 1], -1)[None]
        out = F.grid_sample(padded, grid, mode="bilinear", align_corners=True)[0]
        return out[0], out[1]

    t = lambda a: torch.from_numpy(np.asarray(a, np.float32))
    return C.upwind_features(fetch, t(u), t(v), km_row, km_col, t(elev_km), t(l250), t(l1000)).numpy()


def val_mask() -> np.ndarray:
    lat = 90.0 - 0.25 * np.arange(H)
    lon = 0.25 * np.arange(W)
    m = np.zeros((H, W), bool)
    for la0, la1, lo0, lo1 in VAL_BOXES:
        lo = np.mod(np.array([lo0, lo1]), 360.0)
        cols = (lon >= lo[0]) & (lon <= lo[1]) if lo[0] <= lo[1] else (lon >= lo[0]) | (lon <= lo[1])
        m |= ((lat >= la0) & (lat <= la1))[:, None] & cols[None, :]
    return m


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--patch", type=int, default=96)
    ap.add_argument("--lr", type=float, default=4e-3)
    ap.add_argument("--paleo", action="store_true",
                    help="add the CHELSA-TraCE21k slices (data/climate_paleo) as extra planets")
    ap.add_argument("--p-present", type=float, default=0.5, help="share of training crops from today's planet")
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    raw = args.data / "raw/era5"
    clim = dict(np.load(raw / "climatology.npz"))
    clim_targets = {"t0": clim["t_mean"] + 0.0065 * np.maximum(clim["orog"], 0), "tseason": clim["tseason"],
                    "precip": clim["precip"], "pcv": clim["pcv"], "aridity": clim["aridity"], "soil": clim["soil"],
                    "snow": clim["snow"]}
    geo = gebco_025(args.data / "raw/gebco/GEBCO_2024.tif", raw / "geo_025.npz")
    x, weight = build_inputs(geo, clim)
    y = C.norm_targets(clim_targets)
    vm = val_mask()
    dev = args.device
    VAL = torch.from_numpy(vm).to(dev)
    on = lambda a: torch.from_numpy(np.ascontiguousarray(a)).to(dev)
    # planet = (inputs, targets, weight, per-output mask); planet 0 is today's (the only one validated on)
    planets = [(on(x), on(y), on(weight), torch.ones(len(C.OUT_NAMES), device=dev))]
    if args.paleo:
        from terrain_slm.data import climate_paleo as CP

        known = torch.tensor([float(k in ("t0", "tseason", "log_precip", "pcv")) for k in C.OUT_NAMES], device=dev)
        for t in CP.SLICES:
            pc = dict(np.load(args.data / "raw" / "chelsa_trace" / f"paleo_{t}.npz"))
            xp, wp = build_inputs({"elev": pc["elev"], "land": pc["land"]}, {"u10": clim["u10"], "v10": clim["v10"]},
                                  float(pc["forcing_dt"]), float(pc["age_ka"]))
            zero = np.zeros_like(pc["precip"])
            yp = C.norm_targets({"t0": pc["t_mean"] + 0.0065 * np.maximum(pc["orog"], 0), "tseason": pc["tseason"],
                                 "precip": pc["precip"], "pcv": pc["pcv"], "aridity": zero + 1.0, "soil": zero + 0.25,
                                 "snow": zero})
            planets.append((on(xp), on(yp), on(wp * ~vm), known))   # held-out geography stays held out
        print(f"planets: today + {len(planets) - 1} paleo slices {CP.SLICES}", flush=True)
    model = C.ClimateNet(C.ClimateConfig(n_in=len(C.IN_NAMES))).to(dev)
    force_weight_norm_(model)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, betas=(0.9, 0.99))
    p = args.patch
    print(f"climatenet {C.count_params(model):,} params; land+coast cells {int(weight.sum())}, val {int(vm.sum())}",
          flush=True)
    log = open(args.out / "log.jsonl", "a")
    t0 = time.time()
    best = float("inf")

    def crops(n, val: bool):
        out = []
        while len(out) < n:
            k = 0 if val or len(planets) == 1 or float(torch.rand(1)) < args.p_present else \
                int(torch.randint(1, len(planets), (1,)))
            Wt = planets[k][2]
            i = int(torch.randint(0, H - p, (1,)))
            j = int(torch.randint(0, W, (1,)))
            cols = torch.arange(j, j + p, device=dev) % W
            inval = VAL[i : i + p][:, cols].float().mean() > 0.3
            if bool(inval) != val or Wt[i : i + p][:, cols].mean() < 0.05:
                continue
            out.append((k, i, cols))
        xs = torch.stack([planets[k][0][:, i : i + p][:, :, c] for k, i, c in out])
        ys = torch.stack([planets[k][1][:, i : i + p][:, :, c] for k, i, c in out])
        ws = torch.stack([(planets[k][2][i : i + p][:, c] * (VAL[i : i + p][:, c] == val)) for k, i, c in out])[:, None]
        ms = torch.stack([planets[k][3] for k, _, _ in out])[:, :, None, None]
        return xs, ys, ws, ms

    for step in range(1, args.steps + 1):
        lr = args.lr * min(1.0, step / 500) / math.sqrt(max(step / 5000, 1.0))
        for g in opt.param_groups:
            g["lr"] = lr
        force_weight_norm_(model)
        xb, yb, wb, mb = crops(args.batch, False)
        if torch.rand(1) < 0.5:                       # east-west mirror (wind u flips sign)
            xb, yb, wb = xb.flip(-1), yb.flip(-1), wb.flip(-1)
            xb[:, C.IN_NAMES.index("wind_u")] *= -1
        if torch.rand(1) < 0.5:                       # north-south mirror: the other hemisphere's geography (belt and
            xb, yb, wb = xb.flip(-2), yb.flip(-2), wb.flip(-2)       # wind v flip sign; upwind fields are geometric)
            xb[:, C.IN_NAMES.index("belt")] *= -1
            xb[:, C.IN_NAMES.index("wind_v")] *= -1
        loss = ((model(xb) - yb).pow(2) * wb * mb).sum() / (wb * mb).sum().clamp_min(1)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        if step % 200 == 0:
            print(f"step {step:6d} loss {loss.item():.4f} lr {lr:.2e}", flush=True)
        if step % 2000 == 0 or step == args.steps:
            model.eval()
            with torch.no_grad():
                torch.manual_seed(123)
                xv, yv, wv, _ = crops(64, True)
                pv = model(xv)
                per = (((pv - yv).pow(2) * wv).sum((0, 2, 3)) / wv.sum().clamp_min(1)).sqrt()
            model.train()
            ev = {"step": step, "min": round((time.time() - t0) / 60, 1), "val_rmse_norm": dict(zip(C.OUT_NAMES, [round(float(v), 3) for v in per]))}
            ev["val_t0_rmse_C"] = round(float(per[0]) * C.OUT_NORM["t0"][1], 2)
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", json.dumps(ev), flush=True)
            mean = float(per.mean())
            if mean < best:
                best = mean
                torch.save({"model": model.state_dict(), "config": model.cfg.to_dict(), "step": step, "eval": ev},
                           args.out / "climate.pt")


if __name__ == "__main__":
    main()
