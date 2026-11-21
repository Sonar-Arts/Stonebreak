"""Distil DaedalusTGM-Exp-2's surface channels into rule tables for Standard (noise) worlds (Standard shape 2).

    python -m terrain_slm.eval.distill_channels /mnt/extrastorage/terrain-slm-data/distill/exp2-r3 [--write]

The EXP-2 tiles carry per-block surface channels (TGMPipe v3: vegetation from the vegetation node - ETH canopy height +
ESA WorldCover trained; rock exposure / scree / roughness from agent L's lidar node). Standard worlds have no network, so
this measures what those channels do against what a Standard world DOES know per column - its game biome, its height
above the sea (1:4 reference curve) and, for the rock channels, its slope - and writes the conditional means (and, for
vegetation, the spread) as compact tables into the native generator (`stonebreak-game/native/src/standard_channels.inc`):

  vegetation (slots 0..7)  per (biome, height band): mean + std. Measured share of variance explained: biome + height
                           0.33..0.78 (cover 0.65, clearing 0.67, moss 0.78); slope adds <= 0.1, so the vegetation tables
                           are pointwise (FastLOD's strided samples compute them without neighbours).
  rock (slots 8, 9, 12)    per (biome, height band, slope class): mean. Slope matters here (rock exposure 0.31 -> 0.58,
                           scree 0.24 -> 0.68, roughness 0.34 -> 0.67).

The game spreads the vegetation values around the mean with smooth patch noise of the measured std (shape.cpp
standardChannels). Cells with fewer than MIN_N samples back off to the biome's own mean; biomes the sample lacks
(beach, ocean, ...) carry no data (255) and keep the biome rules.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from terrain_slm.eval.distill_stats import _DAEDALUS_BIOME, SEA
from terrain_slm.paths import MODELS_DIR

# SBW biome order (worldgen.h enum sbw_biome)
SBW_BIOMES = ["PLAINS", "DESERT", "RED_SAND_DESERT", "SNOWY_PLAINS", "TUNDRA", "TAIGA", "STONY_PEAKS", "BEACH",
              "ICE_FIELDS", "BADLANDS", "MEADOW", "OCEAN", "SWAMP"]
VEG = ["canopy_cover", "canopy_height", "clearing", "ground_grass", "ground_shrub", "ground_moss", "ground_bare",
       "ground_wetland"]
ROCK = ["rock_exposure", "scree", "surface_roughness"]
H_EDGES = (8, 24, 60, 120)      # blocks above the sea (1:4): 5 bands
S_EDGES = (1, 2, 3, 5)          # slope classes: 0, 1, 2, 3-4, 5+ (max step to a face neighbour)
MIN_N = 400
FALLBACK = {"BADLANDS": "RED_SAND_DESERT"}
OUT = MODELS_DIR.parent / "stonebreak-game/native/src/standard_channels.inc"


def gather(d: Path):
    cols = {k: [] for k in VEG + ROCK}
    B, H, S = [], [], []
    for f in sorted(d.glob("*_l1.npz")):
        z = np.load(f)
        if "surf_canopy_cover" not in z:
            continue
        h = z["height"].astype(np.int32)
        f64 = h.astype(np.float64)
        sl = np.zeros_like(f64)
        for ax in (0, 1):
            for dd in (1, -1):
                sl = np.maximum(sl, np.abs(np.roll(f64, dd, ax) - f64))
        ok = z["surf_canopy_cover"] != 255
        ok[0, :] = ok[-1, :] = ok[:, 0] = ok[:, -1] = False
        for k in cols:
            cols[k].append(z["surf_" + k][ok])
        names = np.vectorize(lambda i: _DAEDALUS_BIOME.get(int(i), "PLAINS"), otypes=[object])(z["biome"][ok])
        B.append(np.array([SBW_BIOMES.index(n) for n in names], np.int32))
        H.append(h[ok])
        S.append(sl[ok])
    return ({k: np.concatenate(v).astype(np.float64) for k, v in cols.items()}, np.concatenate(B),
            np.concatenate(H), np.concatenate(S))


def tables(cols, B, H, S):
    hb = np.digitize(H - SEA, H_EDGES)
    sb = np.digitize(S, S_EDGES)
    nb, nh, ns = len(SBW_BIOMES), len(H_EDGES) + 1, len(S_EDGES) + 1
    veg_mean = np.full((nb, nh, len(VEG)), 255, np.int32)
    veg_std = np.zeros((nb, nh, len(VEG)), np.int32)
    rock = np.full((nb, nh, ns, len(ROCK)), 255, np.int32)
    counts = {}
    for b in range(nb):
        mb = B == b
        counts[SBW_BIOMES[b]] = int(mb.sum())
        if mb.sum() < MIN_N:
            continue
        for h in range(nh):
            m = mb & (hb == h)
            src = m if m.sum() >= MIN_N else mb
            for k, name in enumerate(VEG):
                v = cols[name][src]
                veg_mean[b, h, k] = int(round(min(254.0, v.mean())))
                veg_std[b, h, k] = int(round(min(127.0, v.std())))
            for s_ in range(ns):
                m2 = m & (sb == s_)
                src2 = m2 if m2.sum() >= MIN_N else (mb & (sb == s_) if (mb & (sb == s_)).sum() >= MIN_N else src)
                for k, name in enumerate(ROCK):
                    rock[b, h, s_, k] = int(round(min(254.0, cols[name][src2].mean())))
    for b, src_b in FALLBACK.items():           # biomes EXP-2 never draws (badlands): a close relative's tables
        bi, si = SBW_BIOMES.index(b), SBW_BIOMES.index(src_b)
        if counts[b] < MIN_N and counts[src_b] >= MIN_N:
            veg_mean[bi], veg_std[bi], rock[bi] = veg_mean[si], veg_std[si], rock[si]
    return veg_mean, veg_std, rock, counts


def explained(cols, B, H, S, veg_mean, rock):
    hb = np.digitize(H - SEA, H_EDGES)
    sb = np.digitize(S, S_EDGES)
    out = {}
    for k, name in enumerate(VEG):
        v = cols[name]
        out[name] = 1 - ((v - veg_mean[B, hb, k]) ** 2).mean() / v.var()
    for k, name in enumerate(ROCK):
        v = cols[name]
        out[name] = 1 - ((v - rock[B, hb, sb, k]) ** 2).mean() / v.var()
    return out


def render(veg_mean, veg_std, rock, counts, n, src: str) -> str:
    def arr(a):
        return "{" + ", ".join(arr(x) if isinstance(x, np.ndarray) and x.ndim else str(int(x)) for x in a) + "}"
    lines = [
        "/* GENERATED by terrain_slm.eval.distill_channels from " + src + " - do not edit.",
        " * Standard shape 2 surface channels, distilled from DaedalusTGM-Exp-2 (exp2-r3) tiles: " + f"{n} land columns.",
        " * Index: [sbw_biome][height band][...]; height bands = blocks above the sea (1:4 reference curve) split at "
        + ", ".join(map(str, H_EDGES)) + "; slope classes = max step to a face neighbour split at "
        + ", ".join(map(str, S_EDGES)) + ".",
        " * 255 = no data (the biome rules). Land columns per biome: "
        + ", ".join(f"{k} {v}" for k, v in counts.items()) + ". */",
        f"constexpr int SC_HBANDS = {len(H_EDGES) + 1}, SC_SCLASSES = {len(S_EDGES) + 1};",
        "constexpr int SC_H_EDGES[" + str(len(H_EDGES)) + "] = {" + ", ".join(map(str, H_EDGES)) + "};",
        "constexpr int SC_S_EDGES[" + str(len(S_EDGES)) + "] = {" + ", ".join(map(str, S_EDGES)) + "};",
        "/* slots 0..7 (SBW_SURF_CANOPY_COVER .. GROUND_WETLAND): mean and std, bytes */",
        f"constexpr uint8_t SC_VEG_MEAN[SBW_BIOME_COUNT][SC_HBANDS][8] = {arr(veg_mean)};",
        f"constexpr uint8_t SC_VEG_STD[SBW_BIOME_COUNT][SC_HBANDS][8] = {arr(veg_std)};",
        "/* rock exposure, scree, roughness (SBW_SURF_ROCK_EXPOSURE, SCREE, ROUGHNESS): mean bytes */",
        f"constexpr uint8_t SC_ROCK[SBW_BIOME_COUNT][SC_HBANDS][SC_SCLASSES][3] = {arr(rock)};",
    ]
    return "\n".join(lines) + "\n"


def main(argv=None) -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("sample")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args(argv)
    cols, B, H, S = gather(Path(args.sample))
    veg_mean, veg_std, rock, counts = tables(cols, B, H, S)
    print(f"{len(B)} land columns; per biome: {counts}")
    for k, v in explained(cols, B, H, S, veg_mean, rock).items():
        print(f"  {k:18s} R2 {v:.3f}")
    text = render(veg_mean, veg_std, rock, counts, len(B), Path(args.sample).name)
    if args.write:
        Path(args.out).write_text(text)
        print("wrote", args.out)
    else:
        print(text[:2000])


if __name__ == "__main__":
    main()
