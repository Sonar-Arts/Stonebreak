"""Renders system-overview.png (DaedalusTGM-Exp v2). Run from docs/: ../.venv/bin/python system-overview.py"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Patch

OUT = Path(__file__).with_name("system-overview.png")
LEARNED = "#fff1dc"   # trained model stages
SERVICE = "#fffbe6"
W, H = 16.0, 24.0

fig, ax = plt.subplots(figsize=(W, H + 0.8))
ax.set_xlim(0, W)
ax.set_ylim(0, H + 0.8)
ax.axis("off")


def group(x, y, w, h, title, fc, ec):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.25", fc=fc, ec=ec, lw=2))
    ax.text(x + 0.3, y + h - 0.3, title, fontsize=18, fontweight="bold", va="top", color=ec)


def box(cx, cy, title, detail="", w=5.6, h=1.1, fc="white"):
    ax.add_patch(FancyBboxPatch((cx - w / 2, cy - h / 2), w, h, boxstyle="round,pad=0.02,rounding_size=0.12",
                                fc=fc, ec="#333", lw=1.6))
    if detail:
        # Title and detail block centred together: title line 0.28, gap 0.08, detail lines 0.245 each.
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


ax.text(W / 2, 24.35, "DaedalusTGM-Exp — system overview (v2)", ha="center", va="center", fontsize=24, fontweight="bold")

# ---------------------------------------------------------------- game (Java)
group(0.4, 18.3, 15.2, 5.3, "Stonebreak (Java)", "#fbeefe", "#a23fb0")
sl = box(3.6, 22.55, "ServerLevel.createAndLoad", w=5.2, h=0.85)
tg = box(10.6, 22.55, "TerrainGenerationSystem", w=5.2, h=0.85)
pm = box(3.9, 20.85, "TerrainServiceProcessManager", "backend=slm → DaedalusTGM-Exp · checkpoints/v2", w=6.6, h=1.15)
dc = box(11.6, 20.85, "DiffusionTileCache", "TerrainTileSource · 256² block tiles", w=5.8, h=1.15)
ts = box(4.95, 19.15, "TerrainScale + WorldConfiguration",
         "60 m blocks · 256 tall, sea y 64 · curve 48/16/24/38 m/block", w=7.0, h=1.15)
arrow((sl["r"], sl["cy"]), (tg["l"], tg["cy"]))
arrow((tg["l"] + 1.0, tg["b"]), (pm["cx"] + 1.5, pm["t"]))
arrow((tg["cx"] + 1.0, tg["b"]), (dc["cx"], dc["t"]))
arrow((ts["cx"], ts["t"]), (ts["cx"], pm["b"]), "TERRAIN_BRIDGE_* env", loff=(0.2, 0.0))

# ---------------------------------------------------------------- services
br = box(11.3, 16.5, "terrain-bridge (FastAPI)",
         "HeightCurve metres → blocks · UpstreamWater\ntile cache keyed on slm:<model dir> + curve rates",
         w=7.8, h=1.75, fc=SERVICE)
srv = box(11.3, 13.35, "DaedalusTGM-Exp model server (Flask, cuda:1)",
          "terrain_slm.serve.upstream_api · 2×2 downscale → 60 m blocks\nblock-level water settling · vendored biome classifier",
          w=7.8, h=1.75, fc=SERVICE)
arrow((dc["cx"], dc["b"]), (dc["cx"], br["t"]), "POST /generate_heightmap")
arrow((br["cx"], br["b"]), (br["cx"], srv["t"]), "GET /terrain?…&downscale=2&water=1")
for tgt in (br, srv):
    arrow((1.1, pm["b"]), (tgt["l"], tgt["cy"]), dashed=True, color="#777",
          conn="angle,angleA=-90,angleB=180,rad=0")
ax.text(1.25, 17.4, "launches\n(same env)", fontsize=13, color="#555", style="italic", va="center")

# ---------------------------------------------------------------- world generator
group(0.4, 0.3, 15.2, 11.9, "DaedalusTGM-Exp WorldGenerator — per request", "#e8fbf6", "#1b8f73")
X, BW, BH, STEP = 5.3, 8.9, 1.15, 1.47
stages = [
    ("Procedural controls v2", "continents · mountain ranges (spines, spurs, foothills) · wildness · climate", "white"),
    ("Relief sampler  (0.43M UNet, flow matching)", "240 m cells · 448-cell windows · 16 Euler steps → valley networks", LEARNED),
    ("Planner R1  (1.56M ViT)", "64-cell windows, stride 32 → coarse height · 8 descriptors · river · log flow", LEARNED),
    ("Synth", "descriptor-driven hashed noise at 30 m pixels · region-exact", "white"),
    ("Refiner  (0.34M UNet, flow matching)", "SDEdit t=0.6 · 8 steps · 384 px regions, cross-faded", LEARNED),
    ("Summit soft cap", "above 4300 m → approaches 5150 m (y ≈ 254), never flat-tops", "white"),
    ("River pass", "flow ridge lines (threshold 4.5) · carve · levees · no-spill", "white"),
]
top = 10.85
boxes = []
for k, (title, detail, fc) in enumerate(stages):
    b = box(X, top - k * STEP, title, detail, w=BW, h=BH, fc=fc)
    if boxes:
        arrow((X, boxes[-1]["b"]), (X, b["t"]))
    boxes.append(b)
arrow((srv["cx"], srv["b"]), (boxes[0]["r"], boxes[0]["cy"]), conn="angle,angleA=-90,angleB=0,rad=0")

# model files + training
ck = box(13.0, 6.45, "checkpoints/v2", "relief.pt · 425k\nplanner.pt · 1.56M\nrefiner.pt · 337k",
         w=4.0, h=2.2, fc=LEARNED)
for i in (1, 2, 4):
    arrow((ck["l"], ck["cy"]), (boxes[i]["r"], boxes[i]["cy"]), dashed=True, color="#b07a2a")
tr = box(13.0, 2.6, "Offline training", "7 Copernicus GLO-30 regions\ntrain_relief · train_planner\ntrain_refiner",
         w=4.0, h=2.2)
arrow((tr["cx"], tr["t"]), (ck["cx"], ck["b"]))

ax.legend(handles=[Patch(fc=LEARNED, ec="#333", label="learned model"),
                   Patch(fc="white", ec="#333", label="procedural / deterministic")],
          loc="lower left", bbox_to_anchor=(0.045, 0.02), fontsize=13, frameon=False)

plt.savefig(OUT, dpi=110, bbox_inches="tight", facecolor="white")
print(OUT)
