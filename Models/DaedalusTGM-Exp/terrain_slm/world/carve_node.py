"""Carve node (DaedalusTGM-Exp-2 plan §6), v1 = a rule over the built terrain, calibrated on real-data labels
(data/carve_labels.py); a learned node can replace it behind the same output.

The native carver spawns ravines on `hash % 450` anywhere, in a random direction, so they look the same everywhere.
This node tells it where the terrain is actually incised and which way, per block column, as one 16-bit code:

  bits 0-3   density   0..15  how many ravine sources per area (0 = none: plains, the sea); sea_stacks: cliff exposure
  bits 4-6   style     0..7   CARVE_STYLES: ravine, slot canyon (arid, deep and narrow), gorge (a river cutting
                              mountains), rift (wide, straight, deep at 2 km), karst (sinkholes), none,
                              sea_stacks (a cliffed coast: notches, sea caves, arches, stacks), towers (humid
                              tower karst: pillars between corridors and pits, Guilin / Zhangjiajie)
  bits 7-10  strike    0..15  along-axis direction theta of the incision, 16 steps over [0, pi), measured from
                              +j (world Z) toward +i (world X); the native carver's yaw (from +X toward +Z) is pi/2 - theta
  bits 11-15 depth     0..31  incision depth class, 32 m per step (0..~1 km)

Measured on the tile's own 60 m samples (heights in metres), with a margin, so it is request-independent like the
rest of the tile. Climate (aridity) and the style gates pick the style; glacial terrain gets fjords from the
landform instead, so its ravine density is cut. A gorge is a river sample incised at 600 m that ALSO lies >= GORGE_VD2000_M
deep at 2 km: on real data the 600 m test alone gave hill rivers (Ozarks, Scottish Highlands) more gorges than the
Karakoram, so the 2 km depth tells a river cutting mountains from a stream in hills.

Coast and tower styles (the native carver shapes them itself; ravines never spawn on them):
  sea_stacks  land within SEA_STACK_REACH_SAMPLES (300 m) of a STEEP shore: a land sample touching the sea whose
              ground rises SEA_CLIFF_M (70 m) within SEA_CLIFF_SAMPLES (120 m), i.e. > ~30 deg. Beaches and flat
              coasts never qualify. Density = the cliff's height (SEA_CLIFF_DENSITY_M per step, at least 1): how far
              the native carver lets the coast retreat into stacks. Overrides every other style (the coast wins).
  towers      where the karst rule fires AND the ground is humid (aridity >= TOWER_HUMID, wetter than any other
              style asks for) and rugged (max - min >= TOWER_RELIEF_M within TOWER_RELIEF_SAMPLES, true on peaks and
              pits alike; a valley-depth test would leave the tower tops to sinkholes): towers replace sinkhole karst.
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

from terrain_slm.data import carve_labels as CL
from terrain_slm.data.descriptors import blur

CARVE_STYLES = ("ravine", "slot_canyon", "gorge", "rift", "karst", "none", "sea_stacks", "towers")  # ids = SBW ABI
MARGIN_SAMPLES = 40          # closing at 2 km (33 px at 60 m, half = 16) + the strike blur (sigma 4 px, ~3 sigma)
# Rule constants, calibrated 2026-11-18 on real terrain against carve_labels (eval/carve_calibration.py: 49 regions,
# windows outside the val tiles; v1 values in its V1_CONSTANTS).
DENSITY_VD600_M = (30.0, 160.0)    # valley depth at 600 m from which ravines start / saturate (v1 25: erg dunes reach it)
SLOT_VD150_M = 12.0                # deep at 150 m already: narrow
ARID = 0.3                         # aridity below which canyons are slot canyons (v1 0.2 = the Colorado Plateau's median)
GORGE_VD2000_M = 250.0             # a gorge's river also runs >= 250 m deep at 2 km: mountains, not hill streams
RIFT_VD2000_M, RIFT_COHERENCE = 600.0, 0.9   # v1 300 / 0.6 named every big glacial trough a rift (13-26% of carving)
SEA_CLIFF_SAMPLES, SEA_CLIFF_M = 2, 70.0   # a shore is steep when land within 120 m of it reaches 70 m (~30 deg)
SEA_STACK_REACH_SAMPLES = 5                # sea_stacks on land within 300 m of a steep shore
SEA_CLIFF_DENSITY_M = 20.0                 # sea_stacks density: cliff height per step (70 m -> 4, 300 m -> 15)
TOWER_HUMID = 1.1                          # aridity (P / PET) from which karst grows towers (Aegean ~0.5 never)
TOWER_RELIEF_SAMPLES, TOWER_RELIEF_M = 5, 40.0    # ... on rugged ground: >= 40 m relief in a 660 m window


def pack(density, style, strike, depth) -> torch.Tensor:
    return (density.clamp(0, 15).to(torch.int32) | (style.clamp(0, 7).to(torch.int32) << 4)
            | (strike.clamp(0, 15).to(torch.int32) << 7) | (depth.clamp(0, 31).to(torch.int32) << 11))


def unpack(code: torch.Tensor) -> dict[str, torch.Tensor]:
    c = code.to(torch.int32) & 0xFFFF
    return {"density": c & 15, "style": (c >> 4) & 7, "strike": (c >> 7) & 15, "depth": (c >> 11) & 31}


def carve_codes(h60: torch.Tensor, aridity: torch.Tensor, river: torch.Tensor, gates: dict[str, torch.Tensor] | None,
                margin: int = MARGIN_SAMPLES) -> torch.Tensor:
    """Codes (int16 bit pattern as int32) for the 60 m samples of `h60` (H, W metres, INCLUDING `margin` samples each
    side), cropped to the core. `aridity`, `river` (0/1) and gates are (H, W) at the same samples."""
    z = h60[None, None].float()
    vd150 = CL.valley_depth(z, 150.0)
    vd600 = CL.valley_depth(z, 600.0)
    vd2000 = CL.valley_depth(z, 2000.0)
    c2, s2 = CL.strike(vd600)
    coh = torch.sqrt(c2 ** 2 + s2 ** 2)
    angle = 0.5 * torch.atan2(s2, c2)                                        # [-pi/2, pi/2): the axis
    strike = torch.round(((angle % math.pi) / math.pi) * 16).long() % 16
    v6 = vd600[0, 0]
    density = ((v6 - DENSITY_VD600_M[0]) / (DENSITY_VD600_M[1] - DENSITY_VD600_M[0]) * 15).clamp(0, 15)
    style = torch.zeros_like(v6, dtype=torch.long)                           # ravine
    style = torch.where((vd150[0, 0] > SLOT_VD150_M) & (aridity < ARID), torch.full_like(style, 1), style)
    gorge = (river > 0.5) & (v6 > DENSITY_VD600_M[0]) & (vd2000[0, 0] > GORGE_VD2000_M)
    style = torch.where(gorge, torch.full_like(style, 2), style)
    style = torch.where((vd2000[0, 0] > RIFT_VD2000_M) & (coh > RIFT_COHERENCE), torch.full_like(style, 3), style)
    pool = lambda x, r: F.max_pool2d(x, 2 * r + 1, stride=1, padding=r)
    if gates is not None:
        if "karst" in gates or "drowned_archipelago" in gates:
            # controls2's limestone provinces when present (inland Guilin-style karst too); the coastal archipelago
            # gate was a stand-in that also put towers on granite islands (Seto Inland, Visayas)
            karst = (gates["karst"] if "karst" in gates else gates["drowned_archipelago"]) > 0.5
            style = torch.where(karst, torch.full_like(style, 4), style)
            r = TOWER_RELIEF_SAMPLES
            relief = (pool(z, r) + pool(-z, r))[0, 0]
            towers = karst & (aridity >= TOWER_HUMID) & (relief >= TOWER_RELIEF_M)
            style = torch.where(towers, torch.full_like(style, 7), style)
        if "glacial_fjord" in gates:
            density = density * (1 - 0.8 * gates["glacial_fjord"])
    # Steep coasts (pointwise windows of a few samples, well inside the margin): stacks wherever a cliffed shore is
    # within reach. Sea samples keep their style but get density 0 below, so only land carries sea_stacks.
    sea = (h60 < 0.0).float()[None, None]
    shore = (pool(sea, 1) > 0.5) & (sea < 0.5)
    cliff = pool(z.clamp_min(0.0), SEA_CLIFF_SAMPLES)                         # highest land within 120 m
    steep = shore & (cliff >= SEA_CLIFF_M)
    reach = SEA_STACK_REACH_SAMPLES
    near = (pool(steep.float(), reach) > 0.5)[0, 0] & (h60 > 0.0)
    exposure = pool(torch.where(steep, cliff, torch.zeros_like(cliff)), reach)[0, 0]
    style = torch.where(near, torch.full_like(style, 6), style)
    density = torch.where(near, (exposure / SEA_CLIFF_DENSITY_M).clamp(1, 15), density)
    density = torch.where(h60 <= 0.0, torch.zeros_like(density), density)   # no ravines under the sea
    depth = (vd2000[0, 0].clamp(max=1000.0) / 32.0)
    code = pack(density.round(), style, strike, depth.round())
    m = margin
    return code[m:-m, m:-m]
