"""Train the detail sampler (models/detail.py): flow matching on real 60 m terrain.

Regions stream in: every --refresh-every steps the trainer loads any region under --data-dir
whose build finished since the last look (cells.npz + meta.json present), so a long run can
start while new data is still downloading. Normalisation constants are fixed at startup (and
saved in the checkpoint), so late regions never shift them. The validation set is fixed at
startup too (the val tiles of the regions present then), so the curve stays comparable.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import detail as DT
from terrain_slm.models import planner as P
from terrain_slm.train.planner_data import _Region, built_regions
from terrain_slm.train.train_refiner import fm_loss


def fm_loss_cover(model, cond, x1, p_uniform: float, p_early: float, gen=None):
    """fm_loss with t drawn from a mixture: logit-normal (the base recipe), plus p_uniform of the batch
    uniform on [0, 1] and p_early uniform on [0, 0.1]. Logit-normal alone puts ~0.2% of samples below
    t = 0.05, where every sample starts -- the final r0 checkpoint blew up there on ~1.7% of in-game windows."""
    b = x1.shape[0]
    x0 = torch.randn(x1.shape, device=x1.device, generator=gen)
    t = torch.sigmoid(torch.randn(b, device=x1.device, generator=gen))
    u = torch.rand(b, device=x1.device, generator=gen)
    t = torch.where(u < p_uniform, torch.rand(b, device=x1.device, generator=gen), t)
    t = torch.where((u >= p_uniform) & (u < p_uniform + p_early), 0.1 * torch.rand(b, device=x1.device, generator=gen), t)
    xt = (1 - t.view(-1, 1, 1, 1)) * x0 + t.view(-1, 1, 1, 1) * x1
    with torch.autocast("cuda", dtype=torch.bfloat16):
        v = model(xt, t, cond)
    return F.mse_loss(v.float(), x1 - x0)

CROP_CELLS = 64          # 256 samples = 15 km
MARGIN_CELLS = 8         # base blur (<= 2 cells) + detail_base blur (1.5 cells) + wildness blur
SPC = DT.SAMPLES_PER_CELL


class DetailRegion:
    def __init__(self, index: int, root: Path, device: str):
        self.cells = _Region(index, root, device, crop=CROP_CELLS + 2 * MARGIN_CELLS)
        self.name = self.cells.name
        dem = np.load(root / "dem.npy", mmap_mode="r")
        h, w = dem.shape[0] // D.CELL_PX * D.CELL_PX, dem.shape[1] // D.CELL_PX * D.CELL_PX
        out = torch.empty(h // DT.DS, w // DT.DS, device=device)
        for r0 in range(0, h, 4096):  # 2x2 mean through the GPU in row blocks
            blk = torch.from_numpy(np.ascontiguousarray(dem[r0 : min(h, r0 + 4096), :w])).to(device)
            out[r0 // DT.DS : (r0 + blk.shape[0]) // DT.DS] = F.avg_pool2d(blk[None, None], DT.DS)[0, 0]
        self.dem60 = out
        del dem


class DetailData:
    def __init__(self, data_dir: Path, device: str, seed: int = 0, max_val_per_region: int = 12):
        self.data_dir, self.device = data_dir, device
        self.gen = torch.Generator(device="cpu").manual_seed(seed)
        self.regions: list[DetailRegion] = []
        self.refresh()
        # Fixed normalisation over the startup regions' training cells.
        parts = []
        for reg in self.regions:
            d = reg.cells.desc[:, reg.cells.train_mask]
            parts.append(d[:, torch.randperm(d.shape[1], device=d.device)[:1_000_000]])
        d = torch.cat(parts, dim=1)
        d[5] = d[5].clamp(-3, 3)
        self.desc_mean = d.mean(dim=1)
        self.desc_std = d.std(dim=1).clamp_min(1e-3)
        # Fixed validation set: the startup regions' val tiles.
        n = CROP_CELLS + 2 * MARGIN_CELLS
        self.val_corners = []
        for reg in self.regions:
            got = [(reg.cells.index, r + dr, c + dc) for r, c, h, w in reg.cells.val_boxes
                   for dr in range(0, h - n + 1, n) for dc in range(0, w - n + 1, n)]
            step = max(1, len(got) // max_val_per_region)
            self.val_corners += got[::step][:max_val_per_region]
        self.val_regions = [reg.name for reg in self.regions]

    def refresh(self) -> list[str]:
        have = {r.name for r in self.regions}
        added = []
        for root in built_regions(self.data_dir):
            name = json.loads((root / "meta.json").read_text()).get("region", root.name)
            if name in have:
                continue
            self.regions.append(DetailRegion(len(self.regions), root, self.device))
            added.append(name)
        return added

    def _u(self, lo, hi):
        return float(torch.empty(1).uniform_(lo, hi, generator=self.gen))

    def sample_corners(self, batch: int):
        out = []
        for _ in range(batch):
            reg = self.regions[int(torch.randint(0, len(self.regions), (1,), generator=self.gen))].cells
            k = int(torch.randint(0, len(reg.train_corners), (1,), generator=self.gen))
            r, c = reg.train_corners[k].tolist()
            out.append((reg.index, r, c))
        return out

    @staticmethod
    def _dihedral(x, ops):
        tr, fr, fc = ops
        if tr:
            x = x.transpose(-1, -2)
        if fr:
            x = x.flip(-2)
        if fc:
            x = x.flip(-1)
        return x

    def assemble(self, corners, train: bool):
        m, s = MARGIN_CELLS, CROP_CELLS
        n = s + 2 * m
        cell_f, dems = [], []
        for ri, r, c in corners:
            reg = self.regions[ri]
            rc = reg.cells
            f = torch.cat([
                rc.coarse[None, r : r + n, c : c + n],
                rc.desc[:, r : r + n, c : c + n],
                rc.climate[:, r : r + n, c : c + n],
                torch.log1p(rc.acc[None, r : r + n, c : c + n]) / P.LOGACC_SCALE,
            ])  # (14, n, n)
            dem = reg.dem60[None, (r + m) * SPC : (r + m + s) * SPC, (c + m) * SPC : (c + m + s) * SPC]
            if train:
                ops = (torch.randint(0, 2, (3,), generator=self.gen) == 1).tolist()
                f, dem = self._dihedral(f, ops).clone(), self._dihedral(dem, ops)
                if ops[0]:
                    f[7] = -f[7]  # desc 6 (aniso_c) negates under transpose
                if ops[1] ^ ops[2]:
                    f[8] = -f[8]  # desc 7 (aniso_s) negates under one flip
            cell_f.append(f)
            dems.append(dem)
        f = torch.stack(cell_f)
        dem = torch.stack(dems).contiguous()
        b = f.shape[0]
        coarse = f[:, 0:1]
        # What the planner hands over: a smoothed, slightly wrong coarse height.
        if train:
            deg = torch.empty_like(coarse)
            for i in range(b):
                g = D.blur(coarse[i : i + 1], self._u(0.5, 2.0))
                jit = D.blur(torch.randn(g.shape, generator=self.gen).to(g.device), 6.0)
                jit = jit / jit.std().clamp_min(1e-6) * self._u(0.0, 20.0)
                deg[i : i + 1] = g + jit
        else:
            deg = D.blur(coarse, 1.0)
        ms = m * SPC
        crop = lambda x: x[..., ms : ms + s * SPC, ms : ms + s * SPC]
        base = crop(DT.detail_base(deg))
        desc = f[:, 1:9].clone()
        desc[:, 5] = desc[:, 5].clamp(-3, 3)
        desc = (desc - self.desc_mean.view(1, -1, 1, 1)) / self.desc_std.view(1, -1, 1, 1)
        la = D.blur(f[:, 1 + P.WILD_BAND : 2 + P.WILD_BAND], self._u(3.0, 8.0) if train else 5.0)
        if train:
            la = la + 0.15 * torch.randn(b, 1, 1, 1, generator=self.gen).to(la.device)
        wild = (la - P.WILD_NORM[0]) / P.WILD_NORM[1]
        climate = torch.cat([P.climate_input(nm, f[:, 9 + k : 10 + k]) for k, nm in enumerate(P.CLIMATE_NAMES)], dim=1)
        logacc = f[:, 13:14]
        river = (logacc >= math.log1p(P.RIVER_MIN_UPSLOPE_CELLS) / P.LOGACC_SCALE).float()
        river = D.blur(river, 0.7)
        if train:
            has = lambda p: (torch.rand(b, 1, 1, 1, generator=self.gen) < p).float().to(f.device)
            hd, hw, hc, hf = has(0.6), has(0.85), has(0.85), has(0.7)
        else:
            hd = torch.zeros(b, 1, 1, 1, device=f.device)  # val = the no-descriptor path
            hw = hc = hf = torch.ones(b, 1, 1, 1, device=f.device)
        up = lambda x: crop(DT.up_cells(x))
        cond = DT.build_cond(base, up(desc), up(wild), up(climate), up(logacc), up(river), hd, hw, hc, hf)
        x1 = DT.encode(dem - base)
        return cond.contiguous(memory_format=torch.channels_last), x1, base, dem

    def train_batch(self, batch):
        return self.assemble(self.sample_corners(batch), train=True)

    def val_batches(self, batch):
        for i in range(0, len(self.val_corners), batch):
            yield self.assemble(self.val_corners[i : i + batch], train=False)


@torch.no_grad()
def evaluate(model, data: DetailData, batch: int, steps: int = 16):
    """Flow-matching loss plus per-region relief ratio: std of the sampled residual (metres)
    over the real one. Well below 1 = the flat, averaged look this model exists to remove."""
    model.eval()
    g = torch.Generator(device=data.device).manual_seed(123)
    tot, n = 0.0, 0
    per = {}
    for cond, x1, base, dem in data.val_batches(batch):
        tot += fm_loss(model, cond, x1, g).item() * x1.shape[0]
        n += x1.shape[0]
        x = DT.sample(model, cond, torch.randn(x1.shape, device=x1.device, generator=g), steps=steps)
        res_s, res_r = DT.decode(x), dem - base
        per_crop = zip(res_s.flatten(1).std(1).tolist(), res_r.flatten(1).std(1).tolist())
        yield_names = [data.regions[ri].name for ri, _, _ in data.val_corners[n - x1.shape[0] : n]]
        for name, (ss, rs) in zip(yield_names, per_crop):
            a = per.setdefault(name, [0.0, 0.0])
            a[0] += ss
            a[1] += rs
    model.train()
    ratios = {k: round(v[0] / max(v[1], 1e-6), 3) for k, v in per.items()}
    return {"val_fm": tot / n, "amp_ratio": round(sum(ratios.values()) / len(ratios), 3), "amp_by_region": ratios}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--out", type=Path, default=Path("checkpoints/detail_r0"))
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--channels", type=int, nargs="+", default=list(DT.DetailConfig.channels))
    ap.add_argument("--blocks", type=int, default=2)
    ap.add_argument("--dropout", type=float, default=0.1)
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--wd", type=float, default=0.01)
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--ema", type=float, default=0.9995)
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--refresh-every", type=int, default=500)
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--probe", type=int, default=0, help="run N steps, print speed/memory, exit")
    ap.add_argument("--t-uniform", type=float, default=0.0, help="share of t drawn uniform on [0,1]")
    ap.add_argument("--t-early", type=float, default=0.0, help="share of t drawn uniform on [0,0.1]")
    ap.add_argument("--init-state", type=Path, default=None, help="resume weights/EMA/optimizer from this state.pt")
    args = ap.parse_args()
    torch.manual_seed(0)
    torch.backends.cudnn.benchmark = True
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    args.out.mkdir(parents=True, exist_ok=True)

    data = DetailData(args.data_dir, args.device)
    cfg = DT.DetailConfig(channels=tuple(args.channels), blocks=args.blocks, dropout=args.dropout)
    model = DT.Detail(cfg).to(args.device).to(memory_format=torch.channels_last)
    ema = copy.deepcopy(model).eval()
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.wd, betas=(0.9, 0.99))
    floor = 0.1
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / args.warmup) * (
        floor + (1 - floor) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps)))))
    start, best = 1, float("inf")
    state_path = args.init_state or (args.out / "state.pt")
    if (args.resume or args.init_state) and state_path.exists():
        st = torch.load(state_path, map_location=args.device, weights_only=False)
        model.load_state_dict(st["model"]); ema.load_state_dict(st["ema"])
        opt.load_state_dict(st["opt"]); sched.load_state_dict(st["sched"])
        start, best = st["step"] + 1, st["best"]
        data.desc_mean = torch.tensor(st["norms"]["desc_mean"], device=args.device)
        data.desc_std = torch.tensor(st["norms"]["desc_std"], device=args.device)
        print(f"resumed at step {start}", flush=True)
    fwd = torch.compile(model) if args.compile else model
    print(f"detail {DT.count_params(model):,} params, cfg {cfg.to_dict()}, regions {[r.name for r in data.regions]}, "
          f"{len(data.val_corners)} val crops", flush=True)
    norms = {"desc_mean": data.desc_mean.tolist(), "desc_std": data.desc_std.tolist()}
    log = open(args.out / "log.jsonl", "a")
    t0, t_mark = time.time(), time.time()
    for step in range(start, args.steps + 1):
        if step % args.refresh_every == 0:
            added = data.refresh()
            if added:
                print(f"  + regions {added} (now {len(data.regions)})", flush=True)
                log.write(json.dumps({"step": step, "added": added}) + "\n"); log.flush()
        cond, x1, *_ = data.train_batch(args.batch)
        loss = (fm_loss_cover(fwd, cond, x1, args.t_uniform, args.t_early) if args.t_uniform + args.t_early > 0
                else fm_loss(fwd, cond, x1))
        opt.zero_grad(set_to_none=True)
        loss.backward()
        gn = torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        with torch.no_grad():
            d = min(args.ema, (1 + step) / (10 + step))
            torch._foreach_lerp_(list(ema.parameters()), list(model.parameters()), 1 - d)
        if step % 50 == 0:
            torch.cuda.synchronize(args.device)
            dt = (time.time() - t_mark) / 50
            t_mark = time.time()
            print(f"step {step:6d}  loss {loss.item():.4f}  gn {gn.item():.3f}  lr {sched.get_last_lr()[0]:.2e}  "
                  f"{dt * 1000:.0f} ms/step  mem {torch.cuda.max_memory_allocated(args.device) / 2**30:.1f} GB", flush=True)
        if args.probe and step >= start + args.probe - 1:
            print("probe done", flush=True)
            return
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, "regions": len(data.regions), "min": round((time.time() - t0) / 60, 1),
                  **evaluate(ema, data, args.batch)}
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", ev, flush=True)
            ckpt = {"model": ema.state_dict(), "config": cfg.to_dict(), "norms": norms, "step": step, "eval": ev}
            torch.save(ckpt, args.out / "last.pt")
            if ev["val_fm"] < best:
                best = ev["val_fm"]
                torch.save(ckpt, args.out / "best.pt")
            torch.save({"model": model.state_dict(), "ema": ema.state_dict(), "opt": opt.state_dict(),
                        "sched": sched.state_dict(), "step": step, "best": best, "norms": norms},
                       args.out / "state.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
