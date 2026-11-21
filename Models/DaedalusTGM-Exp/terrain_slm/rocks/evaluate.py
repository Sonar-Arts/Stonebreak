"""Novelty / plausibility / diversity metrics for generated rock shapes + preview sheets.

Novelty = nearest-neighbour IoU of a generated 32^3 occupancy to every TRAINING scan in 48 orientations (24 yaws x
mirror, each re-normalised to the canonical frame like the training data). A near-copy scores ~0.85+; the natural
scale is the same number between distinct real scans (leave-one-asset-out NN IoU of the training set).

    python -m terrain_slm.rocks.evaluate --ckpt checkpoints/rocks/rockvae_v1/last.pt --n 512
"""
from __future__ import annotations

import argparse
import json
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import torch
from scipy import ndimage

from . import dataset as D
from . import render as R
from . import shapes as S
from .model import RockVAE, cond_vector

REFS_DEFAULT = Path("/mnt/extrastorage/terrain-slm-data/rocks/rocks_v1_refs48.npz")


def _occ32(occ64: np.ndarray) -> np.ndarray:
    return occ64.reshape(32, 2, 32, 2, 32, 2).mean((1, 3, 5)) >= 0.5


def _orient_job(args):
    i, occ = args
    out = []
    a = occ.astype(np.float32)
    for m in (False, True):
        b = a[::-1] if m else a
        for yaw in range(0, 360, 15):
            r = ndimage.rotate(b, yaw, axes=(0, 2), order=1, reshape=True) >= 0.5 if yaw else b >= 0.5
            out.append(_occ32(D.canonical_occupancy(D.clean_occupancy(r))))
    return i, np.stack(out)


def build_refs(src: Path = D.OUT_DEFAULT, out: Path = REFS_DEFAULT, workers: int = 8):
    _, occ64, _, _, info = D.load(src)
    refs = np.zeros((len(occ64), 48, 32 ** 3), bool)
    with ProcessPoolExecutor(workers) as ex:
        for i, r in ex.map(_orient_job, list(enumerate(occ64)), chunksize=2):
            refs[i] = r.reshape(48, -1)
    assets = np.array([m["asset"] for m in info["shapes"]])
    np.savez_compressed(out, refs=np.packbits(refs, axis=2), assets=assets)
    return refs, assets


def load_refs(path: Path = REFS_DEFAULT):
    """(packed refs (N, 48, 4096) uint8 - np.packbits big-endian bit order, assets). Kept packed: 74 MB not 590."""
    z = np.load(path)
    return z["refs"], z["assets"]


_SHIFTS = torch.arange(7, -1, -1, dtype=torch.uint8)


def unpack(packed: torch.Tensor) -> torch.Tensor:
    """(..., B) uint8 big-endian bits -> (..., 8B) float32."""
    sh = _SHIFTS.to(packed.device)
    return ((packed[..., None] >> sh) & 1).flatten(-2).float()


def ref_occ(refs: np.ndarray, i: int, o: int = 0) -> np.ndarray:
    return np.unpackbits(refs[i, o])[:32 ** 3].astype(bool).reshape(32, 32, 32)


def nn_iou(gen: np.ndarray, refs: np.ndarray, device="cpu", chunk: int = 2048, orient: bool = False):
    """gen (G, 32^3) bool, refs packed (N, O, 4096) -> (max IoU per gen, index of the nearest scan
    [, its best orientation])."""
    g = torch.as_tensor(np.asarray(gen).reshape(len(gen), -1), dtype=torch.float32, device=device)
    gs = g.sum(1, keepdim=True)
    N, O, B = refs.shape
    flat = torch.as_tensor(refs.reshape(N * O, B), device=device)
    best = torch.zeros(len(gen), device=device)
    arg = torch.zeros(len(gen), dtype=torch.long, device=device)
    for i in range(0, len(flat), chunk):
        r = unpack(flat[i:i + chunk])
        inter = g @ r.T
        iou = inter / (gs + r.sum(1)[None] - inter).clamp(min=1)
        m, a = iou.max(1)
        upd = m > best
        best[upd] = m[upd]
        arg[upd] = a[upd] + i
    arg = arg.cpu().numpy()
    if orient:
        return best.cpu().numpy(), arg // O, arg % O
    return best.cpu().numpy(), arg // O


def real_nn_baseline(refs: np.ndarray, assets: np.ndarray, device="cpu") -> np.ndarray:
    """Each scan (orientation 0) vs every OTHER asset's scans in all orientations."""
    out = np.zeros(len(refs))
    for a in np.unique(assets):
        sel = assets == a
        own = np.unpackbits(refs[sel, 0], axis=1)[:, :32 ** 3].astype(bool)
        out[sel], _ = nn_iou(own, refs[~sel], device)
    return out


def pairwise_iou(gen: np.ndarray, device="cpu") -> np.ndarray:
    g = torch.as_tensor(gen.reshape(len(gen), -1), dtype=torch.float32, device=device)
    inter = g @ g.T
    s = g.sum(1)
    iou = inter / (s[:, None] + s[None] - inter).clamp(min=1)
    return iou.cpu().numpy()


def load_model(ckpt: Path, device="cpu") -> tuple[RockVAE, dict]:
    ck = torch.load(ckpt, map_location=device, weights_only=False)
    m = RockVAE(ck["latent"], ck["width"]).to(device)
    m.load_state_dict(ck["model"])
    m.eval()
    m.prior = None
    if "prior" in ck:
        from .prior import LatentFlow
        m.prior = LatentFlow(ck["latent"]).to(device)
        m.prior.load_state_dict(ck["prior"])
        m.prior.eval()
    return m, ck


@torch.no_grad()
def sample(model: RockVAE, style, size_bin, grounded, tau: float, gen: torch.Generator, device="cpu",
           batch: int = 64, use_prior: bool = True) -> np.ndarray:
    """SDF (cells of the 32 grid) of len(style) new shapes."""
    n = len(style)
    out = []
    for i in range(0, n, batch):
        c = cond_vector(style[i:i + batch], size_bin[i:i + batch], grounded[i:i + batch], device)
        if getattr(model, "prior", None) is not None and use_prior:
            z = model.prior.sample(c, gen, tau=tau)
        else:
            z = (torch.randn(len(c), model.latent, generator=gen) * tau).to(device)
        out.append(model.decode(z, c).float().cpu().numpy() * D.TRUNC)
    return np.concatenate(out)


def bank_sheet(path: Path, out: Path, per_class: int = 16, seed: int = 0):
    """Block-resolution renders of a baked SBRB bank (moss / rough hints tinted), `per_class` random shapes per class."""
    from . import sbrb
    _, shapes = sbrb.read_bank(path.read_bytes())
    rng = np.random.default_rng(seed)
    ims, labs = [], []
    for cls in sorted({s.cls for s in shapes}):
        idx = [i for i, s in enumerate(shapes) if s.cls == cls]
        for i in rng.choice(idx, min(per_class, len(idx)), replace=False):
            s = shapes[i]
            ims.append(R.render_voxels(s.occ, 128, hint=s.hint))
            labs.append(f"{sbrb.CLASS_NAMES[cls]} {s.dims[0]}x{s.dims[1]}x{s.dims[2]} sink{s.sink}")
    R.sheet(ims, labs, cols=8).save(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ckpt", type=Path, required=True)
    ap.add_argument("--n", type=int, default=512)
    ap.add_argument("--tau", type=float, default=1.0)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--no-prior", action="store_true", help="sample z ~ N(0, tau^2) instead of the latent flow")
    ap.add_argument("--out", type=Path, required=True, help="directory for metrics.json + sheets")
    a = ap.parse_args(argv)
    a.out.mkdir(parents=True, exist_ok=True)
    if not REFS_DEFAULT.exists():
        build_refs()
    refs, assets = load_refs()
    model, ck = load_model(a.ckpt, a.device)
    g = torch.Generator().manual_seed(a.seed)
    rng = np.random.default_rng(a.seed)
    style = rng.choice(4, a.n, p=[0.35, 0.3, 0.2, 0.15])
    size_bin = rng.choice(3, a.n, p=[0.2, 0.5, 0.3])
    grounded = (rng.random(a.n) < 0.3).astype(int)
    sdf = sample(model, style, size_bin, grounded, a.tau, g, a.device, use_prior=not a.no_prior)
    occ = sdf < 0
    plaus = [S.plausibility(s) for s in sdf]
    nov, near, near_o = nn_iou(occ, refs, a.device, orient=True)
    base = real_nn_baseline(refs, assets, a.device)
    pw = pairwise_iou(occ, a.device)
    np.fill_diagonal(pw, np.nan)
    # coverage: share of training scans with a generated shape within IoU 0.6
    cov, _ = nn_iou(np.unpackbits(refs[:, 0], axis=1)[:, :32 ** 3].astype(bool),
                    np.packbits(occ.reshape(len(occ), 1, -1), axis=2), a.device)
    metrics = {
        "ckpt": str(a.ckpt), "step": ck["step"], "n": a.n, "tau": a.tau,
        "sampler": "latent flow" if (model.prior is not None and not a.no_prior) else "N(0, tau^2)",
        "novelty_nn_iou": {"mean": float(nov.mean()), "p50": float(np.median(nov)), "p90": float(np.percentile(nov, 90)),
                           "max": float(nov.max()), "share_ge_0.85": float((nov >= 0.85).mean()),
                           "share_ge_0.8": float((nov >= 0.8).mean())},
        "real_vs_real_nn_iou": {"mean": float(base.mean()), "p50": float(np.median(base)),
                                "p90": float(np.percentile(base, 90))},
        "plausibility": {"one_component": float(np.mean([p["n_components"] == 1 for p in plaus])),
                         "main_frac_mean": float(np.mean([p["main_frac"] for p in plaus])),
                         "main_frac_min": float(np.min([p["main_frac"] for p in plaus])),
                         "cavity_frac_mean": float(np.mean([p["cavity_frac"] for p in plaus])),
                         "empty": int(sum(p["solid"] == 0 for p in plaus))},
        "diversity": {"pairwise_iou_mean": float(np.nanmean(pw)), "pairwise_iou_p99": float(np.nanpercentile(pw, 99)),
                      "nn_within_set_mean": float(np.nanmax(pw, 1).mean())},
        "coverage_train_within_0.6": float((cov >= 0.6).mean()),
    }
    (a.out / "metrics.json").write_text(json.dumps(metrics, indent=1))
    print(json.dumps(metrics, indent=1))
    k = min(48, a.n)
    ims = [R.render_voxels(occ[i], 128) for i in range(k)]
    labs = [f"{D.STYLES[style[i]][:4]} {D.SIZE_BINS[size_bin[i]][:4]} g{grounded[i]} nn{nov[i]:.2f}" for i in range(k)]
    R.sheet(ims, labs, cols=8).save(a.out / "samples.png")
    # side by side: generated | nearest scan
    pairs = []
    plabs = []
    for i in range(16):
        pairs += [R.render_voxels(occ[i], 128), R.render_voxels(ref_occ(refs, near[i], near_o[i]), 128,
                                                                 color=(120, 135, 160))]
        plabs += [f"gen nn{nov[i]:.2f}", f"scan {assets[near[i]][:18]}"]
    R.sheet(pairs, plabs, cols=8).save(a.out / "nearest.png")


if __name__ == "__main__":
    main()
