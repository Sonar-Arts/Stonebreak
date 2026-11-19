"""Post-training FP8 error of detail checkpoints: sample the same val crops with the same noise in full precision
and under FP8 fake quantisation, report the difference in metres (decoded residual), as v4's PTQ note did
("fake-quant e4m3 moves samples 28-42 m RMS").

    python -m terrain_slm.eval.quant_ptq A.pt [B.pt ...] [--steps 16] [--act dynamic|fixed|none]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from terrain_slm.eval.detail_ab import load, sample
from terrain_slm.models import detail as DT
from terrain_slm.quant.fp8 import fp8_convs
from terrain_slm.train.train_detail import DetailData


@torch.no_grad()
def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoints", type=Path, nargs="+")
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--steps", type=int, default=16)
    ap.add_argument("--act", default="dynamic")
    ap.add_argument("--batches", type=int, default=4)
    ap.add_argument("--device", default="cuda:0")
    args = ap.parse_args(argv)
    data = DetailData(args.data_dir, args.device)
    for p in args.checkpoints:
        m, ck = load(p, args.device)
        rms, worst, n = [], 0.0, 0
        for k, (cond, x1, base, dem) in enumerate(data.val_batches(32)):
            if k >= args.batches:
                break
            g = torch.Generator(device=x1.device).manual_seed(k)
            noise = torch.randn(x1.shape, generator=g, device=x1.device)
            ref = DT.decode(sample(m, cond, noise, args.steps))
            with fp8_convs(act=args.act, skip_in_channels=(1 + DT.N_COND, 1 + DT.N_COND + 1)):
                q = DT.decode(sample(m, cond, noise, args.steps))
            d = (q - ref).flatten(1)
            rms += d.pow(2).mean(1).sqrt().tolist()
            worst = max(worst, float(d.abs().max()))
            n += x1.shape[0]
        print(json.dumps({"ckpt": str(p), "arch": type(m).__name__, "act": args.act, "steps": args.steps,
                          "rms_m_median": round(float(np.median(rms)), 2), "rms_m_p90": round(float(np.percentile(rms, 90)), 2),
                          "worst_px_m": round(worst, 1), "crops": n}), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
