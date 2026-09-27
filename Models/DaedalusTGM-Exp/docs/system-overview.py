"""Renders system-overview.png (DaedalusTGM-Exp v3). Run from docs/: ../.venv/bin/python system-overview.py"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Patch

OUT = Path(__file__).with_name("system-overview.png")
LEARNED = "#fff1dc"   # trained model stages
MIXED = "#f3ecff"     # stage chains that include a learned piece
SERVICE = "#fffbe6"
W, H = 18.0, 34.6

fig, ax = plt.subplots(figsize=(W, H))
ax.set_xlim(0, W)
ax.set_ylim(0, H)
ax.axis("off")


def group(x, y, w, h, title, fc, ec):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.25", fc=fc, ec=ec, lw=2))
    ax.text(x + 0.3, y + h - 0.3, title, fontsize=18, fontweight="bold", va="top", color=ec)


def box(cx, cy, title, detail="", w=5.6, h=1.1, fc="white"):
    ax.add_patch(FancyBboxPatch((cx - w / 2, cy - h / 2), w, h, boxstyle="round,pad=0.02,rounding_size=0.12",
                                fc=fc, ec="#333", lw=1.6))
    if detail:
        n = detail.count("\n") + 1
        top = cy + (0.28 + 0.08 + n * 0.245) / 2
        ax.text(cx, top - 0.14, title, ha="center", va="center", fontsize=14.5, fontweight="bold")
        ax.text(cx, top - 0.36, detail, ha="center", va="top", fontsize=12.5, color="#333", linespacing=1.4)
    else:
        ax.text(cx, cy, title, ha="center", va="center", fontsize=14.5, fontweight="bold")
    return dict(cx=cx, cy=cy, w=w, h=h, l=cx - w / 2, r=cx + w / 2, t=cy + h / 2, b=cy - h / 2)


def arrow(p0, p1, label=None, dashed=False, color="#333", conn="arc3,rad=0", loff=(0.15, 0.0), ha="left"):
    ax.annotate("", xy=p1, xytext=p0, arrowprops=dict(arrowstyle="-|>", lw=1.8 if dashed else 2.1, color=color,
                mutation_scale=22, ls="--" if dashed else "-", shrinkA=1, shrinkB=1, connectionstyle=conn))
    if label:
        mx, my = (p0[0] + p1[0]) / 2 + loff[0], (p0[1] + p1[1]) / 2 + loff[1]
        ax.text(mx, my, label, fontsize=12, va="center", ha=ha, family="monospace",
                bbox=dict(fc="#eeeeee", ec="none", pad=3))


def path_arrow(points, color="#333", dashed=False, label=None, label_at=0, loff=(0.12, 0.0)):
    """Poly-line through `points`, arrowhead on the last segment."""
    xs, ys = zip(*points)
    ax.plot(xs[:-1], ys[:-1], color=color, lw=2.1 if not dashed else 1.8, ls="--" if dashed else "-",
            solid_capstyle="round")
    arrow(points[-2], points[-1], color=color, dashed=dashed)
    if label:
        (x0, y0), (x1, y1) = points[label_at], points[label_at + 1]
        ax.text((x0 + x1) / 2 + loff[0], (y0 + y1) / 2 + loff[1], label, fontsize=11.5, family="monospace",
                va="center", ha="left", bbox=dict(fc="#eeeeee", ec="none", pad=2))


ax.text(W / 2, 34.15, "DaedalusTGM-Exp — system overview (v3)", ha="center", va="center", fontsize=25,
        fontweight="bold")

# ---------------------------------------------------------------- game (Java)
group(0.4, 26.9, 17.2, 6.75, "Stonebreak (Java)", "#fbeefe", "#a23fb0")
sl = box(4.2, 32.55, "ServerLevel.createAndLoad", w=5.6, h=0.85)
tg = box(12.8, 32.55, "TerrainGenerationSystem", w=5.6, h=0.85)
pm = box(4.2, 30.9, "TerrainServiceProcessManager", "backend=slm → DaedalusTGM-Exp · checkpoints/v3", w=6.8, h=1.15)
dc = box(13.2, 30.9, "DiffusionTileCache + DiffusionTerrainClient", "tile protocol v3: 6 int16 planes → TerrainTile",
         w=7.6, h=1.15)
ts = box(4.6, 28.2, "TerrainScale + WorldConfiguration", "60 m blocks · 256 tall, sea y 64\ncurve 48/16/24/38 m/block",
         w=5.6, h=1.5)
hm = box(10.7, 28.2, "HeightMapGenerator → chunk fill", "river floor/roof: undercuts, overhangs\nflow octants: river water markers",
         w=5.6, h=1.5)
arrow((sl["r"], sl["cy"]), (tg["l"], tg["cy"]))
arrow((tg["l"] + 0.8, tg["b"]), (pm["cx"] + 1.5, pm["t"]))
arrow((tg["cx"] + 0.4, tg["b"]), (dc["cx"], dc["t"]))
arrow((ts["cx"], ts["t"]), (ts["cx"], pm["b"]), "TERRAIN_BRIDGE_* env", loff=(0.2, 0.0))
arrow((dc["l"] + 1.6, dc["b"]), (hm["cx"] + 0.8, hm["t"]))

# ---------------------------------------------------------------- services
br = box(12.9, 25.0, "terrain-bridge (FastAPI) · protocol v3",
         "HeightCurve metres → blocks · keeps water under overhangs\n"
         "river planes passed through · cache keyed on slm:<model dir> + curve",
         w=9.4, h=1.75, fc=SERVICE)
srv = box(12.9, 22.0, "DaedalusTGM-Exp model server (Flask, cuda:1)",
          "terrain_slm.serve.upstream_api · runs the generator and the block stages\n"
          "returns heights + water (metres) and river floor/roof/flow (blocks)",
          w=9.4, h=1.75, fc=SERVICE)
arrow((16.3, dc["b"]), (16.3, br["t"]))
ax.text(16.15, 26.35, "POST /generate_heightmap", fontsize=12, va="center", ha="right", family="monospace",
        bbox=dict(fc="#eeeeee", ec="none", pad=3))
arrow((br["cx"], br["b"]), (br["cx"], srv["t"]), "GET /terrain?…&water=1&river3d=1", loff=(-0.15, 0.0), ha="right")
for tgt in (br, srv):
    arrow((1.1, pm["b"]), (tgt["l"], tgt["cy"]), dashed=True, color="#777", conn="angle,angleA=-90,angleB=180,rad=0")
ax.text(1.25, 26.2, "launches\n(same env)", fontsize=13, color="#555", style="italic", va="center")

# ---------------------------------------------------------------- world generator
group(0.4, 0.4, 17.2, 20.0, "DaedalusTGM-Exp WorldGenerator — per request", "#e8fbf6", "#1b8f73")
X, BW = 5.0, 8.2
controls = box(X, 18.9, "Procedural controls v2", "continents · mountain ranges (spines, spurs, foothills) · wildness · climate",
               w=BW + 0.4, h=1.1)
relief = box(X, 17.35, "Relief sampler  (0.43M, flow matching)", "240 m cells · 448-cell windows → sampled valley networks",
             w=BW, h=1.1, fc=LEARNED)
planner = box(X, 15.8, "Planner R1  (1.56M ViT)", "coarse height (its descriptors and drainage are superseded)",
              w=BW, h=1.1, fc=LEARNED)
synth = box(X, 13.1, "Synth", "descriptor-driven hashed noise at 30 m pixels", w=BW, h=1.1)
refiner = box(X, 11.55, "Refiner  (0.34M, flow matching)", "SDEdit t=0.6 · 8 steps · 384 px regions", w=BW, h=1.1, fc=LEARNED)
cap = box(X, 10.0, "Summit soft cap", "above 4300 m → approaches 5150 m (y ≈ 254)", w=BW, h=1.1)
river = box(X, 7.95, "River pipeline (terrain_slm.river) · 30 m px",
            "centrelines (hysteresis) → channel 3–16 blocks → level\n"
            "→ learned banks (0.24M, only where rivers) → guard\n→ U-bed → no-spill", w=BW, h=2.1, fc=MIXED)
blocks = box(X, 5.2, "Block stages · 2×2 → 60 m blocks",
             "quantise → flow octants → flatten across → contain\n→ undercuts → overhangs → 6 tile planes", w=BW, h=1.75, fc=MIXED)
chain = [controls, relief, planner, synth, refiner, cap, river, blocks]
for a, b in zip(chain, chain[1:]):
    arrow((X, a["b"]), (X, b["t"]))
path_arrow([(srv["cx"], srv["b"]), (srv["cx"], controls["cy"]), (controls["r"], controls["cy"])])

hydro = box(14.3, 15.8, "Hydrology sidecar  (0.40M)", "drainage · rivers · D8 directions\nfrom the final coarse height",
            w=5.4, h=1.5, fc=LEARNED)
dg = box(13.95, 13.25, "Descriptor sampler: MaskGIT  (1.55M)", "8 descriptors per cell, sampled\ncheckerboard windows, no averaging",
         w=6.5, h=1.5, fc=LEARNED)
arrow((planner["r"], planner["cy"]), (hydro["l"], hydro["cy"]), "height", loff=(-0.35, 0.28))
path_arrow([(hydro["r"] - 0.4, hydro["b"]), (hydro["r"] - 0.4, river["cy"]), (river["r"], river["cy"])],
           label="drainage + D8", label_at=1, loff=(-1.3, 0.25))
arrow((planner["r"], planner["b"] + 0.2), (dg["l"], dg["t"] - 0.35), "planner inputs", loff=(-0.4, 0.1))
arrow((dg["l"], synth["cy"]), (synth["r"], synth["cy"]))

ck = box(14.4, 5.0, "checkpoints/v3  (tracked, ~18 MB)",
         "planner 1.56M · refiner 0.34M · relief 0.43M\nhydro 0.40M · bank 0.24M · descgit 1.55M",
         w=5.6, h=1.75, fc=LEARNED)
tr = box(14.4, 2.35, "Offline training", "7 GLO-30 regions + water masks\ntrain_{planner,refiner,relief,hydro,banks,descgit}",
         w=5.6, h=1.6)
arrow((tr["cx"], tr["t"]), (ck["cx"], ck["b"]))

ax.legend(handles=[Patch(fc=LEARNED, ec="#333", label="learned model"),
                   Patch(fc=MIXED, ec="#333", label="stage chain with a learned piece"),
                   Patch(fc="white", ec="#333", label="procedural / deterministic")],
          loc="lower left", bbox_to_anchor=(0.03, 0.02), fontsize=13, frameon=False)

plt.savefig(OUT, dpi=100, bbox_inches="tight", facecolor="white")
print(OUT)
