"""Measure an SR node's sampling gain: the residual amplitude of N-step samples (x gain) vs the real fine terrain on the
held-out crops, the same statistic train_sr.evaluate reports (`amp_ratio_median`).

    python -m terrain_slm.eval.sr_gain --ckpt checkpoints/exp2/sr15_r0/ema_0.10.pt \
        --data-dir /mnt/extrastorage/terrain-slm-data/v2-15m --gains 1.0 1.1 1.15 1.2 1.25

Few-step flow samples come out smoother than the data (SR-30 at 8 steps: amp 0.83, fixed by SR_GAIN 1.18 in
generator2). Only the validation regions are loaded (whole held-out tiles for 15 m data).
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


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", type=Path, required=True)
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--steps", type=int, default=8)
    ap.add_argument("--gains", type=float, nargs="+", default=[1.0, 1.1, 1.15, 1.2, 1.25, 1.3])
    ap.add_argument("--per-region", type=int, default=32)
    ap.add_argument("--batch", type=int, default=16)
    args = ap.parse_args()
    ck = torch.load(args.ckpt, map_location="cpu", weights_only=False)
    model = SR.SR(SR.SRConfig(**ck["config"])).to(args.device).to(memory_format=torch.channels_last).eval()
    model.load_state_dict(ck["model"])
    with tempfile.TemporaryDirectory() as tmp:            # only the held-out regions (whole tiles or val corners)
        for meta_p in sorted(args.data_dir.glob("*/meta.json")):
            meta = json.loads(meta_p.read_text())
            if meta.get("val_whole") or meta.get("val_tiles"):
                (Path(tmp) / meta_p.parent.name).symlink_to(meta_p.parent.resolve())
        data = SRData(Path(tmp), args.device, val_per_region=args.per_region)
        amps = {g: [] for g in args.gains}
        n = 0
        with torch.no_grad():
            for cond, x1, coarse, fine in data.val_batches(args.batch):
                g = torch.Generator(device=x1.device).manual_seed(n)
                noise = torch.randn(x1.shape, generator=g, device=x1.device)
                x = SR.sample(model, cond, noise, args.steps)
                up = SR.upsample(coarse)
                real = (fine - up).flatten(1).std(1).clamp_min(1e-3)
                for gain in args.gains:
                    out = SR.assemble(coarse, x * gain)
                    amps[gain] += ((out - up).flatten(1).std(1) / real).tolist()
                n += x1.shape[0]
    print(f"{args.ckpt}: {n} crops from {len(data.regions)} held-out regions ({data.px_m:g} m), {args.steps} steps")
    for gain in args.gains:
        a = np.array(amps[gain])
        print(f"  gain {gain:.3f}: amp median {np.median(a):.3f}  p25 {np.percentile(a, 25):.3f}  p75 {np.percentile(a, 75):.3f}")


if __name__ == "__main__":
    main()
