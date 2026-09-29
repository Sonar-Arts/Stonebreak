"""`generator.procedural_controls` as one Triton kernel, bit-identical to the torch version.

The torch version is ~40 hash-noise evaluations of ~50 elementwise ops each: ~2000 kernel
launches of tiny tensors, ~28 ms per call, called for every relief, planner, hydro and detail
window. Everything in it is pointwise in the cell coordinates, so one program computes a block of
cells end to end in registers and writes the six fields once.

Bit-exactness (the fields feed the relief sampler, the planner and the discrete drainage router,
where one ulp can move a river): the kernel repeats the torch evaluation op for op in fp32 --
every multiply / add through libdevice's rounded intrinsics (never fused, never packed: see `_m`),
libdevice `exp` (= CUDA `expf`, as torch.exp) and IEEE `div_rn` (Triton's `/` is approximate),
denormals kept (no FTZ, like torch), `tensor / scalar` as multiplication by the fp32 reciprocal (torch's CUDA division by a CPU
scalar), softmax as exp(x - max) / sequential sum, and the 7-way archetype blend summed in torch's
4-accumulator reduction order. tests/test_kernels.py pins equality.
"""
from __future__ import annotations

import math

import numpy as np
import torch
import triton
import triton.language as tl
from triton.language.extra import libdevice

from terrain_slm.synth import noise as S

_MASK32 = 0xFFFFFFFF
FIELDS = ("wild", "trend", "t0", "tseason", "precip", "pcv")

# fbm table rows (period cells, stream, octaves) in the order the kernel reads them.
_FBM = (
    ("continent", 900.0, 1, 4),
    ("warp_i", 400.0, 4, 2),
    ("warp_j", 400.0, 5, 2),
    ("range", 560.0, 2, 2),
    ("spur", 130.0, 6, 2),
    ("along", 900.0, 7, 2),
    ("hills", 70.0, 8, 4),
    ("hilly", 350.0, 9, 3),
) + tuple((f"climate{k}", 250.0, 20 + k, 2) for k in range(7))


def _f32(x: float) -> float:
    return float(np.float32(x))


def _inv(x: float) -> float:
    """torch's `tensor / scalar` on CUDA: multiply by float32(1) / float32(scalar)."""
    return float(np.float32(1.0) / np.float32(x))


def _check_constants(G) -> None:
    """The kernel bakes the generator's constants; fail loudly if they drift apart."""
    expect = dict(RANGE_PERIOD_CELLS=560.0, RANGE_WARP_CELLS=60.0, RANGE_SPINE_WIDTH=0.5, RANGE_ALONG_PERIOD=900.0,
                  RANGE_ALONG_BIAS=0.2, RANGE_SPINE_M=2800.0, RANGE_SPUR_M=900.0, RANGE_FOOTHILL_M=500.0,
                  _FBM2_STD=0.27, _FBM3_STD=0.30, HILL_PERIOD_CELLS=350.0, HILL_BIAS=0.8, HILL_SOFTNESS=2.4,
                  HILL_PLAINS_M=100.0, HILL_COUNTRY_M=360.0, HILL_HOME_MIN=0.55, HILL_ROUGH=0.55,
                  HOME_RADIUS_CELLS=150.0, CLIMATE_ZONE_CELLS=250.0, CLIMATE_SHARPNESS=10.0)
    bad = {k: getattr(G, k) for k, v in expect.items() if getattr(G, k) != v}
    if bad or len(G.CLIMATE_ARCHETYPES) != 7:
        raise RuntimeError(f"kernels/controls.py is out of date with generator constants: {bad}")


@triton.jit
def _hash32(x):
    x = x ^ (x >> 16)
    x = x * 0x7FEB352D
    x = x ^ (x >> 15)
    x = x * 0x846CA68B
    return x ^ (x >> 16)


# fp32 arithmetic through libdevice's explicitly rounded intrinsics. Plain `a * b + c` is not
# safe: LLVM's O3 pairs independent lanes into Blackwell `mul/add.rn.f32x2`, and the bundled
# ptxas (12.9) fuses those pairs into FFMA even under --fmad=false (seen in the SASS), so results
# drift by an ulp depending on how a given specialisation got vectorised. The intrinsics are
# opaque to the vectorizer and always compile to one correctly rounded scalar op.
@triton.jit
def _m(a, b):
    return libdevice.mul_rn(a, b)


@triton.jit
def _a(a, b):
    return libdevice.add_rn(a, b)


@triton.jit
def _s(a, b):
    return libdevice.sub_rn(a, b)


@triton.jit
def _corner(xi, yi, base, dx: tl.constexpr, dy: tl.constexpr):
    h = _hash32(_hash32((xi + dx) ^ base) ^ (yi + dy))
    return _s(_m(h.to(tl.float32), 4.656612873077393e-10), 1.0)   # / 2**31 (exact), - 1


@triton.jit
def _quintic(f):
    # f * f * f * (f * (f * 6 - 15) + 10), evaluated left to right like torch
    return _m(_m(_m(f, f), f), _a(_m(f, _s(_m(f, 6.0), 15.0)), 10.0))


@triton.jit
def _vnoise(ci, cj, inv_period, base):
    x = _m(ci, inv_period)
    y = _m(cj, inv_period)
    x0 = tl.floor(x)
    y0 = tl.floor(y)
    ux = _quintic(_s(x, x0))
    uy = _quintic(_s(y, y0))
    xi = x0.to(tl.int32).to(tl.uint32, bitcast=True)
    yi = y0.to(tl.int32).to(tl.uint32, bitcast=True)
    a = _corner(xi, yi, base, 0, 0)
    b = _corner(xi, yi, base, 0, 1)
    c = _corner(xi, yi, base, 1, 0)
    d = _corner(xi, yi, base, 1, 1)
    vy = _s(1.0, uy)
    # (a * (1 - uy) + b * uy) * (1 - ux) + (c * (1 - uy) + d * uy) * ux
    return _a(_m(_a(_m(a, vy), _m(b, uy)), _s(1.0, ux)), _m(_a(_m(c, vy), _m(d, uy)), ux))


@triton.jit
def _octave(ci, cj, bases, invp, k: tl.constexpr):
    return _vnoise(ci, cj, tl.load(invp + k), tl.load(bases + k).to(tl.uint32, bitcast=True))


@triton.jit
def _fbm(ci, cj, bases, invp, row: tl.constexpr, OCT: tl.constexpr, INV_NORM: tl.constexpr):
    tot = _octave(ci, cj, bases, invp, row)
    if OCT > 1:
        tot = _a(tot, _m(0.5, _octave(ci, cj, bases, invp, row + 1)))
    if OCT > 2:
        tot = _a(tot, _m(0.25, _octave(ci, cj, bases, invp, row + 2)))
    if OCT > 3:
        tot = _a(tot, _m(0.125, _octave(ci, cj, bases, invp, row + 3)))
    return _m(tot, INV_NORM)


@triton.jit
def _smoothstep(x):
    x = tl.minimum(tl.maximum(x, 0.0), 1.0)
    return _m(_m(x, x), _s(3.0, _m(2.0, x)))


@triton.jit
def _gauss(x, inv_width):
    y = _m(x, inv_width)
    return libdevice.exp(-_m(y, y))


@triton.jit
def _controls_kernel(out, bases, invp, arch, ci0, cj0, hc, wc,
                     R_CONT: tl.constexpr, R_WI: tl.constexpr, R_WJ: tl.constexpr, R_RANGE: tl.constexpr,
                     R_SPUR: tl.constexpr, R_ALONG: tl.constexpr, R_HILLS: tl.constexpr, R_HILLY: tl.constexpr,
                     R_CLIM: tl.constexpr,
                     INV2: tl.constexpr, INV3: tl.constexpr, INV4: tl.constexpr, INV_HOME: tl.constexpr,
                     INV_LAND: tl.constexpr, INV_STD2: tl.constexpr, INV_STD3: tl.constexpr, INV_SPINE: tl.constexpr,
                     INV_FOOT: tl.constexpr, INV_BODY: tl.constexpr, INV_SPUR: tl.constexpr, INV_ALONG: tl.constexpr,
                     INV_HILLY: tl.constexpr, WILD_K: tl.constexpr, WILD_0: tl.constexpr,
                     BLOCK: tl.constexpr):
    offs = tl.program_id(0) * BLOCK + tl.arange(0, BLOCK)
    n = hc * wc
    m = offs < n
    ci = (ci0 + offs // wc).to(tl.float32)
    cj = (cj0 + offs % wc).to(tl.float32)

    home = libdevice.exp(_m(-_a(_m(ci, ci), _m(cj, cj)), INV_HOME))
    continent = _fbm(ci, cj, bases, invp, R_CONT, 4, INV4)
    continent = _a(continent, _m(0.7, home))
    land = _smoothstep(_m(_a(continent, 0.35), INV_LAND))
    wi = _a(ci, _m(60.0, _fbm(ci, cj, bases, invp, R_WI, 2, INV2)))
    wj = _a(cj, _m(60.0, _fbm(ci, cj, bases, invp, R_WJ, 2, INV2)))
    n_range = _m(_fbm(wi, wj, bases, invp, R_RANGE, 2, INV2), INV_STD2)
    n_spur = _m(_fbm(wi, wj, bases, invp, R_SPUR, 2, INV2), INV_STD2)
    spine = _gauss(n_range, INV_SPINE)
    foot = _gauss(n_range, INV_FOOT)
    body = _gauss(n_range, INV_BODY)
    spur = _m(_gauss(n_spur, INV_SPUR), body)
    along = _smoothstep(_m(_a(_m(_fbm(ci, cj, bases, invp, R_ALONG, 2, INV2), INV_STD2), 0.2), INV_ALONG))
    hills = _fbm(ci, cj, bases, invp, R_HILLS, 4, INV4)
    hilly = _smoothstep(_m(_a(_m(_fbm(ci, cj, bases, invp, R_HILLY, 3, INV3), INV_STD3), 0.8), INV_HILLY))
    hilly = tl.maximum(hilly, _m(0.55, home))
    # along * (2800 spine + 900 spur + 500 foot)
    mountains = _m(along, _a(_a(_m(2800.0, spine), _m(900.0, spur)), _m(500.0, foot)))
    # 100 + 260 hilly + 200 along foot
    hill_m = _a(_a(100.0, _m(260.0, hilly)), _m(_m(200.0, along), foot))
    # -300 + 600 land (0.6 + 0.5 continent) + land (mountains + hill_m hills)
    trend = _a(_a(-300.0, _m(_m(600.0, land), _a(0.6, _m(0.5, continent)))),
               _m(land, _a(mountains, _m(hill_m, hills))))
    # (0.14 + 0.55 hilly + along (0.9 spine + 0.45 spur + 0.25 foot) + 0.25 (0.3 + along foot) |hills|).clamp(0, 1) land
    rough = _a(_a(_a(0.14, _m(0.55, hilly)), _m(along, _a(_a(_m(0.9, spine), _m(0.45, spur)), _m(0.25, foot)))),
               _m(_m(0.25, _a(0.3, _m(along, foot))), tl.abs(hills)))
    rough = _m(tl.minimum(tl.maximum(rough, 0.0), 1.0), land)
    wild = _a(WILD_0, _m(rough, WILD_K))

    # Climate archetypes: softmax over 7 sharpened scores, then blends summed in torch's order.
    s0 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 0, 2, INV2))
    s1 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 2, 2, INV2))
    s2 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 4, 2, INV2))
    s3 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 6, 2, INV2))
    s4 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 8, 2, INV2))
    s5 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 10, 2, INV2))
    s6 = _m(10.0, _fbm(ci, cj, bases, invp, R_CLIM + 12, 2, INV2))
    mx = tl.maximum(tl.maximum(tl.maximum(s0, s1), tl.maximum(s2, s3)), tl.maximum(tl.maximum(s4, s5), s6))
    e0 = libdevice.exp(_s(s0, mx))
    e1 = libdevice.exp(_s(s1, mx))
    e2 = libdevice.exp(_s(s2, mx))
    e3 = libdevice.exp(_s(s3, mx))
    e4 = libdevice.exp(_s(s4, mx))
    e5 = libdevice.exp(_s(s5, mx))
    e6 = libdevice.exp(_s(s6, mx))
    tot = _a(_a(_a(_a(_a(_a(e0, e1), e2), e3), e4), e5), e6)
    w0 = libdevice.div_rn(e0, tot)
    w1 = libdevice.div_rn(e1, tot)
    w2 = libdevice.div_rn(e2, tot)
    w3 = libdevice.div_rn(e3, tot)
    w4 = libdevice.div_rn(e4, tot)
    w5 = libdevice.div_rn(e5, tot)
    w6 = libdevice.div_rn(e6, tot)
    base = out + offs
    tl.store(base, wild, mask=m)
    tl.store(base + n, trend, mask=m)
    for col in tl.static_range(4):
        a0 = _m(w0, tl.load(arch + 0 * 4 + col))
        a1 = _m(w1, tl.load(arch + 1 * 4 + col))
        a2 = _m(w2, tl.load(arch + 2 * 4 + col))
        a3 = _m(w3, tl.load(arch + 3 * 4 + col))
        a4 = _m(w4, tl.load(arch + 4 * 4 + col))
        a5 = _m(w5, tl.load(arch + 5 * 4 + col))
        a6 = _m(w6, tl.load(arch + 6 * 4 + col))
        v = _a(_a(_a(_a(a0, a4), _a(a1, a5)), _a(a2, a6)), a3)
        if col == 2:
            v = libdevice.exp(v)   # precipitation is blended in log space
        tl.store(base + (2 + col) * n, v, mask=m)


_TABLES: dict = {}


def _tables(seed: int, device) -> tuple[torch.Tensor, torch.Tensor, dict, torch.Tensor]:
    key = (seed, str(device))
    if key not in _TABLES:
        from terrain_slm.world import generator as G
        _check_constants(G)
        bases, invp, rows = [], [], {}
        for name, period, stream, octaves in _FBM:
            rows[name] = len(bases)
            for o in range(octaves):
                s = stream * 16 + o
                b = S._hash32(torch.tensor((seed * 0x9E3779B1 + s * 0x632BE5AB) & _MASK32))
                bases.append(int(b))
                invp.append(_inv(period / 2**o))
        arch = torch.tensor([a[1:] for a in G.CLIMATE_ARCHETYPES], dtype=torch.float32, device=device)
        arch[:, 2] = torch.log(arch[:, 2])                       # on device: CUDA logf, as the torch path
        bt = torch.tensor(np.array(bases, dtype=np.uint32).view(np.int32), device=device)   # uint32 bits
        _TABLES[key] = (bt, torch.tensor(invp, dtype=torch.float32, device=device), rows, arch.contiguous())
        if len(_TABLES) > 64:
            _TABLES.pop(next(iter(_TABLES)))
    return _TABLES[key]


def procedural_controls(ci0: int, cj0: int, hc: int, wc: int, seed: int, device) -> dict:
    """Same contract and values as `generator.procedural_controls` (CUDA only)."""
    bases, invp, rows, arch = _tables(seed, device)
    out = torch.empty(len(FIELDS), hc, wc, device=device, dtype=torch.float32)
    # One cell per thread (BLOCK = 32 * num_warps): with two, the compiler pairs lanes into Blackwell
    # f32x2 ops for some argument specialisations and the result stops matching torch (and itself).
    block = 128
    with torch.cuda.device(out.device):   # Triton launches on the current device
        _launch(out, bases, invp, arch, ci0, cj0, hc, wc, rows, block)
    return {name: out[k] for k, name in enumerate(FIELDS)}


def _launch(out, bases, invp, arch, ci0, cj0, hc, wc, rows, block):
    _controls_kernel[(triton.cdiv(hc * wc, block),)](
        out, bases, invp, arch, ci0, cj0, hc, wc,
        R_CONT=rows["continent"], R_WI=rows["warp_i"], R_WJ=rows["warp_j"], R_RANGE=rows["range"],
        R_SPUR=rows["spur"], R_ALONG=rows["along"], R_HILLS=rows["hills"], R_HILLY=rows["hilly"],
        R_CLIM=rows["climate0"],
        INV2=_inv(1.5), INV3=_inv(1.75), INV4=_inv(1.875), INV_HOME=_inv(2 * 150.0**2),
        INV_LAND=_inv(0.2), INV_STD2=_inv(0.27), INV_STD3=_inv(0.30), INV_SPINE=_inv(0.5),
        INV_FOOT=_inv(2.4 * 0.5), INV_BODY=_inv(1.6 * 0.5), INV_SPUR=_inv(0.6), INV_ALONG=_inv(1.2),
        INV_HILLY=_inv(2.4), WILD_K=_f32(math.log(40.0) - math.log(2.0)), WILD_0=_f32(math.log(2.0)),
        BLOCK=block, num_warps=4, enable_fp_fusion=False, enable_reflect_ftz=False,
    )
