"""EXP-2 generator (plan §3, §5b, §2): v4's WorldGenerator with the EXP-2 nodes plugged into its hooks.

    gen = WorldGenerator2(model_dir, seed, device)          # same API as WorldGenerator

Uses what the model directory holds and falls back to v4 behaviour for what it does not, so it runs at every stage
of EXP-2's training:
  controls      always `world/controls2.py` (islands, volcanoes, ocean floors, belts, wind, style gates)
  climate.pt    the climate node (models/climatenet.py) replaces v4's archetype climate (t0 / tseason / precip / pcv)
                and adds aridity / soil / snow; it also fills the aeolian gate
  detail.pt     a Detail2 with experts gets the style gates per window (experts at gate 0 are skipped)
  sr30.pt       the SR node draws 30 m pixels at 1:2 (instead of the bilinear stand-in)

Request independence: the climate node runs on canonical windows of its own ~28 km grid (ERA5's resolution) and is
cross-faded, then sampled bilinearly at cells; SR runs on canonical windows cross-faded with weights constant inside
each 2x2 block, so the result stays exactly mean-preserving.
"""
from __future__ import annotations

import math
from dataclasses import replace
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import climatenet as CN
from terrain_slm.models import detail as DT
from terrain_slm.models import detail2 as D2
from terrain_slm.models import sr as SRM
from terrain_slm.synth import noise as S
from terrain_slm.world import controls2 as C2
from terrain_slm.world import generator as G

CLIMATE_GRID_CELLS = 116.0                  # ~27.8 km at 240 m cells: ERA5's 0.25 deg, the node's training grid
CLIMATE_WIN, CLIMATE_CORE, CLIMATE_APRON = 96, 48, 24
CLIMATE_SUB = 4                             # sub-points per grid cell and axis (land fraction, mean elevation)
GRID_KM = CLIMATE_GRID_CELLS * 0.24
CLIMATE_FETCH_CELLS = 38                    # > 1000 km (the far upwind fetch) in grid cells
WIND_SCALE = 0.55                           # ERA5 10 m VECTOR-mean wind / controls2 belt wind (sea 30-55 deg: 3.45 vs ~7)
LAND_WIND = (0.08, 0.92)                    # x (a + b (1 - land share within 250 km)^2): interiors ~0.1x, coasts ~0.3x
# Lowland micro-relief (2026-11-18): at 1:4 a lowland block is 16 m, so real 10-30 m swales read as 0-1 block. Small
# deviations of the 60 m terrain from its own ~500 m smoothed surface are amplified (up to x(1 + LOWLAND_BOOST) for
# |d| << LOWLAND_D0_M, fading for larger relief), only in lowland: regional elevation, coasts and mountains unchanged.
MEANFLOW_DETAIL_STEPS = 2
LOWLAND_BOOST = 0.0                         # 2026-11-19: OFF. 3.5 (sweep 50/30/18/2) made meadows jagged in play (user); 60 m
                                            # amplification is the wrong tool -> lowland landform provinces in controls2 instead
LOWLAND_D0_M = 40.0
LOWLAND_SIGMA_SAMPLES = 4.0                 # 60 m samples: ~240 m std, a ~500 m neighbourhood
LOWLAND_TOP_M = (400.0, 800.0)             # full boost below 400 m regional elevation, none above 800 m
SR_CORE, SR_APRON = 256, 32                 # fine (30 m) pixels
SR_STEPS = 8
SR_NOISE_STREAM = 30011


class WorldGenerator2(G.WorldGenerator):
    def __init__(self, model_dir: Path, seed: int, device: str, cfg: G.GenConfig = G.GenConfig(),
                 cache_regions: int = 512):
        super().__init__(model_dir, seed, device, cfg, cache_regions)
        self.climate_net, cl = self._optional(model_dir / "climate.pt", lambda c: CN.ClimateNet(CN.ClimateConfig(
            **{k: v for k, v in c.items()})))
        self.sr30, sr = self._optional(model_dir / "sr30.pt", lambda c: SRM.SR(SRM.SRConfig(**c)))
        self.model_id += f":X2:c{cl}:s{sr}"
        # A MeanFlow detail student is trained, scanned and shipped at 2 steps; the GenConfig default (16) is the FM
        # teacher's. An explicit non-default detail_steps is kept.
        if getattr(self.detail, "mean_flow", False) and self.cfg.detail_steps == G.GenConfig.detail_steps:
            self.cfg = replace(self.cfg, detail_steps=MEANFLOW_DETAIL_STEPS)
        self._climate_windows = G._LRU(32, 96)
        self.climate_forcing = (0.0, 0.0)      # (global dT degC, age ka): today; other values = ice-age / warm dial
        # World option (2026-11-18): 'belts' (default: warped latitude, random pole axis) or 'zones' (no axis). Part of
        # the world's identity like the seed: set it before the first tile (use_climate_layout clears the caches).
        self.climate_layout = "belts"
        self.lowland_boost = LOWLAND_BOOST           # 0 = off (A/B knob)
        self._base_model_id = self.model_id
        self._refresh_model_id()
        self._sr_windows = G._LRU(32, 224)

    def _refresh_model_id(self) -> None:
        """model_id names everything that changes tiles (TGMPipe keys its disk cache by it): layout and lowland boost."""
        self.model_id = (f"{self._base_model_id}:ds{self.cfg.detail_steps}:L{self.climate_layout}"
                         f":lb{self.lowland_boost:g}")

    def use_lowland_boost(self, boost: float) -> None:
        if boost != self.lowland_boost:
            self.lowland_boost = boost
            self._detail_windows.clear()
            self._refresh_model_id()

    def use_climate_layout(self, layout: str) -> None:
        """Switch the world's climate layout (C2.CLIMATE_LAYOUTS); clears every cache that depends on the controls."""
        if layout not in C2.CLIMATE_LAYOUTS:
            raise ValueError(f"climate layout {layout!r} not in {C2.CLIMATE_LAYOUTS}")
        if layout != self.climate_layout:
            self.climate_layout = layout
            for v in list(vars(self).values()):          # every LRU is keyed by seed, not layout: start clean
                if isinstance(v, G._LRU):
                    v.clear()
            self._refresh_model_id()

    # ------------------------------------------------------------------ controls + climate
    def controls(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        c = C2.controls2(ci0, cj0, hc, wc, self.seed, self.device, self.climate_layout)
        if self.climate_net is not None:
            c.update(self.climate_cells(ci0, cj0, hc, wc))
            gates = c["gates"].clone()
            gates[D2.STYLES.index("aeolian")] = C2.aeolian_gate(c["aridity"], c["trend"])
            c["gates"] = gates
        return c

    def _detail_window(self, wi: int, wj: int) -> torch.Tensor:
        """The canonical detail window with the lowland micro-relief boost applied inside it (before the cross-fade, so
        neighbouring tiles agree)."""
        win = super()._detail_window(wi, wj)
        if self.lowland_boost <= 0:
            return win
        regional = D.blur(win[None, None], LOWLAND_SIGMA_SAMPLES)[0, 0]
        d = win - regional
        low = 1 - C2._ss((regional - LOWLAND_TOP_M[0]) / (LOWLAND_TOP_M[1] - LOWLAND_TOP_M[0]))
        land = C2._ss(regional / 5.0)
        gain = 1 + self.lowland_boost * torch.exp(-(d / LOWLAND_D0_M) ** 2) * low * land
        return regional + d * gain

    def climate_native(self, i1: int, j1: int, i2: int, j2: int, elev: torch.Tensor) -> torch.Tensor:
        """(4 or 7, H, W) climate at native pixels (temperature lapse-adjusted to `elev`): with the climate node, also
        aridity, soil water and snow (biomes.has_extras), which the biome rules and the extras biome sidecar read."""
        clim = super().climate_native(i1, j1, i2, j2, elev)
        if self.climate_net is None:
            return clim
        ci0, cj0 = i1 // G.CP - 1, j1 // G.CP - 1
        hc, wc = (i2 - 1) // G.CP + 2 - ci0, (j2 - 1) // G.CP + 2 - cj0
        with self.lock:
            ctrl = self.controls(ci0, cj0, hc, wc)
        stack = torch.stack([ctrl["aridity"], ctrl["soil"], ctrl["snow"]])[None]
        up = F.interpolate(stack, size=(hc * G.CP, wc * G.CP), mode="bilinear", align_corners=False)[0]
        oi, oj = i1 - ci0 * G.CP, j1 - cj0 * G.CP
        return torch.cat([clim, up[:, oi : oi + (i2 - i1), oj : oj + (j2 - j1)]])

    def climate_cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict[str, torch.Tensor]:
        """The climate node's fields at cells [ci0, ci0+hc) x [cj0, cj0+wc), in climate_era5's units."""
        g = CLIMATE_GRID_CELLS
        gi0, gj0 = math.floor(ci0 / g) - 1, math.floor(cj0 / g) - 1
        gi1, gj1 = math.floor((ci0 + hc) / g) + 2, math.floor((cj0 + wc) / g) + 2
        y = self._climate_grid(gi0, gj0, gi1 - gi0, gj1 - gj0)                    # (7, gh, gw) normalised
        ci = (torch.arange(ci0, ci0 + hc, device=self.device, dtype=torch.float32) + 0.5) / g - 0.5 - gi0
        cj = (torch.arange(cj0, cj0 + wc, device=self.device, dtype=torch.float32) + 0.5) / g - 0.5 - gj0
        gh, gw = y.shape[-2:]
        grid = torch.stack([(cj / (gw - 1)) * 2 - 1, torch.zeros_like(cj)], -1)   # x = j, y = i (normalised)
        gy = (ci / (gh - 1)) * 2 - 1
        sample = torch.stack(torch.broadcast_tensors(grid[None, :, 0], gy[:, None]), -1)
        out = F.grid_sample(y[None], sample[None], mode="bilinear", align_corners=True)[0]
        return {k: v[0] for k, v in CN.denorm(out[None]).items()}

    def _climate_grid(self, gi0: int, gj0: int, gh: int, gw: int) -> torch.Tensor:
        out = torch.zeros(len(CN.OUT_NAMES), gh, gw, device=self.device)
        ramp = 2 * CLIMATE_APRON
        t = (torch.arange(ramp, device=self.device, dtype=torch.float32) + 0.5) / ramp
        w1 = torch.ones(CLIMATE_WIN, device=self.device)
        w1[:ramp] = torch.sin(0.5 * math.pi * t) ** 2
        w1[-ramp:] = torch.cos(0.5 * math.pi * t) ** 2
        w2 = w1[:, None] * w1[None, :]
        for wi in range(math.floor((gi0 - CLIMATE_WIN + CLIMATE_APRON) / CLIMATE_CORE),
                        math.floor((gi0 + gh - 1 + CLIMATE_APRON) / CLIMATE_CORE) + 1):
            for wj in range(math.floor((gj0 - CLIMATE_WIN + CLIMATE_APRON) / CLIMATE_CORE),
                            math.floor((gj0 + gw - 1 + CLIMATE_APRON) / CLIMATE_CORE) + 1):
                a0, b0 = wi * CLIMATE_CORE - CLIMATE_APRON, wj * CLIMATE_CORE - CLIMATE_APRON
                r0, r1 = max(gi0, a0), min(gi0 + gh, a0 + CLIMATE_WIN)
                s0, s1 = max(gj0, b0), min(gj0 + gw, b0 + CLIMATE_WIN)
                if r0 >= r1 or s0 >= s1:
                    continue
                win = self._climate_window(wi, wj)
                out[:, r0 - gi0 : r1 - gi0, s0 - gj0 : s1 - gj0] += (
                    win[:, r0 - a0 : r1 - a0, s0 - b0 : s1 - b0] * w2[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0])
        return out

    def _land_lattice(self, gi0: int, gj0: int, n: int, step: int) -> torch.Tensor:
        """Land fraction on a lattice of `step` grid cells, n x n points starting at grid (gi0, gj0) (pointwise)."""
        g = CLIMATE_GRID_CELLS
        pi = (gi0 + (torch.arange(n, device=self.device, dtype=torch.float32) + 0.5) * step) * g
        pj = (gj0 + (torch.arange(n, device=self.device, dtype=torch.float32) + 0.5) * step) * g
        c = C2.controls2_at(pi.view(-1, 1).expand(n, n).contiguous(), pj.view(1, -1).expand(n, n).contiguous(), self.seed, self.climate_layout)
        return (c["trend"] > 0).float()

    def _continentality(self, a0: int, b0: int, km: float) -> torch.Tensor:
        """Land fraction blurred at `km` over the window [a0, a0+WIN) x [b0, b0+WIN) of grid cells, from a coarse
        lattice reaching 3 sigma beyond it (the window alone is too small for 1000-2500 km blurs)."""
        sigma = km / GRID_KM
        step = max(1, int(sigma // 3))
        reach = int(math.ceil(3 * sigma / step)) + 1
        n = CLIMATE_WIN // step + 2 * reach + 1
        land = self._land_lattice(a0 - reach * step, b0 - reach * step, n, step)
        blurred = D.blur(land[None, None], sigma / step)[0, 0]
        # lattice point k sits at grid a0 - reach*step + (k + 0.5) * step; window cell i at a0 + i + 0.5
        idx = (torch.arange(CLIMATE_WIN, device=self.device, dtype=torch.float32) + 0.5) / step + reach - 0.5
        gy = (idx / (n - 1)) * 2 - 1
        grid = torch.stack(torch.broadcast_tensors(gy[None, :], gy[:, None]), -1)
        return F.grid_sample(blurred[None, None], grid[None], mode="bilinear", align_corners=True)[0, 0]

    def _climate_window(self, wi: int, wj: int) -> torch.Tensor:
        def run():
            from scipy import ndimage

            a0, b0 = wi * CLIMATE_CORE - CLIMATE_APRON, wj * CLIMATE_CORE - CLIMATE_APRON
            n, k, g, e = CLIMATE_WIN, CLIMATE_SUB, CLIMATE_GRID_CELLS, CLIMATE_FETCH_CELLS
            # Controls over the window plus a fetch margin, pooled to the node's grid exactly like its training inputs
            # (0.25 deg block means): the upwind fields sample THIS smooth grid, as training sampled ERA5's -- point
            # samples of the raw trend read every sharp ridge as a km-high barrier (t0 fell ~25 C at spawn).
            m = n + 2 * e
            sub = (torch.arange(m * k, device=self.device, dtype=torch.float32) + 0.5) / k
            pi = ((a0 - e + sub) * g).view(-1, 1).expand(m * k, m * k).contiguous()
            pj = ((b0 - e + sub) * g).view(1, -1).expand(m * k, m * k).contiguous()
            c = C2.controls2_at(pi, pj, self.seed, self.climate_layout)
            pool = lambda x: F.avg_pool2d(x[None, None], k)[0, 0]
            trend = c["trend"]
            land_x = pool((trend > 0).float())
            elev_x = pool(trend.clamp_min(0)) / land_x.clamp_min(1e-3) / 1000.0 * (land_x > 0)
            inner = lambda x: x[e : e + n, e : e + n]
            land, elev = inner(land_x), inner(elev_x)
            sea = (land < 0.5).cpu().numpy()
            d_land = ndimage.distance_transform_edt(sea) * GRID_KM
            d_sea = ndimage.distance_transform_edt(~sea) * GRID_KM
            coast = torch.from_numpy(((d_sea - d_land) / 50.0)).to(self.device).float().asinh()
            belt = inner(pool(c["belt"]))
            l250, l1000 = self._continentality(a0, b0, 250.0), self._continentality(a0, b0, 1000.0)
            # The node saw ERA5's 10 m annual VECTOR-mean wind: weak over land (roughness + changing directions; land
            # 30-55 deg: u ~0.3-0.5 m/s vs 3.45 at sea). The procedural belt wind is a free wind, 5x too strong over
            # land, which put spawn at -6 C; damp it by the land share around to the distribution the node knows.
            damp = WIND_SCALE * (LAND_WIND[0] + LAND_WIND[1] * (1.0 - l250) ** 2)
            u, v = inner(pool(c["wind_u"])) * damp, inner(pool(c["wind_v"])) * damp
            grid = torch.stack([land_x, elev_x])[None]
            ii = (torch.arange(n, device=self.device, dtype=torch.float32) + e).view(-1, 1).expand(n, n)
            jj = (torch.arange(n, device=self.device, dtype=torch.float32) + e).view(1, -1).expand(n, n)

            def fetch(drow, dcol):            # bilinear on the pooled grid (positions beyond it clamp at its edge)
                gy = ((ii + drow).clamp(0, m - 1) / (m - 1)) * 2 - 1
                gx = ((jj + dcol).clamp(0, m - 1) / (m - 1)) * 2 - 1
                out = F.grid_sample(grid, torch.stack([gx, gy], -1)[None], mode="bilinear", align_corners=True)[0]
                return out[0], out[1]

            up = CN.upwind_features(fetch, u, v, GRID_KM, GRID_KM, elev, l250, l1000)
            x = torch.stack([elev, land, l250, l1000, self._continentality(a0, b0, 2500.0), coast, belt, belt.abs(),
                             u / 5.0, v / 5.0, *up,
                             torch.full_like(u, self.climate_forcing[0] / CN.FORCING_DT_SCALE),
                             torch.full_like(u, self.climate_forcing[1] / CN.FORCING_AGE_SCALE)])
            with torch.no_grad():
                return self.climate_net(x[None])[0].float()
        return self._climate_windows.get_or((self.seed, wi, wj), run)

    # ------------------------------------------------------------------ detail experts
    def detail_gates(self, ci0: int, cj0: int, nc: int, margin_samples: int) -> torch.Tensor | None:
        if not isinstance(self.detail, D2.Detail2) or not len(self.detail.adapters):
            return None
        gates = self.controls(ci0, cj0, nc, nc)["gates"]                           # (S, nc, nc) cells
        up = DT.up_cells(gates[None])[0]                                           # samples
        m = margin_samples
        return up[:, m : m + G.DETAIL_EXT, m : m + G.DETAIL_EXT][None]

    # ------------------------------------------------------------------ SR at 1:2
    def fine_detail(self, i1: int, j1: int, i2: int, j2: int) -> torch.Tensor | None:
        if self.sr30 is None or self.scale.px_per_block >= DT.DS:
            return None
        h, w = i2 - i1, j2 - j1
        out = torch.zeros(h, w, device=self.device)
        ext = SR_CORE + 2 * SR_APRON
        ramp = 2 * SR_APRON
        t = (torch.arange(ramp // 2, device=self.device, dtype=torch.float32) + 0.5) / (ramp // 2)
        w1 = torch.ones(ext // 2, device=self.device)                              # per 2x2 block: exact means
        w1[: ramp // 2] = torch.sin(0.5 * math.pi * t) ** 2
        w1[-(ramp // 2):] = torch.cos(0.5 * math.pi * t) ** 2
        w1 = w1.repeat_interleave(2)
        w2 = w1[:, None] * w1[None, :]
        for wi in range(math.floor((i1 - ext + SR_APRON) / SR_CORE), math.floor((i2 - 1 + SR_APRON) / SR_CORE) + 1):
            for wj in range(math.floor((j1 - ext + SR_APRON) / SR_CORE), math.floor((j2 - 1 + SR_APRON) / SR_CORE) + 1):
                a0, b0 = wi * SR_CORE - SR_APRON, wj * SR_CORE - SR_APRON
                r0, r1 = max(i1, a0), min(i2, a0 + ext)
                s0, s1 = max(j1, b0), min(j2, b0 + ext)
                if r0 >= r1 or s0 >= s1:
                    continue
                win = self._sr_window(wi, wj)
                out[r0 - i1 : r1 - i1, s0 - j1 : s1 - j1] += (
                    win[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0] * w2[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0])
        return out

    def _sr_window(self, wi: int, wj: int) -> torch.Tensor:
        def run():
            ext = SR_CORE + 2 * SR_APRON
            a0, b0 = wi * SR_CORE - SR_APRON, wj * SR_CORE - SR_APRON           # fine px, even
            m = 4                                                                # samples of bicubic context
            coarse = self._detail_samples(a0 // 2 - m, b0 // 2 - m, (a0 + ext) // 2 + m, (b0 + ext) // 2 + m)
            up = SRM.upsample(coarse[None, None])
            cond = SRM.build_cond(up, 30.0)[..., 2 * m : 2 * m + ext, 2 * m : 2 * m + ext]
            noise = S.white_noise(a0, b0, ext, ext, self.seed, SR_NOISE_STREAM, self.device)[None, None]
            x = SRM.sample(self.sr30, cond.contiguous(memory_format=torch.channels_last), noise, SR_STEPS)
            fine = SRM.assemble(coarse[None, None][..., m:-m, m:-m], x)
            return fine[0, 0]
        return self._sr_windows.get_or((self.seed, wi, wj), run)
