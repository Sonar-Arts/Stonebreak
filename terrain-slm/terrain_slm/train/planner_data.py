"""Planner training data: per-cell fields on the GPU, random crops, augmentation and
control degradation (so hand-painted or procedural controls are in-distribution)."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P

RIVER_MIN_UPSLOPE_CELLS = P.RIVER_MIN_UPSLOPE_CELLS
# D8 offsets (drow, dcol) in class order; class 8 = terminal. Must match data/build.py.
D8_OFFSETS = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))


def _d8_permutation(flip_rows: bool, flip_cols: bool, transpose: bool) -> torch.Tensor:
    """Class remap for a dihedral transform applied as: transpose, then flips."""
    perm = []
    for dr, dc in D8_OFFSETS:
        if transpose:
            dr, dc = dc, dr
        if flip_rows:
            dr = -dr
        if flip_cols:
            dc = -dc
        perm.append(D8_OFFSETS.index((dr, dc)))
    return torch.tensor(perm + [8])


@dataclass
class Norms:
    desc_mean: list[float]
    desc_std: list[float]

    def to_dict(self):
        return {"desc_mean": self.desc_mean, "desc_std": self.desc_std}

    @staticmethod
    def from_dict(d):
        return Norms(d["desc_mean"], d["desc_std"])


def built_regions(data_dir: Path = Path("data")) -> list[Path]:
    """Every region directory with a finished build (cells.npz + meta.json)."""
    return sorted(p.parent for p in data_dir.glob("*/cells.npz") if (p.parent / "meta.json").exists())


class _Region:
    """One training region's per-cell fields on the device, plus its train/val split."""

    def __init__(self, index: int, root: Path, device: str, crop: int):
        meta = json.loads((root / "meta.json").read_text())
        c = np.load(root / "cells.npz")
        self.index, self.name = index, meta.get("region", root.name)
        t = lambda a: torch.from_numpy(np.ascontiguousarray(a)).float().to(device)
        self.coarse = t(c["coarse"])
        self.desc = t(c["desc"])
        self.climate = torch.stack([t(c[k]) for k in P.CLIMATE_NAMES])
        # Real D8 accumulation lives on 1-cell-wide lines the planner cannot localise
        # exactly; an L1 regression then predicts a low, blurred ridge that never reaches
        # the river threshold. Train on the 3x3-dilated field instead (true magnitude,
        # 3-cell ridge); the river pass's non-maximum suppression thins it back.
        self.acc = F.max_pool2d(t(c["acc"])[None, None], 3, stride=1, padding=1)[0, 0]
        self.river = (self.acc >= RIVER_MIN_UPSLOPE_CELLS).float()
        self.d8 = torch.from_numpy(c["d8"].astype(np.int64)).to(device)
        hc, wc = self.coarse.shape

        # Validation tiles, in cells.
        tile_h = meta["tile_px"][0] // D.CELL_PX
        tile_w = meta["tile_px"][1] // D.CELL_PX
        lat1, lon0 = meta["lat"][1], meta["lon"][0]
        self.val_boxes = [((lat1 - 1 - la) * tile_h, (lo - lon0) * tile_w, tile_h, tile_w) for la, lo in meta["val_tiles"]]
        val = torch.zeros(hc, wc, dtype=torch.bool)
        for r, cc, h, w in self.val_boxes:
            val[r : r + h, cc : cc + w] = True
        # Top-left corners whose crop avoids every validation cell.
        integral = F.pad(val.float().cumsum(0).cumsum(1), (1, 0, 1, 0))
        s = crop
        hits = integral[s:, s:] - integral[:-s, s:] - integral[s:, :-s] + integral[:-s, :-s]
        self.train_corners = torch.nonzero(hits[: hc - s + 1, : wc - s + 1] == 0)
        self.val_corners = [(index, r + dr, cc + dc) for r, cc, h, w in self.val_boxes
                            for dr in range(0, h - s + 1, s) for dc in range(0, w - s + 1, s)]
        self.train_mask = ~val.to(device)


class PlannerData:
    """Training crops drawn from one or more regions (data/<region>/). Regions are sampled
    uniformly, not by area, so a big region cannot drown out a small, distinctive one."""

    def __init__(self, roots, device: str, crop: int = 64, seed: int = 0):
        roots = [Path(roots)] if isinstance(roots, (str, Path)) else [Path(r) for r in roots]
        self.device = device
        self.crop = crop
        self.gen = torch.Generator(device="cpu").manual_seed(seed)
        self.regions = [_Region(i, r, device, crop) for i, r in enumerate(roots)]
        self.val_corners = [vc for reg in self.regions for vc in reg.val_corners]
        self.train_count = sum(len(reg.train_corners) for reg in self.regions)

        # Descriptor normalisation over every region's training cells (subsampled).
        parts = []
        for reg in self.regions:
            d = reg.desc[:, reg.train_mask]
            parts.append(d[:, torch.randperm(d.shape[1], device=d.device)[:2_000_000]])
        d = torch.cat(parts, dim=1)
        d[5] = d[5].clamp(-3, 3)
        self.norms = Norms(d.mean(dim=1).tolist(), d.std(dim=1).clamp_min(1e-3).tolist())

    @property
    def train_corners(self):  # kept for callers that report counts
        return range(self.train_count)

    def sample_corners(self, batch: int) -> list[tuple[int, int, int]]:
        out = []
        for _ in range(batch):
            reg = self.regions[int(torch.randint(0, len(self.regions), (1,), generator=self.gen))]
            k = int(torch.randint(0, len(reg.train_corners), (1,), generator=self.gen))
            r, c = reg.train_corners[k].tolist()
            out.append((reg.index, r, c))
        return out

    # ------------------------------------------------------------------ batches
    def _crop(self, corners, augment: bool):
        s = self.crop
        fields = []
        for ri, r, c in corners:
            reg = self.regions[ri]
            f = torch.cat([
                reg.coarse[None, r : r + s, c : c + s],
                reg.desc[:, r : r + s, c : c + s],
                reg.climate[:, r : r + s, c : c + s],
                reg.river[None, r : r + s, c : c + s],
                reg.d8[None, r : r + s, c : c + s].float(),
                torch.log1p(reg.acc[None, r : r + s, c : c + s]) / P.LOGACC_SCALE,
            ])
            if augment:
                f = self._dihedral(f)
            fields.append(f)
        return torch.stack(fields)  # (B, 16, s, s)

    def _dihedral(self, f: torch.Tensor, ops: tuple[bool, bool, bool] | None = None) -> torch.Tensor:
        tr, fr, fc = ops if ops is not None else (torch.randint(0, 2, (3,), generator=self.gen) == 1).tolist()
        f = f.clone()
        if tr:
            f = f.transpose(-1, -2)
        if fr:
            f = f.flip(-2)
        if fc:
            f = f.flip(-1)
        # Anisotropy (desc 6, 7 = field 7, 8): transpose negates c; each flip negates s.
        if tr:
            f[7] = -f[7]
        if fr ^ fc:
            f[8] = -f[8]
        perm = _d8_permutation(fr, fc, tr).to(f.device)
        f[14] = perm[f[14].long()].float()
        return f

    def _degrade_trend(self, coarse: torch.Tensor) -> torch.Tensor:
        """Blurred, quantised, jittered coarse height: what a painted/procedural trend looks like."""
        b = coarse.shape[0]
        out = torch.empty_like(coarse)
        for i in range(b):
            sigma = float(torch.empty(1).uniform_(1.5, 5.0, generator=self.gen))
            t = D.blur(coarse[i : i + 1], sigma)
            step = float([0.0, 50.0, 100.0, 250.0][int(torch.randint(0, 4, (1,), generator=self.gen))])
            if step > 0:
                t = torch.round(t / step) * step
            jitter = D.blur(torch.randn(t.shape, generator=self.gen).to(t.device), 6.0)
            jitter = jitter / jitter.std().clamp_min(1e-6) * float(torch.empty(1).uniform_(0, 80, generator=self.gen))
            out[i : i + 1] = t + jitter
        return out

    def _wildness(self, f: torch.Tensor, train: bool) -> torch.Tensor:
        """Heavily blurred band-3 log amplitude: the coarse roughness a painter would draw."""
        la = f[:, 1 + P.WILD_BAND : 2 + P.WILD_BAND]
        sigma = float(torch.empty(1).uniform_(3.0, 8.0, generator=self.gen)) if train else 5.0
        w = D.blur(la, sigma)
        if train:
            w = w + 0.15 * torch.randn(w.shape[0], 1, 1, 1, generator=self.gen).to(w.device)
        return (w - P.WILD_NORM[0]) / P.WILD_NORM[1]

    def make_inputs(self, f: torch.Tensor, p_trend=0.9, p_climate=0.9, p_river=0.5, p_wild=0.8, train=True):
        b, _, s, _ = f.shape
        x = torch.zeros(b, P.N_IN, s, s, device=f.device)
        coarse = f[:, 0:1]
        trend = self._degrade_trend(coarse) if train else D.blur(coarse, 3.0)
        has = lambda p: (torch.rand(b, 1, 1, 1, generator=self.gen) < p).float().to(f.device) if train else torch.ones(b, 1, 1, 1, device=f.device)
        ht, hcl, hr = has(p_trend), has(p_climate), has(p_river) * (1.0 if train else 0.0)
        hw = has(p_wild)
        x[:, P.IN_WILD : P.IN_WILD + 1] = hw * self._wildness(f, train)
        x[:, P.IN_HAS_WILD] = hw[:, 0]
        x[:, P.IN_TREND : P.IN_TREND + 1] = ht * trend / P.HEIGHT_SCALE_M
        for k, name in enumerate(P.CLIMATE_NAMES):
            x[:, P.IN_T0 + k : P.IN_T0 + k + 1] = hcl * P.climate_input(name, f[:, 9 + k : 10 + k])
        x[:, P.IN_RIVER : P.IN_RIVER + 1] = hr * f[:, 13:14]
        x[:, P.IN_HAS_TREND] = ht[:, 0]
        x[:, P.IN_HAS_CLIMATE] = hcl[:, 0]
        x[:, P.IN_HAS_RIVER] = hr[:, 0]
        return x

    def targets(self, f: torch.Tensor) -> dict:
        mean = torch.tensor(self.norms.desc_mean, device=f.device).view(1, -1, 1, 1)
        std = torch.tensor(self.norms.desc_std, device=f.device).view(1, -1, 1, 1)
        desc = f[:, 1:9].clone()
        desc[:, 5] = desc[:, 5].clamp(-3, 3)
        return {
            "height": f[:, 0:1] / P.HEIGHT_SCALE_M,
            "desc": (desc - mean) / std,
            "stat_weight": (torch.exp(f[:, 3:4]) > 0.3).float(),  # ridge/aniso only where band 2 has relief
            "river": f[:, 13:14],
            "logacc": f[:, 15:16],
        }

    def train_batch(self, batch: int):
        f = self._crop(self.sample_corners(batch), augment=True)
        return self.make_inputs(f), self.targets(f)

    def val_batches(self, batch: int):
        for i in range(0, len(self.val_corners), batch):
            f = self._crop(self.val_corners[i : i + batch], augment=False)
            yield self.make_inputs(f, train=False), self.targets(f)
