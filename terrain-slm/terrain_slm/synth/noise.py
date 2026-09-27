"""Descriptor-driven procedural synth: coarse heights + descriptors -> per-pixel heights.

Design (see descriptors.py for the statistics being matched):

* White noise is a pure function of absolute pixel coordinates, seed and stream id
  (integer hash, Irwin-Hall Gaussianisation, no libm), so any region of the world can be
  generated independently and overlapping regions agree.
* Band o's noise is that white noise filtered in the frequency domain by *the extractor's
  own band mask* times an angular window. The masks barely overlap, so the energy the
  extractor measures per band is (almost) exactly the energy put in; the small residual
  leakage is removed analytically (`_band_gain_matrix`).
* Frequency split: bands 0..4 (wavelengths 2..64 px) are synthesised; the upsampled coarse
  field is Gaussian low-passed (half power at ~64 px), so little is counted twice. Band 4
  (~1-2 km ridges/valleys) must come from the synth: a regression planner's coarse field is
  the conditional *mean*, which is smooth, so relying on it for band 4 erased that scale.
* Anisotropy: an isotropic stream plus eight oriented streams (wave-vector angle every
  22.5 deg). The streams are independent, so structure tensors add: the target coherence
  vector is decomposed exactly onto the two nearest bank directions.
* Ridge: a skew transform u -> (u + a(u^2 - 1)) / sqrt(1 + 2a^2) on the normalised mid
  band, with `a` from the analytic skew of that transform, scaled by a measured gain.

Region independence: every filter is a fixed, finite spatial kernel (noise kernels are
cut from the band masks once, Hann-windowed to KERNEL_RADIUS; the coarse low-pass is a
Gaussian), applied as a *linear* convolution. Callers pass cells for the region plus
MARGIN_CELLS on every side, taken from the same world lattice, and only the interior is
returned, so two overlapping requests agree up to float rounding.
"""
from __future__ import annotations

import math
from functools import lru_cache

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D

SYNTH_BANDS = 5
MARGIN_CELLS = 16
N_ORIENT = 8
ORIENTATIONS = tuple(i * math.pi / N_ORIENT for i in range(N_ORIENT))
ANGULAR_POWER = 3  # angular window cos^(2p)(phi - theta)
N_STREAMS = 1 + N_ORIENT
_MASK32 = 0xFFFFFFFF
KERNEL_RADIUS = (8, 16, 40, 64, 128)  # px, per synth band; must not exceed MARGIN_CELLS * CELL_PX
KERNEL_GRID = 512
BASE_LOWPASS_SIGMA = 12.0  # px; Gaussian response 0.5 at a ~64 px wavelength

# Measured by tests/test_synth_roundtrip.py::test_calibration_constants_report.
RIDGE_GAIN = 0.56
ANISO_KMAX = 0.82


# ----------------------------------------------------------------------------- hashing
def _mul32(x: torch.Tensor, c: int) -> torch.Tensor:
    """(x * c) mod 2**32 for x < 2**32 held in int64, without signed overflow."""
    lo, hi = c & 0xFFFF, c >> 16
    return (x * lo + (((x * hi) & 0xFFFF) << 16)) & _MASK32


def _hash32(x: torch.Tensor) -> torch.Tensor:
    """lowbias32 (C. Wellons) on int64 tensors holding uint32 values."""
    x = x & _MASK32
    x = x ^ (x >> 16)
    x = _mul32(x, 0x7FEB352D)
    x = x ^ (x >> 15)
    x = _mul32(x, 0x846CA68B)
    return x ^ (x >> 16)


def white_noise(i0: int, j0: int, h: int, w: int, seed: int, stream: int, device) -> torch.Tensor:
    """Unit-variance Gaussian-ish white noise for pixel rows [i0, i0+h), cols [j0, j0+w)."""
    ii = torch.arange(i0, i0 + h, device=device, dtype=torch.int64).view(-1, 1)
    jj = torch.arange(j0, j0 + w, device=device, dtype=torch.int64).view(1, -1)
    base = _hash32(torch.tensor((seed * 0x9E3779B1 + stream * 0x85EBCA77) & _MASK32, device=device))
    key = _hash32(_hash32(ii ^ base) ^ jj)
    acc = torch.zeros((h, w), device=device, dtype=torch.float32)
    for k in range(4):
        acc = acc + _hash32(key ^ (0x27D4EB2F * (k + 1) & _MASK32)).to(torch.float32) * (1.0 / 4294967296.0)
    return (acc - 2.0) * math.sqrt(3.0)


# ----------------------------------------------------------------------------- spectra
def _angular(phi: torch.Tensor, theta: float) -> torch.Tensor:
    a = torch.cos(phi - theta) ** (2 * ANGULAR_POWER)
    return a / a.mean()


def _hann_radial(radius: int) -> torch.Tensor:
    r = torch.arange(-radius, radius + 1, dtype=torch.float64)
    rr = torch.sqrt(r.view(-1, 1) ** 2 + r.view(1, -1) ** 2)
    return torch.where(rr < radius, 0.5 * (1 + torch.cos(math.pi * rr / radius)), torch.zeros_like(rr))


@lru_cache(maxsize=1)
def _kernels_cpu() -> tuple[tuple[torch.Tensor, ...], ...]:
    """kernels[stream][band]: unit-L2 spatial kernels (2R+1)^2 for each orientation stream
    (0 = isotropic) and synth band, cut from the band mask x angular window."""
    omega, phi = D.freq_grid(KERNEL_GRID, KERNEL_GRID, "cpu")
    masks = D.band_masks(omega.double())
    out = []
    for si in range(N_STREAMS):
        ang = torch.ones_like(phi, dtype=torch.float64) if si == 0 else _angular(phi.double(), ORIENTATIONS[si - 1])
        row = []
        for o in range(SYNTH_BANDS):
            k = torch.fft.fftshift(torch.fft.ifft2(masks[o] * ang).real)
            c, rad = KERNEL_GRID // 2, KERNEL_RADIUS[o]
            k = k[c - rad : c + rad + 1, c - rad : c + rad + 1] * _hann_radial(rad)
            row.append((k / torch.sqrt((k**2).sum())).float())
        out.append(tuple(row))
    return tuple(out)


@lru_cache(maxsize=4)
def _kernels(device: str):
    return tuple(tuple(k.to(device) for k in row) for row in _kernels_cpu())


@lru_cache(maxsize=1)
def _band_gain_matrix() -> torch.Tensor:
    """G[m, o]: energy the extractor measures in band m per unit variance put in band o,
    from the actual (windowed) isotropic kernel spectra: mean(M_m^2 |K_o|^2) / mean(|K_o|^2)."""
    n = 1024
    omega, _ = D.freq_grid(n, n, "cpu")
    m = D.band_masks(omega)
    g = torch.zeros(D.N_BANDS, SYNTH_BANDS)
    for o in range(SYNTH_BANDS):
        k = _kernels_cpu()[0][o]
        kp = torch.zeros(n, n)
        kp[: k.shape[0], : k.shape[1]] = k
        p = torch.fft.fft2(kp).abs() ** 2
        for q in range(D.N_BANDS):
            g[q, o] = (m[q] ** 2 * p).mean() / p.mean()
    return g


def _conv_valid_fft(x: torch.Tensor, k: torch.Tensor) -> torch.Tensor:
    """Linear 'valid' correlation of (H, W) with a (2R+1)^2 kernel via FFT -> (H-2R, W-2R)."""
    h, w = x.shape
    kh, kw = k.shape
    fh, fw = h + kh - 1, w + kw - 1
    y = torch.fft.irfft2(torch.fft.rfft2(x, s=(fh, fw)) * torch.fft.rfft2(torch.flip(k, (0, 1)), s=(fh, fw)), s=(fh, fw))
    return y[kh - 1 : h, kw - 1 : w]


def _solve_energies(target: torch.Tensor) -> torch.Tensor:
    """Input energies (N, 4, H, W) that make the extractor measure `target` (same shape)."""
    g = _band_gain_matrix()[:SYNTH_BANDS].to(target.device)
    e = torch.einsum("oq,nqhw->nohw", torch.linalg.inv(g), target)
    return e.clamp_min(0.0)


def _skew_of(a: torch.Tensor) -> torch.Tensor:
    return (6 * a + 8 * a**3) / (1 + 2 * a**2) ** 1.5


def _invert_skew(target: torch.Tensor) -> torch.Tensor:
    """a such that skew(a) == target (|target| clamped to 2.5; Newton on the monotone branch)."""
    t = target.clamp(-2.5, 2.5)
    a = t / 6.0
    for _ in range(12):
        f = _skew_of(a) - t
        df = (_skew_of(a + 1e-3) - _skew_of(a - 1e-3)) / 2e-3
        a = (a - f / df.clamp_min(1e-3)).clamp(-2.0, 2.0)
    return a


def _orientation_weights(c: torch.Tensor, s: torch.Tensor) -> list[torch.Tensor]:
    """Variance weights [iso, o_0..o_7] whose summed structure tensor matches (c, s).

    In doubled-angle space the bank vectors sit every 45 deg; the target vector
    t = (c, s) / ANISO_KMAX is split onto its two neighbours with non-negative weights.
    """
    t = torch.sqrt(c**2 + s**2) / ANISO_KMAX
    ang = torch.atan2(s, c) % (2 * math.pi)  # doubled angle
    step = 2 * math.pi / N_ORIENT
    lo = torch.floor(ang / step).long() % N_ORIENT
    alpha = ang - lo.float() * step
    a = t * torch.sin(step - alpha) / math.sin(step)
    b = t * torch.sin(alpha) / math.sin(step)
    total = (a + b).clamp_min(1e-9)
    scale = torch.where(total > 1.0, 1.0 / total, torch.ones_like(total))
    a, b = a * scale, b * scale
    ws = [torch.zeros_like(c) for _ in range(N_ORIENT)]
    for i in range(N_ORIENT):
        ws[i] = torch.where(lo == i, a, ws[i]) + torch.where((lo + 1) % N_ORIENT == i, b, torch.zeros_like(b))
    iso = (1.0 - sum(ws)).clamp_min(0.0)
    return [iso] + ws


# ----------------------------------------------------------------------------- synth
def _up(x: torch.Tensor, h_px: int, w_px: int) -> torch.Tensor:
    return F.interpolate(x, size=(h_px, w_px), mode="bilinear", align_corners=False)


def coarse_surface(coarse: torch.Tensor) -> torch.Tensor:
    """Smooth pixel surface from cell means: bilinear upsample + Gaussian low-pass.

    Shared by the synth, refiner training and the server so all three see the identical
    base. Values within ~3 * BASE_LOWPASS_SIGMA px of the block edge see reflected context:
    callers pass a margin and crop it."""
    hc, wc = coarse.shape[-2:]
    return D.blur(_up(coarse, hc * D.CELL_PX, wc * D.CELL_PX), BASE_LOWPASS_SIGMA)


def synth(
    coarse: torch.Tensor,
    desc: torch.Tensor,
    origin_px: tuple[int, int],
    seed: int,
    river: torch.Tensor | None = None,
    river_depth_m: float = 12.0,
    amp_scale: float = 1.0,
) -> torch.Tensor:
    """Heights for the interior of the given cell block.

    Args:
        coarse: (1, 1, Hc, Wc) cell-mean elevation (metres), INCLUDING MARGIN_CELLS of
            margin on every side.
        desc:   (1, N_CHANNELS, Hc, Wc) descriptors, same extent.
        origin_px: absolute (row, col) pixel of the *interior's* top-left corner.
        seed: world seed.
        river: optional (1, 1, Hc, Wc) river probability in [0, 1], same extent.
        amp_scale: multiplies all synthesised amplitudes (a "fantasy" knob).
    Returns:
        (1, 1, (Hc - 2m) * 8, (Wc - 2m) * 8) metres, m = MARGIN_CELLS.
    """
    assert coarse.shape[0] == 1 and desc.shape[0] == 1
    dev = coarse.device
    m, cp = MARGIN_CELLS, D.CELL_PX
    hc, wc = coarse.shape[-2:]
    hp, wp = hc * cp, wc * cp  # padded pixel extent
    halo = m * cp
    i0, j0 = origin_px[0] - halo, origin_px[1] - halo

    # Coarse field, Gaussian low-passed (bands 0..4 come from noise).
    base = coarse_surface(coarse)

    target = ((torch.exp(desc[:, :SYNTH_BANDS]) - D.AMP_EPS).clamp_min(0.0) * amp_scale) ** 2
    amp_px = _up(torch.sqrt(_solve_energies(target)), hp, wp)  # (1, 4, hp, wp)

    weights = _orientation_weights(_up(desc[:, 6:7], hp, wp)[0, 0], _up(desc[:, 7:8], hp, wp)[0, 0])
    kernels = _kernels(str(dev))
    band_px = torch.zeros((1, SYNTH_BANDS, hp, wp), device=dev)
    # One independent white-noise stream per (orientation, band): bands sharing a stream
    # would correlate where their masks overlap and inflate the measured energy. Each
    # stream is generated R px beyond the padded block, so the valid convolution covers it.
    for si in range(N_STREAMS):
        sw = torch.sqrt(weights[si])
        for o in range(SYNTH_BANDS):
            r = KERNEL_RADIUS[o]
            wn = white_noise(i0 - r, j0 - r, hp + 2 * r, wp + 2 * r, seed, si * SYNTH_BANDS + o, dev)
            band_px[0, o] += sw * _conv_valid_fft(wn, kernels[si][o])
    band_px = band_px * amp_px

    # Ridge: skew transform on the normalised mid band (bands 1..3; band 4 stays Gaussian).
    mid = band_px[:, 1:4].sum(dim=1, keepdim=True)
    mid_std = torch.sqrt((amp_px[:, 1:4] ** 2).sum(dim=1, keepdim=True)).clamp_min(1e-4)
    a = _invert_skew(_up(desc[:, 5:6], hp, wp) / RIDGE_GAIN)
    u = mid / mid_std
    mid = mid_std * (u + a * (u**2 - 1.0)) / torch.sqrt(1.0 + 2.0 * a**2)

    detail = band_px[:, 0:1] + mid + band_px[:, 4:5]
    z = base + detail
    if river is not None:
        r = D.blur(_up(river, hp, wp).clamp(0, 1), 3.0)
        z = z - river_depth_m * r - 0.7 * r * detail
    return z[..., halo:-halo, halo:-halo]
