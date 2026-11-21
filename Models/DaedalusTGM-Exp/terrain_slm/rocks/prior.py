"""Latent prior for RockVAE: a conditional rectified-flow MLP over the VAE's posterior latents.

Sampling z ~ N(0, I) straight into the decoder lands in the "holes" of the aggregate posterior (fragmented blobs);
the flow transports N(0, I) onto the region the decoder knows, conditioned on (style, size bin, grounded).
Training data = posterior samples of every training variant (mu + sigma * eps), so the flow learns a smooth density,
not a lookup table of the scans.

    python -m terrain_slm.rocks.prior --vae checkpoints/rocks/rockvae_v1/last.pt --device cuda:0
"""
from __future__ import annotations

import argparse
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

from . import augment as A
from .dataset import TRUNC
from .model import COND_DIM, cond_vector


class LatentFlow(nn.Module):
    def __init__(self, latent: int, hidden: int = 512, depth: int = 4):
        super().__init__()
        self.latent = latent
        self.inp = nn.Linear(latent + COND_DIM + 32, hidden)
        self.blocks = nn.ModuleList(nn.Sequential(nn.LayerNorm(hidden), nn.Linear(hidden, hidden), nn.SiLU(),
                                                  nn.Linear(hidden, hidden)) for _ in range(depth))
        self.out = nn.Sequential(nn.LayerNorm(hidden), nn.Linear(hidden, latent))

    @staticmethod
    def t_embed(t):
        f = torch.exp(torch.linspace(0, math.log(1000), 16, device=t.device))
        a = t[:, None] * f[None]
        return torch.cat([a.sin(), a.cos()], 1)

    def forward(self, x, t, c):
        h = self.inp(torch.cat([x, c, self.t_embed(t)], 1))
        for b in self.blocks:
            h = h + b(h)
        return self.out(h)

    @torch.no_grad()
    def sample(self, c, gen: torch.Generator, steps: int = 32, tau: float = 1.0) -> torch.Tensor:
        x = (torch.randn(len(c), self.latent, generator=gen) * tau).to(c.device)
        for k in range(steps):
            t = torch.full((len(c),), k / steps, device=c.device)
            x = x + self.forward(x, t, c) / steps
        return x


@torch.no_grad()
def encode_all(vae, data: Path, device) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    z = np.load(data)
    sdf = torch.from_numpy(z["sdf32"])
    c = cond_vector(z["style"], z["size_bin"], z["grounded"])
    mus, sig = [], []
    for i in range(0, len(sdf), 256):
        mu, lv = vae.encode(sdf[i:i + 256].to(device).float() / TRUNC, c[i:i + 256].to(device))
        mus.append(mu.cpu())
        sig.append((0.5 * lv).exp().cpu())
    return torch.cat(mus), torch.cat(sig), c


def main(argv=None):
    from .evaluate import load_model
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vae", type=Path, required=True)
    ap.add_argument("--data", type=Path, default=A.AUG_DEFAULT)
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args(argv)
    torch.manual_seed(a.seed)
    dev = torch.device(a.device)
    vae, ck = load_model(a.vae, dev)
    mu, sig, c = encode_all(vae, a.data, dev)
    mu, sig, c = mu.to(dev), sig.to(dev), c.to(dev)
    flow = LatentFlow(mu.shape[1]).to(dev)
    opt = torch.optim.AdamW(flow.parameters(), lr=3e-4, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps)
    log = open(a.vae.parent / "prior.log", "a")
    t0 = time.time()
    for step in range(1, a.steps + 1):
        i = torch.randint(len(mu), (a.batch,), device=dev)
        x1 = mu[i] + sig[i] * torch.randn_like(mu[i])
        x0 = torch.randn_like(x1)
        t = torch.rand(a.batch, device=dev)
        xt = (1 - t[:, None]) * x0 + t[:, None] * x1
        cc = c[i]
        drop = torch.rand(a.batch, device=dev) < 0.1  # condition dropout (robust to rare combos)
        cc = torch.where(drop[:, None], torch.zeros_like(cc), cc)
        loss = (flow(xt, t, cc) - (x1 - x0)).pow(2).mean()
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        sched.step()
        if step % 1000 == 0:
            msg = f"prior step {step} loss {loss.item():.4f} {time.time() - t0:.0f}s"
            print(msg, file=log, flush=True)
            print(msg, flush=True)
    ck["prior"] = flow.state_dict()
    ck["prior_steps"] = a.steps
    torch.save(ck, a.vae)
    log.close()


if __name__ == "__main__":
    main()
