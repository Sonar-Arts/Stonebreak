"""Tiny numpy voxel renderer (orthographic ray march, blocky faces, Lambert + ambient) for previews and sheets."""
from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw


def render_voxels(occ: np.ndarray, size: int = 160, yaw: float = 35.0, pitch: float = 28.0,
                  color=(150, 146, 138), hint: np.ndarray | None = None, bg=(236, 236, 232)) -> Image.Image:
    """occ[x, y, z] (y up) -> RGB image. `hint` (same shape, 1 moss, 2 rough) tints faces."""
    occ = np.asarray(occ, bool)
    dims = np.array(occ.shape, float)
    c = dims / 2
    rad = float(np.linalg.norm(dims)) / 2 + 0.5
    yw, pt = np.radians(yaw), np.radians(pitch)
    fwd = -np.array([np.cos(pt) * np.cos(yw), np.sin(pt), np.cos(pt) * np.sin(yw)])  # camera looks along fwd
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    u = np.linspace(-rad, rad, size)
    U, Vv = np.meshgrid(u, -u, indexing="xy")
    origin = c[None, None] + U[..., None] * right + Vv[..., None] * up - fwd * (rad + 1)
    steps = int(2 * (rad + 1) / 0.25)
    t = np.arange(steps) * 0.25
    hit = np.full((size, size), -1, np.int64)
    normal = np.zeros((size, size, 3))
    cell_hit = np.zeros((size, size, 3), np.int64)
    prev = np.floor(origin).astype(np.int64)
    active = np.ones((size, size), bool)
    for k in range(steps):
        p = origin + fwd * t[k]
        cell = np.floor(p).astype(np.int64)
        inside = np.all((cell >= 0) & (cell < dims.astype(int)), -1)
        cc = np.clip(cell, 0, dims.astype(int) - 1)
        occv = inside & occ[cc[..., 0], cc[..., 1], cc[..., 2]]
        new = active & occv
        if new.any():
            d = cell[new] - prev[new]
            ax = np.argmax(np.abs(d), -1)
            nrm = np.zeros((new.sum(), 3))
            nrm[np.arange(len(ax)), ax] = -np.sign(d[np.arange(len(ax)), ax])
            normal[new] = nrm
            cell_hit[new] = cc[new]
            hit[new] = k
            active &= ~new
        prev = cell
        if not active.any():
            break
    light = np.array([0.45, 0.8, 0.35])
    light /= np.linalg.norm(light)
    lam = np.clip((normal * light).sum(-1), 0, 1)
    shade = 0.42 + 0.58 * lam
    base = np.broadcast_to(np.array(color, float), (size, size, 3)).copy()
    if hint is not None:
        hv = hint[cell_hit[..., 0], cell_hit[..., 1], cell_hit[..., 2]]
        base[hv == 1] = (104, 128, 82)
        base[hv == 2] = (118, 112, 106)
    img = base * shade[..., None]
    out = np.where((hit >= 0)[..., None], img, np.array(bg, float))
    return Image.fromarray(out.clip(0, 255).astype(np.uint8))


def sheet(images: list[Image.Image], labels: list[str] | None = None, cols: int = 8, pad: int = 4) -> Image.Image:
    w, h = images[0].size
    rows = (len(images) + cols - 1) // cols
    lab_h = 12 if labels else 0
    out = Image.new("RGB", (cols * (w + pad) + pad, rows * (h + lab_h + pad) + pad), (255, 255, 255))
    d = ImageDraw.Draw(out)
    for i, im in enumerate(images):
        x, y = pad + (i % cols) * (w + pad), pad + (i // cols) * (h + lab_h + pad)
        out.paste(im, (x, y))
        if labels:
            d.text((x + 2, y + h), labels[i][:30], fill=(0, 0, 0))
    return out
