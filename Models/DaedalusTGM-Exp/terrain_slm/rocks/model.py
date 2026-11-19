"""RockVAE: a small conditional 3D VAE over 32^3 truncated SDFs of rocks.

Condition c = [style one-hot (rounded, angular, slab, tor), size-bin one-hot (stone, rock, boulder), grounded (flat
base, from outcrop scans)]. The decoder maps (z, c) to an SDF in [-1, 1] (x TRUNC cells); the surface is sdf = 0.
New shapes = decoder(z ~ N(0, tau^2 I), c). ~3.6 M parameters.
"""
from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

N_STYLE, N_SIZE = 4, 3
COND_DIM = N_STYLE + N_SIZE + 1


def cond_vector(style, size_bin, grounded, device=None) -> torch.Tensor:
    style = torch.as_tensor(style, device=device).long()
    size_bin = torch.as_tensor(size_bin, device=device).long()
    grounded = torch.as_tensor(grounded, device=device).float()
    return torch.cat([F.one_hot(style, N_STYLE).float(), F.one_hot(size_bin, N_SIZE).float(), grounded[:, None]], 1)


def _block(cin, cout, down):
    conv = nn.Conv3d(cin, cout, 4, 2, 1) if down else nn.ConvTranspose3d(cin, cout, 4, 2, 1)
    return nn.Sequential(conv, nn.GroupNorm(8, cout), nn.SiLU(),
                         nn.Conv3d(cout, cout, 3, 1, 1), nn.GroupNorm(8, cout), nn.SiLU())


class RockVAE(nn.Module):
    def __init__(self, latent: int = 96, width: int = 32):
        super().__init__()
        w = width
        self.latent = latent
        self.enc = nn.Sequential(_block(1, w, True), _block(w, 2 * w, True), _block(2 * w, 4 * w, True))  # 32->4
        self.enc_fc = nn.Linear(4 * w * 64 + COND_DIM, 2 * latent)
        self.dec_fc = nn.Linear(latent + COND_DIM, 4 * w * 64)
        self.dec = nn.Sequential(_block(4 * w, 2 * w, False), _block(2 * w, w, False), _block(w, w, False))
        self.out = nn.Conv3d(w + COND_DIM, 1, 3, 1, 1)
        self.w = w

    def encode(self, x, c):
        h = self.enc(x[:, None]).flatten(1)
        mu, logvar = self.enc_fc(torch.cat([h, c], 1)).chunk(2, 1)
        return mu, logvar.clamp(-8, 6)

    def decode(self, z, c):
        h = self.dec_fc(torch.cat([z, c], 1)).view(-1, 4 * self.w, 4, 4, 4)
        h = self.dec(F.silu(h))
        cc = c[:, :, None, None, None].expand(-1, -1, *h.shape[2:])
        return torch.tanh(self.out(torch.cat([h, cc], 1)))[:, 0]

    def forward(self, x, c):
        mu, logvar = self.encode(x, c)
        z = mu + torch.randn_like(mu) * (0.5 * logvar).exp()
        return self.decode(z, c), mu, logvar
