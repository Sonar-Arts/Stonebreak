"""Per-cell terrain descriptors: the contract between the planner and the synth.

Everything here is a *statistic* of the heightfield, measured the same way on real
DEM data (training targets) and on synth output (the round-trip gate), so the synth
only has to match statistics, never pixels.

Bands are sharp frequency-domain windows: band o holds wavelengths 2**(o+1) .. 2**(o+2)
native pixels (o = 0..4, i.e. 2..64 px), with raised-cosine edges a quarter octave wide.
The squared masks telescope (sum_o M_o^2 + C_5 = 1), so bands barely leak into each
other and the synth can use the very same masks. Wavelengths above 64 px are "coarse":
the planner's coarse-height field carries them.

Channels (v0, all per 8x8-pixel cell):
  0..4  log_amp[o]  log(local RMS of band o + AMP_EPS), metres
  5     ridge       skewness of the mid band (bands 1..3): > 0 sharp crests, < 0 sharp valleys
  6     aniso_c     structure-tensor coherence * cos(2 theta) of the mid band
  7     aniso_s     structure-tensor coherence * sin(2 theta); theta = dominant gradient direction
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

CELL_PX = 8
N_BANDS = 5
BAND_EDGES_PX = tuple(2.0 ** (k + 1) for k in range(N_BANDS + 1))  # 2, 4, ..., 64
EDGE_HALF_WIDTH_OCT = 0.25
AMP_EPS = 0.01
# Local window (sigma, px) over which each band's energy is averaged: at least half a
# cell, and wide enough to hold a couple of that band's wavelengths.
ENERGY_SIGMA = tuple(max(4.0, BAND_EDGES_PX[o]) for o in range(N_BANDS))  # 4,4,8,16,32
STAT_SIGMA = 8.0  # window for ridge / anisotropy statistics
MID_BANDS = (1, 2, 3)
N_CHANNELS = N_BANDS + 3
FFT_PAD_PX = 96  # reflect padding before the band FFT (> the longest band wavelength)
# Pixels of context a chunk needs on each side so its interior is unaffected by edges.
HALO_PX = int(math.ceil(3.0 * ENERGY_SIGMA[-1])) + 2 * int(BAND_EDGES_PX[-1]) + CELL_PX

CHANNEL_NAMES = tuple(f"log_amp{o}" for o in range(N_BANDS)) + ("ridge", "aniso_c", "aniso_s")


def _smoothstep_oct(x: torch.Tensor) -> torch.Tensor:
    """0 -> 1 across x in [-w, w] octaves (w = EDGE_HALF_WIDTH_OCT), raised cosine."""
    t = (x / EDGE_HALF_WIDTH_OCT).clamp(-1.0, 1.0)
    return 0.5 * (1.0 + torch.sin(0.5 * math.pi * t))


def cumulative_masks(omega: torch.Tensor) -> list[torch.Tensor]:
    """C_k(omega), k = 0..N_BANDS: energy share at wavelengths longer than edge k."""
    lam = (2 * math.pi) / omega.clamp_min(1e-9)
    ell = torch.log2(lam)
    return [_smoothstep_oct(ell - math.log2(e)) for e in BAND_EDGES_PX]


def band_masks(omega: torch.Tensor) -> list[torch.Tensor]:
    """Amplitude masks M_o, o = 0..N_BANDS-1 (band 0 is open towards high frequency)."""
    c = cumulative_masks(omega)
    masks = [torch.sqrt((1.0 - c[1]).clamp_min(0.0))]
    masks += [torch.sqrt((c[o] - c[o + 1]).clamp_min(0.0)) for o in range(1, N_BANDS)]
    return masks


def lowpass_mask(omega: torch.Tensor, edge_index: int) -> torch.Tensor:
    """Smooth amplitude mask keeping wavelengths longer than BAND_EDGES_PX[edge_index]."""
    return cumulative_masks(omega)[edge_index]


def freq_grid(h: int, w: int, device):
    """Radial frequency (rad/px) and wave-vector angle (atan2(ky, kx), x = columns)."""
    ky = torch.fft.fftfreq(h, device=device).view(-1, 1) * 2 * math.pi
    kx = torch.fft.fftfreq(w, device=device).view(1, -1) * 2 * math.pi
    return torch.sqrt(kx**2 + ky**2), torch.atan2(ky.expand(h, w), kx.expand(h, w))


def gaussian_kernel1d(sigma: float, device=None, dtype=torch.float32) -> torch.Tensor:
    radius = max(1, int(math.ceil(3.0 * sigma)))
    x = torch.arange(-radius, radius + 1, device=device, dtype=dtype)
    k = torch.exp(-0.5 * (x / sigma) ** 2)
    return k / k.sum()


def blur(x: torch.Tensor, sigma: float) -> torch.Tensor:
    """Separable Gaussian blur of a (N, C, H, W) tensor, reflect-padded."""
    k = gaussian_kernel1d(sigma, x.device, x.dtype)
    r = (k.numel() - 1) // 2
    c = x.shape[1]
    kx = k.view(1, 1, 1, -1).expand(c, 1, 1, -1)
    ky = k.view(1, 1, -1, 1).expand(c, 1, -1, 1)
    x = F.conv2d(F.pad(x, (r, r, 0, 0), mode="reflect"), kx, groups=c)
    return F.conv2d(F.pad(x, (0, 0, r, r), mode="reflect"), ky, groups=c)


def bands(z: torch.Tensor) -> torch.Tensor:
    """(N, 1, H, W) heights -> (N, N_BANDS, H, W) band-passed fields (FFT, reflect-padded)."""
    p = FFT_PAD_PX
    zp = F.pad(z, (p, p, p, p), mode="reflect")
    h, w = zp.shape[-2:]
    omega, _ = freq_grid(h, w, z.device)
    spec = torch.fft.fft2(zp)
    out = [torch.fft.ifft2(spec * m).real[..., p:-p, p:-p] for m in band_masks(omega)]
    return torch.cat(out, dim=1)


def pool_cells(x: torch.Tensor) -> torch.Tensor:
    return F.avg_pool2d(x, CELL_PX)


def extract(z: torch.Tensor) -> torch.Tensor:
    """Descriptors for a heightfield.

    Args:
        z: (N, 1, H, W) or (H, W) metres; H and W multiples of CELL_PX. Cells within
           HALO_PX of the border see reflected context -- crop them when tiling.
    Returns:
        (N, N_CHANNELS, H/8, W/8) float32.
    """
    if z.dim() == 2:
        z = z[None, None]
    z = z.float()
    b = bands(z)

    amps = []
    for o in range(N_BANDS):
        energy = blur(b[:, o : o + 1] ** 2, ENERGY_SIGMA[o])
        amps.append(torch.log(torch.sqrt(pool_cells(energy).clamp_min(0.0)) + AMP_EPS))

    h = b[:, list(MID_BANDS)].sum(dim=1, keepdim=True)
    m2 = blur(h**2, STAT_SIGMA)
    m3 = blur(h**3, STAT_SIGMA)
    ridge = pool_cells(m3) / (pool_cells(m2).clamp_min(1e-6) ** 1.5)
    ridge = ridge.clamp(-3.0, 3.0)

    gx = (F.pad(h, (1, 1, 0, 0), mode="replicate")[..., :, 2:] - F.pad(h, (1, 1, 0, 0), mode="replicate")[..., :, :-2]) * 0.5
    gy = (F.pad(h, (0, 0, 1, 1), mode="replicate")[..., 2:, :] - F.pad(h, (0, 0, 1, 1), mode="replicate")[..., :-2, :]) * 0.5
    jxx = pool_cells(blur(gx * gx, STAT_SIGMA))
    jyy = pool_cells(blur(gy * gy, STAT_SIGMA))
    jxy = pool_cells(blur(gx * gy, STAT_SIGMA))
    tr = (jxx + jyy).clamp_min(1e-8)
    aniso_c = (jxx - jyy) / tr
    aniso_s = 2.0 * jxy / tr

    return torch.cat(amps + [ridge, aniso_c, aniso_s], dim=1)


def coarse_height(z: torch.Tensor) -> torch.Tensor:
    """Cell-mean elevation, (N, 1, H/8, W/8)."""
    if z.dim() == 2:
        z = z[None, None]
    return pool_cells(z.float())


def extract_tiled(z, chunk_px: int = 2048, device: str = "cuda") -> torch.Tensor:
    """`extract` over a large (H, W) array (numpy or memmap) in haloed chunks.

    Returns a CPU float32 tensor (N_CHANNELS, H/8, W/8). Interior cells are exact;
    cells within HALO_PX of the *array* border use reflected context.
    """
    import numpy as np

    H, W = z.shape
    assert H % CELL_PX == 0 and W % CELL_PX == 0 and chunk_px % CELL_PX == 0
    out = torch.empty((N_CHANNELS, H // CELL_PX, W // CELL_PX), dtype=torch.float32)
    halo = (HALO_PX + CELL_PX - 1) // CELL_PX * CELL_PX
    for i0 in range(0, H, chunk_px):
        for j0 in range(0, W, chunk_px):
            i1, j1 = min(H, i0 + chunk_px), min(W, j0 + chunk_px)
            a0, b0 = max(0, i0 - halo), max(0, j0 - halo)
            a1, b1 = min(H, i1 + halo), min(W, j1 + halo)
            block = torch.from_numpy(np.ascontiguousarray(z[a0:a1, b0:b1], dtype=np.float32)).to(device)
            d = extract(block)[0].cpu()
            ci, cj = (i0 - a0) // CELL_PX, (j0 - b0) // CELL_PX
            out[:, i0 // CELL_PX : i1 // CELL_PX, j0 // CELL_PX : j1 // CELL_PX] = d[
                :, ci : ci + (i1 - i0) // CELL_PX, cj : cj + (j1 - j0) // CELL_PX
            ]
    return out
