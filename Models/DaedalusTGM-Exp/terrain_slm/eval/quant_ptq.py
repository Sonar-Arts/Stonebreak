"""FP8 error of Detail2 checkpoints on the generator's REAL detail windows (2026-11-19 v2): decoded residual RMS (m) of the
kernel-exact simulation (quant/detail2_sim.py) -- and optionally of the FP8 kernels themselves -- against the fp32
TEACHER, the metric of the native fp16 gate (tests/test_cuda_detail2.py: native fp16 0.11 m, torch bf16 1.13 m median).

    python -m terrain_slm.eval.quant_ptq STUDENT.pt [--teacher T.pt] [--keep stem,down0,...] [--kernel]
    python -m terrain_slm.eval.quant_ptq T.pt --sweep          # per-layer sensitivity: one layer e4m3 at a time
    python -m terrain_slm.eval.quant_ptq T.pt --greedy 3,8,16  # keep the k most sensitive layers fp16, rest e4m3
    python -m terrain_slm.eval.quant_ptq A.pt --legacy         # v1: val crops, quant/fp8.py patch under bf16 autocast

The checkpoint's own "quant" record (train_qad) gives the default --keep / activation scale.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from terrain_slm.eval.detail_ab import load, sample
from terrain_slm.models import detail as DT
from terrain_slm.quant import detail2_sim as Q

ROOT = Path(__file__).resolve().parents[2]
TEACHER = ROOT / "checkpoints" / "exp2" / "d2_full_v2_mf_experts" / "ema_0.10.pt"
WINDOWS = ROOT / "checkpoints" / "exp2" / "qad_fp8_eval_windows.pt"


def _model(path: Path, dev: str):
    m, ck = load(path, dev)
    m.mean_flow = bool(ck["config"].get("mean_flow", True))
    return m.eval(), ck


def _summ(a) -> dict:
    a = np.asarray(a)
    return {"median": round(float(np.median(a)), 3), "p90": round(float(np.percentile(a, 90)), 3),
            "max": round(float(a.max()), 3)}


@torch.no_grad()
def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoints", type=Path, nargs="+")
    ap.add_argument("--teacher", type=Path, default=TEACHER)
    ap.add_argument("--keep", default=None, help="layers kept fp16 (default: the checkpoint's QAD record, else stem)")
    ap.add_argument("--act-scale", type=float, default=None)
    ap.add_argument("--sweep", action="store_true")
    ap.add_argument("--greedy", default=None)
    ap.add_argument("--sweep-json", type=Path, default=None, help="--greedy: order from this --sweep output")
    ap.add_argument("--kernel", action="store_true", help="also run the FP8 kernels (kernels/cuda_detail2_fp8.py)")
    ap.add_argument("--windows", type=Path, default=WINDOWS)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--legacy", action="store_true")
    ap.add_argument("--data-dir", type=Path, default=None, help="--legacy only: .../terrain-slm-data/v2-gedtm30")
    ap.add_argument("--steps", type=int, default=16)
    ap.add_argument("--act", default="dynamic")
    ap.add_argument("--batches", type=int, default=4)
    args = ap.parse_args(argv)
    if args.legacy:
        if args.data_dir is None:
            ap.error("--legacy needs --data-dir (e.g. /mnt/extrastorage/terrain-slm-data/v2-gedtm30)")
        return legacy(args)
    from terrain_slm.train.train_qad import eval_windows
    dev = args.device
    torch.cuda.set_device(dev)
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = True   # exact on pre-rounded operands
    ws = eval_windows(dev, args.windows)
    teacher, _ = _model(args.teacher, dev)
    refs = [Q.fp32_sample(teacher, c, z, st, t0, gates=g) for c, z, g, st, t0 in ws]
    dec = lambda x: DT.decode(x.clamp(-7.0, 7.0))
    rms = lambda a, b: float((dec(a) - dec(b)).pow(2).mean().sqrt())

    def sim_err(m, qc, idx=None):
        sim = Q.Detail2Sim(m, qc)
        sel = range(len(ws)) if idx is None else idx
        return [rms(sim.sample(ws[i][0], ws[i][1], ws[i][3], ws[i][4], gates=ws[i][2]), refs[i]) for i in sel]

    for path in args.checkpoints:
        m, ck = _model(path, dev)
        from terrain_slm.kernels.cuda_detail2_fp8 import qconfig_of
        qc = qconfig_of(ck if "quant" in ck else None)
        if args.keep is not None:
            qc.layers = {k: "fp16" for k in args.keep.split(",") if k}
        if args.act_scale is not None:
            qc.act_scale = args.act_scale
        styles = tuple(s for s in m.adapters)
        if args.sweep:
            sub = list(range(0, len(ws), 3))
            base = float(np.sqrt(np.mean(np.square(sim_err(m, Q.QConfig(), sub)))))
            out = {"_fp16": base}
            for name in Q.layer_names(m, styles):
                e = sim_err(m, Q.QConfig(layers={name: "e4m3"}, act_scale=qc.act_scale), sub)
                out[name] = float(np.sqrt(np.mean(np.square(e))))
                print(f"{name:32s} {out[name]:.3f}", flush=True)
            if args.sweep_json:
                args.sweep_json.write_text(json.dumps(out, indent=1))
            continue
        if args.greedy:
            sens = json.loads(args.sweep_json.read_text())
            order = [k for k, v in sorted(sens.items(), key=lambda kv: -kv[1]) if not k.startswith(("_", "ad."))]
            for k in (int(x) for x in args.greedy.split(",")):
                keep = order[:k]
                e = sim_err(m, Q.QConfig(default="e4m3", layers={l: "fp16" for l in keep}, act_scale=qc.act_scale))
                print(json.dumps({"ckpt": str(path), "keep_k": k, "keep": keep, **_summ(e)}), flush=True)
            continue
        row = {"ckpt": str(path), "fp16_layers": sorted(k for k, v in qc.layers.items() if v == "fp16"),
               "act_scale": qc.act_scale, "sim_fp8": _summ(sim_err(m, qc)), "sim_fp16": _summ(sim_err(m, Q.QConfig()))}
        if args.kernel:
            from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8
            ns = NativeDetail2SamplerFP8(m, ws[0][0].shape[-2], ws[0][0].shape[-1], ws[0][3], ws[0][4], qc=qc)
            xs = [ns(c, z, g) for c, z, g, *_ in ws]
            row["kernel_fp8"] = _summ([rms(x, r) for x, r in zip(xs, refs)])
            row["kernel_max_abs_x"] = round(max(float(x.nan_to_num(1e9).abs().max()) for x in xs), 3)
        print(json.dumps(row), flush=True)
    return 0


def legacy(args) -> int:
    from terrain_slm.quant.fp8 import fp8_convs
    from terrain_slm.train.train_detail import DetailData
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
