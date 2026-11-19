"""Compare SR nodes per held-out region (agent L, SR-15 v2): amplitude ratio, residual spectrum by band, RMS.

    python -m terrain_slm.eval.sr_compare --data-dir /mnt/extrastorage/terrain-slm-data/v2-15m-lidar \
        --ckpt A=checkpoints/exp2/sr15_r0/ema_0.10.pt:1.14 --ckpt B=checkpoints/exp2/sr15_v2/ema_0.10.pt:1.10 \
        [--per-region 32] [--steps 8] [--json out.json]

Same crops and noise for every model (SRData val crops of the whole held-out tiles, seeds by crop index). Per region:
- amp: median over crops of std(sample - up) / std(real - up) (train_sr's statistic; 1 = right amplitude);
- bands: power of the residual in wavelength bands (2-4, 4-8, 8-16 px = 30-60, 60-120, 120-240 m at 15 m), as the
  ratio sample / real (1 = right texture at that scale);
Crops that touch filled no-data / sea (sea.npy, > 2 % of the crop) or whose real residual is < 0.05 m (flat water) are
skipped: their ratios are meaningless (nearest-filled survey gaps make cliffs, flat sea makes 0/0).
- rms: median over crops of the RMS of (sample - real) in metres (an SR sample is a draw, not a prediction: lower is not necessarily better,
  reported for completeness), and of (up - real) = the bilinear baseline;
- slope: mean |gradient| ratio sample / real (steepness at 15 m).
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

import numpy as np
import torch

from terrain_slm.models import sr as SR
from terrain_slm.train.train_sr import SRData

BANDS = ((2, 4), (4, 8), (8, 16))     # wavelength in fine pixels


def band_power(x: torch.Tensor) -> torch.Tensor:
    """(B, 1, H, W) residuals -> (B, len(BANDS)) power per wavelength band (Hann window)."""
    b, _, h, w = x.shape
    win = torch.hann_window(h, device=x.device)[:, None] * torch.hann_window(w, device=x.device)[None]
    f = torch.fft.fft2((x[:, 0] - x[:, 0].mean((-1, -2), keepdim=True)) * win)
    p = f.real ** 2 + f.imag ** 2
    fy = torch.fft.fftfreq(h, device=x.device)[:, None]
    fx = torch.fft.fftfreq(w, device=x.device)[None]
    k = torch.sqrt(fy * fy + fx * fx).clamp_min(1e-9)
    lam = 1.0 / k
    return torch.stack([(p * ((lam >= a) & (lam < b_))).sum((-1, -2)) for a, b_ in BANDS], 1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--ckpt", action="append", required=True, help="NAME=path:gain")
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--steps", type=int, default=8)
    ap.add_argument("--per-region", type=int, default=32)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--json", type=Path, default=None)
    args = ap.parse_args()
    models = {}
    for spec in args.ckpt:
        name, rest = spec.split("=", 1)
        path, gain = rest.rsplit(":", 1)
        ck = torch.load(path, map_location="cpu", weights_only=False)
        m = SR.SR(SR.SRConfig(**{k: (tuple(v) if k == "channels" else v) for k, v in ck["config"].items()}))
        m.load_state_dict(ck["model"])
        models[name] = (m.to(args.device).to(memory_format=torch.channels_last).eval(), float(gain))
    res: dict = {}
    with tempfile.TemporaryDirectory() as tmp:
        for meta_p in sorted(args.data_dir.glob("*/meta.json")):
            meta = json.loads(meta_p.read_text())
            if meta.get("val_whole"):
                (Path(tmp) / meta_p.parent.name).symlink_to(meta_p.parent.resolve())
        data = SRData(Path(tmp), args.device, val_per_region=args.per_region)
        names = [r["name"] for r in data.regions]
        acc = {n: {m: {"amp": [], "bands": [], "rms": [], "slope": []} for m in list(models) + ["bilinear"]}
               for n in names}
        n0 = 0
        with torch.no_grad():
            for cond, x1, coarse, fine in data.val_batches(args.batch):
                b = x1.shape[0]
                region = [names[data.val[n0 + i][0]] for i in range(b)]
                n_crop = 256 + 32
                keep = []
                for i in range(b):
                    ri, r0, c0 = data.val[n0 + i]
                    sea = data.regions[ri]["sea"][r0 : r0 + n_crop, c0 : c0 + n_crop].float().mean()
                    keep.append(float(sea) <= 0.02)
                g = torch.Generator(device=x1.device).manual_seed(n0)
                noise = torch.randn(x1.shape, generator=g, device=x1.device)
                up = SR.upsample(coarse)
                real = fine - up
                real_std = real.flatten(1).std(1).clamp_min(1e-3)
                real_bp = band_power(real)
                gy, gx = torch.gradient(fine[:, 0], dim=(-2, -1))
                real_sl = torch.sqrt(gx * gx + gy * gy).mean((-1, -2))
                outs = {"bilinear": up}
                for name, (m, gain) in models.items():
                    x = SR.sample(m, cond, noise, args.steps)
                    outs[name] = SR.assemble(coarse, x * gain)
                for name, out in outs.items():
                    r = out - up
                    amp = r.flatten(1).std(1) / real_std
                    bands = band_power(r) / real_bp.clamp_min(1e-9)
                    rms = (out - fine).flatten(1).pow(2).mean(1).sqrt()
                    gy, gx = torch.gradient(out[:, 0], dim=(-2, -1))
                    sl = torch.sqrt(gx * gx + gy * gy).mean((-1, -2)) / real_sl.clamp_min(1e-6)
                    for i in range(b):
                        if not keep[i] or float(real_std[i]) < 0.05:
                            continue
                        a = acc[region[i]][name]
                        a["amp"].append(float(amp[i]))
                        a["bands"].append(bands[i].tolist())
                        a["rms"].append(float(rms[i]))
                        a["slope"].append(float(sl[i]))
                n0 += b
    for n in names:
        res[n] = {}
        for m, a in acc[n].items():
            if not a["amp"]:
                continue
            res[n][m] = {"amp_median": float(np.median(a["amp"])), "bands_median": np.median(np.array(a["bands"]), 0).tolist(),
                         "rms_m": float(np.median(a["rms"])), "slope_ratio": float(np.median(a["slope"])), "crops": len(a["amp"])}
    hdr = (f"{'region':28s} {'model':10s} {'amp':>6s} " + " ".join(f"b{a * 15}-{b * 15}m" for a, b in BANDS)
           + "   rms_m  slope crops")
    print(hdr)
    for n in names:
        for m, r in res[n].items():
            print(f"{n:28s} {m:10s} {r['amp_median']:6.3f} " + " ".join(f"{v:9.3f}" for v in r["bands_median"])
                  + f"  {r['rms_m']:6.2f}  {r['slope_ratio']:5.3f} {r['crops']:4d}")
    # summary: mean |log ratio| over regions (0 = every region right), per model
    for m in models:
        rs = [res[n][m] for n in names if m in res[n]]
        la = np.mean([abs(np.log(r["amp_median"])) for r in rs])
        lb = np.mean([[abs(np.log(max(v, 1e-6))) for v in r["bands_median"]] for r in rs], 0)
        rr = np.mean([r["rms_m"] / res[n]["bilinear"]["rms_m"] for n, r in zip([n for n in names if m in res[n]], rs)])
        print(f"SUMMARY {m:6s} regions {len(rs)}  mean|log amp| {la:.3f}  mean|log band| " + " ".join(f"{v:.3f}" for v in lb)
              + f"  rms/bilinear {rr:.3f}")
    if args.json:
        args.json.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
