"""Detail backbone v2 (DaedalusTGM-Exp-2 plan §3): v4's detail sampler rebuilt on magnitude-preserving layers.

Same job and conditioning as `models/detail.py` (60 m samples, asinh residual over the smoothed coarse base, 22
conditioning channels; see there), same topology (stem, two blocks per level, stride-2 down convs, nearest-up conv
+ 1x1 merge, mid blocks, output conv) so the native kernels map onto it, but every layer is an EDM2 MP layer
(`models/mp.py`):

  - activations stay at unit magnitude by construction (the v4 mid-block blow-ups came from unbounded FiLM and
    residual growth); a learned output gain (zero-init) replaces the zero-initialised output conv;
  - time conditioning is multiplicative (x * (1 + gain * emb), gain zero-init), bounded like everything else;
  - two times: `t` (where the sample is) and `r` (where it should land). r == t is the instantaneous velocity of
    plain flow matching (the teacher); r < t ... is MeanFlow's average velocity over [t, r], which lets the
    distilled student jump from noise to data in one or two steps (plan §4);
  - `uncertainty(t)`: EDM2's learned per-time loss weighting (loss / e^u + u).

Expert adapters (plan §3a) hook in through `adapters`: a module per level that adds a gated residual after each
block; none by default.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models import detail as DT
from terrain_slm.models.mp import MPConv, MPFourier, mp_cat, mp_silu, mp_sum, pixel_norm

N_COND = DT.N_COND
CLIP_ACT = 256.0      # EDM2's fp16 guard: activations never leave [-256, 256]


@dataclass
class Detail2Config:
    channels: tuple = (96, 192, 288, 384, 512)
    blocks: int = 2
    mid_blocks: int = 4
    emb: int = 512          # embedding width
    fourier: int = 128      # Fourier features per time input
    dropout: float = 0.1
    res_balance: float = 0.3
    concat_balance: float = 0.5
    arch: str = "detail2"

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class Block(nn.Module):
    """EDM2 UNet block: (enc: pixel-norm on the way in) -> silu -> conv -> *(1 + g emb) -> silu -> dropout -> conv,
    mp_sum residual."""

    def __init__(self, c: int, emb: int, dropout: float, res_balance: float, encoder: bool):
        super().__init__()
        self.encoder, self.res_balance = encoder, res_balance
        self.conv0 = MPConv(c, c, (3, 3))
        self.emb_linear = MPConv(emb, c, ())
        self.emb_gain = nn.Parameter(torch.zeros([]))
        self.conv1 = MPConv(c, c, (3, 3))
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, emb: torch.Tensor) -> torch.Tensor:
        if self.encoder:
            x = pixel_norm(x)
        y = self.conv0(mp_silu(x))
        c = self.emb_linear(emb, gain=self.emb_gain) + 1
        y = mp_silu(y * c[:, :, None, None].to(y.dtype))
        y = self.conv1(self.drop(y))
        return mp_sum(x, y, t=self.res_balance).clip(-CLIP_ACT, CLIP_ACT)


#: Landform styles the gates name (plan §3a, §5). Index = gate channel. The regions behind each are in STYLE_REGIONS.
STYLES = ("glacial_fjord", "volcanic", "drowned_archipelago", "skerries", "lagoon_barrier", "aeolian", "undersea")
STYLE_REGIONS = {
    "glacial_fjord": ("norway", "norway_nordland", "patagonia_fjords", "nz_fiordland", "bc_coast", "iceland_west"),
    "volcanic": ("canaries", "hawaii"),
    "drowned_archipelago": ("aegean", "dalmatia", "seto_inland", "visayas", "halong"),
    "skerries": ("baltic_skerries", "outer_hebrides"),
    "lagoon_barrier": ("wadden", "outer_banks"),
    "aeolian": ("sahara_erg",),
    # NOAA CUDEM 1/9" topobathy (refloor_v2 --cudem): reef walls, shelf edges, submarine canyon heads. Trained with the
    # crop's own sea mask as its gate (train_detail2.ExpertBatches), gated in game by depth (controls2).
    "undersea": ("hawaii", "puerto_rico"),
}
#: styles whose training gate is the crop's sea mask (real depth < 0), not the whole crop
SEA_GATED_STYLES = ("undersea",)
SEA_GATE_MIN_M = -2000.0         # ...and only above this depth (the deep abyss around them is smooth GEBCO, not CUDEM)


class Adapter(nn.Module):
    """One expert's residual at one block: 1x1 down to `rank` -> silu -> 3x3 -> silu -> 1x1 up, zero-gain output.
    Added (plain sum, weighted by the gate) after the block, so a gate of 0 leaves the backbone untouched."""

    def __init__(self, c: int, rank: int):
        super().__init__()
        self.down = MPConv(c, rank, (1, 1))
        self.mid = MPConv(rank, rank, (3, 3))
        self.up = MPConv(rank, c, (1, 1))
        self.gain = nn.Parameter(torch.zeros([]))

    def forward(self, h: torch.Tensor) -> torch.Tensor:
        return self.up(mp_silu(self.mid(mp_silu(self.down(h)))), gain=self.gain)


class Expert(nn.Module):
    """A style expert (plan §3a): adapters after every block at the levels it hooks (default the 1/4 and 1/8 levels
    and the mid blocks: landform character lives at 0.5-5 km), rank = channels // rank_div."""

    def __init__(self, cfg: "Detail2Config", levels: tuple = (2, 3), rank_div: int = 4):
        super().__init__()
        ch = cfg.channels
        self.levels = levels
        self.enc = nn.ModuleDict({str(l): nn.ModuleList(Adapter(ch[l], ch[l] // rank_div) for _ in range(cfg.blocks))
                                  for l in levels})
        self.mid = nn.ModuleList(Adapter(ch[-1], ch[-1] // rank_div) for _ in range(cfg.mid_blocks))
        self.dec = nn.ModuleDict({str(l): nn.ModuleList(Adapter(ch[l], ch[l] // rank_div) for _ in range(cfg.blocks))
                                  for l in levels})


class Detail2(nn.Module):
    def __init__(self, cfg: Detail2Config = Detail2Config()):
        super().__init__()
        self.cfg = cfg
        ch, e = cfg.channels, cfg.emb
        self.fourier_t = MPFourier(cfg.fourier, seed=1)
        self.fourier_h = MPFourier(cfg.fourier, seed=2)      # h = t - r (MeanFlow); 0 for the FM teacher
        self.emb_t = MPConv(cfg.fourier, e, ())
        self.emb_h = MPConv(cfg.fourier, e, ())
        blk = lambda c, enc: Block(c, e, cfg.dropout, cfg.res_balance, enc)
        self.stem = MPConv(1 + N_COND + 1, ch[0], (3, 3))    # + a ones channel instead of a bias
        self.enc = nn.ModuleList(nn.ModuleList(blk(c, True) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.down = nn.ModuleList(MPConv(ch[i], ch[i + 1], (3, 3), stride=2) for i in range(len(ch) - 1))
        self.mid = nn.ModuleList(blk(ch[-1], False) for _ in range(cfg.mid_blocks))
        self.up = nn.ModuleList(MPConv(ch[i + 1], ch[i], (3, 3)) for i in range(len(ch) - 1))
        self.merge = nn.ModuleList(MPConv(2 * c, c, (1, 1)) for c in ch[:-1])
        self.dec = nn.ModuleList(nn.ModuleList(blk(c, False) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.out = MPConv(ch[0], 1, (3, 3))
        self.out_gain = nn.Parameter(torch.zeros([]))
        # Learned per-time loss weighting (EDM2): the uncertainty of the loss at time t.
        self.u_fourier = MPFourier(cfg.fourier, seed=3)
        self.u_linear = MPConv(cfg.fourier, 1, ())
        self.adapters: nn.ModuleDict = nn.ModuleDict()       # expert hooks (plan §3a); empty by default

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    def embed(self, t: torch.Tensor, r: torch.Tensor | None) -> torch.Tensor:
        h = torch.zeros_like(t) if r is None else (t - r)
        e = mp_sum(self.emb_t(self.fourier_t(t)), self.emb_h(self.fourier_h(h)), t=0.5)
        return mp_silu(e)

    def add_expert(self, style: str, **kw) -> Expert:
        e = Expert(self.cfg, **kw).to(next(self.parameters()).device)
        self.adapters[style] = e
        return e

    def forward(self, x_t: torch.Tensor, t: torch.Tensor, cond: torch.Tensor, r: torch.Tensor | None = None,
                gates: torch.Tensor | None = None) -> torch.Tensor:
        """x_t (B,1,H,W), t (B,), cond (B,N_COND,H,W), r (B,) or None -> velocity (average velocity over [t, r]
        when r is given). `gates` (B, len(STYLES), H, W) in [0, 1]: each style expert's weight per sample (from the
        style control fields, never learned, so tiles stay request-independent). An expert whose gate is zero over
        the whole batch is skipped, so it costs nothing and changes nothing there. H, W multiples of `multiple`."""
        emb = self.embed(t, r).to(x_t.dtype)
        active = []
        if gates is not None and len(self.adapters):
            for si, style in enumerate(STYLES):
                if style in self.adapters and bool((gates[:, si] > 0).any()):
                    active.append((self.adapters[style], gates[:, si : si + 1].to(x_t.dtype)))
        gate_at = {}

        def g_at(g: torch.Tensor, h: torch.Tensor) -> torch.Tensor:
            key = (id(g), h.shape[-1])
            if key not in gate_at:
                gate_at[key] = F.adaptive_avg_pool2d(g, h.shape[-2:]) if g.shape[-1] != h.shape[-1] else g
            return gate_at[key]

        def experts(h: torch.Tensor, pick) -> torch.Tensor:
            for ex, g in active:
                ad = pick(ex)
                if ad is not None:
                    h = h + (g_at(g, h) * ad(h)).to(h.dtype)   # stays in the block's dtype (bf16 under autocast)
            return h

        ones = torch.ones_like(x_t)
        h = self.stem(torch.cat([x_t, cond.to(x_t.dtype), ones], dim=1))
        skips = []
        for li, (blocks, down) in enumerate(zip(self.enc, self.down)):
            for bi, b in enumerate(blocks):
                h = experts(b(h, emb), lambda ex: ex.enc[str(li)][bi] if str(li) in ex.enc else None)
            skips.append(h)
            h = down(mp_silu(h))
        for bi, b in enumerate(self.mid):
            h = experts(b(h, emb), lambda ex: ex.mid[bi])
        for i in reversed(range(len(self.up))):
            h = self.up[i](F.interpolate(mp_silu(h), scale_factor=2, mode="nearest"))
            h = self.merge[i](mp_cat(h, skips[i], t=self.cfg.concat_balance))
            for bi, b in enumerate(self.dec[i]):
                h = experts(b(h, emb), lambda ex: ex.dec[str(i)][bi] if str(i) in ex.dec else None)
        return self.out(mp_silu(h), gain=self.out_gain)

    def uncertainty(self, t: torch.Tensor) -> torch.Tensor:
        """(B,) learned log-variance of the loss at t (EDM2 loss weighting)."""
        return self.u_linear(self.u_fourier(t)).reshape(-1)


@torch.no_grad()
def sample(model: Detail2, cond: torch.Tensor, noise: torch.Tensor, steps: int = 16, t_start: float = 0.0,
           mean_flow: bool = False, gates: torch.Tensor | None = None) -> torch.Tensor:
    """Euler from t_start to data (t = 1). `mean_flow`: each step uses the average velocity over the step
    (the distilled student: exact in one step if trained so)."""
    x = (1.0 - t_start) * noise
    ts = torch.linspace(t_start, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
        r = torch.full_like(t, float(ts[i + 1])) if mean_flow else None
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            v = model(x, t, cond, r, gates=gates).float()
        x = x + (ts[i + 1] - ts[i]) * v
    return x


def count_params(m: nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())
