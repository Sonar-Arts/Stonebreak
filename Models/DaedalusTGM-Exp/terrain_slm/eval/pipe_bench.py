"""TGMPipe speed benchmark: the REAL service as the game runs it (child process, pipes, its threads), timed.

    python -m terrain_slm.eval.pipe_bench [model_dir] [--device cuda:1] [--scale 1:4 1:2] [--tiles 16]
           [--mode burst|serial] [--seed 12345] [--origin 0,0] [--stages] [--dump out.npz] [--compare base.npz]
           [--json out.json]

Starts `python -m terrain_slm.tgmpipe` with a fresh, empty disk cache (no cache hits), sends the game's handshake
(one world per --scale, belts layout), then per scale:

  burst   every tile of a spiral around --origin requested at once, nearest first (a fresh world / a teleport):
          first-tile latency, time until all arrived, throughput
  serial  one request at a time, each after the previous answer: pure per-tile latency, no queueing

Reports the service's own per-tile build times (its log), the startup (load + warm-up) time, and the STATUS reply's
VRAM (process / torch reserved / peak). --stages turns on the service's stage profiler (tgmpipe/stageprof.py:
exclusive seconds per stage; device-synchronised, so a profiled run is slower -- compare end-to-end numbers from
unprofiled runs). --dump keeps every tile's planes; --compare reports how a run's planes differ from a dump's.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np

from terrain_slm.tgmpipe import protocol as W
from terrain_slm.world.world_config import WorldConfig

ROOT = Path(__file__).resolve().parents[2]

# The game's handshake (stonebreak-game TerrainScale.worldConfigJson): cube world [-384, 640), sea 64.
_RATES = {"1:4": (60.0, 2, (48.0, 16.0, 24.0, 38.0)), "1:2": (30.0, 1, (24.0, 8.0, 12.0, 19.0))}


def game_world(scale: str, layout: str = "belts") -> WorldConfig:
    hm, ds, r = _RATES[scale]
    return WorldConfig(world_height=640, min_y=-384, sea_level=64, horizontal_m=hm, downscale=ds, tile_size=256,
                       ocean_m_per_block=r[0], lowland_m_per_block=r[1], midland_m_per_block=r[2],
                       highland_m_per_block=r[3], lowland_top_m=600.0, highland_base_m=2000.0, shore_blend_m=60.0,
                       midland_blend_m=150.0, highland_blend_m=300.0, climate_layout=layout)


def _handshake(w: WorldConfig) -> dict:
    """The world as a handshake dict without optional fields left at their defaults, so an older service copy
    (--pkg-root snapshot) accepts it too."""
    from dataclasses import MISSING, fields
    d = w.to_dict()
    for f in fields(WorldConfig):
        if f.default is not MISSING and f.name not in ("min_y", "climate_layout") and d[f.name] == f.default:
            del d[f.name]
    return d


def spiral(n: int, origin=(0, 0)):
    from terrain_slm.eval.vram_audit import spiral as sp
    return [(x + origin[0], z + origin[1]) for x, z in sp(n)]


class Client:
    def __init__(self, args, cache_dir: Path, log_path: Path):
        env = dict(os.environ)
        env["TERRAIN_SLM_DEVICE"] = args.device
        if args.stages:
            env["TGM_STAGE_PROFILE"] = "1"
        cmd = [sys.executable, "-m", "terrain_slm.tgmpipe", args.model_path, "--device",
               "cuda" if args.device.startswith("cuda") else args.device, "--disk-cache-dir", str(cache_dir)]
        self.log_path = log_path
        self.t_spawn = time.monotonic()
        self.proc = subprocess.Popen(cmd, cwd=args.pkg_root or ROOT, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=open(log_path, "wb"))

    def send(self, data: bytes) -> None:
        self.proc.stdin.write(data)
        self.proc.stdin.flush()

    def read(self):
        f = W.read_frame(self.proc.stdout)
        if f is None:
            raise RuntimeError(f"service closed its stdout (log: {self.log_path})")
        return f

    def status(self, token: int = 7) -> dict:
        self.send(W.frame(W.STATUS, token.to_bytes(4, "little")))
        while True:
            kind, body = self.read()
            if kind == W.STATUS_REPLY:
                return json.loads(body[4:]) if body[:1] != b"{" else json.loads(body)

    def close(self) -> None:
        try:
            self.proc.stdin.close()
            self.proc.wait(timeout=60)
        except Exception:  # noqa: BLE001
            self.proc.kill()


def _built_ms(log_path: Path, since: int) -> list[float]:
    text = log_path.read_text(errors="replace")[since:]
    return [float(m) for m in re.findall(r"built in (\d+) ms", text)]


def run(args) -> dict:
    scales = args.scale
    worlds = [game_world(s) for s in scales]
    res = {"model": args.model_path, "device": args.device, "mode": args.mode, "tiles": args.tiles,
           "seed": args.seed, "scales": {}}
    planes_out = {}
    with tempfile.TemporaryDirectory(prefix="pipe_bench_") as tmp:
        c = Client(args, Path(tmp) / "cache", Path(args.log) if args.log else Path(tmp) / "service.log")
        try:
            c.send(W.hello(W.VERSION, {"worlds": [_handshake(w) for w in worlds]}))
            kind, body = c.read()
            if kind != W.READY:
                raise RuntimeError(f"no READY: {kind:#x} {body[:400]!r} (log {c.log_path})")
            res["startup_s"] = round(time.monotonic() - c.t_spawn, 2)
            res["model_id"] = json.loads(body)["model_id"]
            res["vram_ready"] = c.status()["vram"]
            req = 1
            for wi, scale in enumerate(scales):
                tiles = spiral(args.tiles, args.origin)
                log_mark = len(c.log_path.read_text(errors="replace"))
                arrived = {}
                t0 = time.monotonic()
                if args.mode == "burst":
                    ids = {}
                    for rank, (tx, tz) in enumerate(tiles):
                        ids[req] = (tx, tz)
                        c.send(W.tile(req, args.seed, tx, tz, 1, rank, wi))
                        req += 1
                    while len(arrived) < len(ids):
                        kind, body = c.read()
                        if kind == W.TILE_DATA:
                            rid, cached, i1, j1, planes = W.parse_tile_data(body)
                            arrived[ids[rid]] = (time.monotonic() - t0, planes)
                        elif kind == W.TILE_ERROR:
                            raise RuntimeError(f"tile error: {body[:400]!r}")
                else:
                    for tx, tz in tiles:
                        ts = time.monotonic()
                        c.send(W.tile(req, args.seed, tx, tz, 1, 0, wi))
                        req += 1
                        while True:
                            kind, body = c.read()
                            if kind == W.TILE_DATA:
                                planes = W.parse_tile_data(body)[4]
                                arrived[(tx, tz)] = (time.monotonic() - ts, planes)
                                break
                            if kind == W.TILE_ERROR:
                                raise RuntimeError(f"tile error: {body[:400]!r}")
                wall = time.monotonic() - t0
                st = c.status()
                built = _built_ms(c.log_path, log_mark)
                lat = sorted(v[0] for v in arrived.values())
                r = {"wall_s": round(wall, 3), "tiles_per_s": round(len(tiles) / wall, 3),
                     "first_tile_s": round(lat[0], 3),
                     "build_ms_median": round(statistics.median(built), 1) if built else None,
                     "build_ms_mean": round(statistics.fmean(built), 1) if built else None,
                     "build_ms": built, "vram": st["vram"], "failed": st.get("failed")}
                if args.mode == "serial":
                    r["latency_s_median"] = round(statistics.median(lat), 3)
                    r["latency_s_mean"] = round(statistics.fmean(lat), 3)
                time.sleep(2.5)                  # past the service's idle release (IDLE_RELEASE_S): what it keeps
                r["vram_idle"] = c.status()["vram"]
                if "stages" in st:
                    r["stages"] = st["stages"]
                    r["tile_stages"] = st.get("tile_stages", [])[-len(tiles):]
                res["scales"][scale] = r
                for (tx, tz), (_, planes) in arrived.items():
                    planes_out[f"{scale}_x{tx}_z{tz}"] = planes
        finally:
            c.close()
    if args.dump:
        np.savez_compressed(args.dump, **planes_out, _model_id=np.array(res["model_id"]))
    if args.compare:
        res["compare"] = compare(np.load(args.compare), planes_out)
    return res


def compare(base, new: dict) -> dict:
    """Per plane: share of values that differ, and for heights the |diff| distribution (blocks)."""
    names = ["height", "biome", "water", "tunnel_floor", "tunnel_roof", "flow", "carve"]
    out = {}
    for scale in sorted({k.split("_")[0] for k in new}):
        keys = [k for k in new if k.startswith(scale + "_") and k in base.files]
        if not keys:
            continue
        diff = {n: 0 for n in names}
        total = 0
        hd = []
        for k in keys:
            a, b = base[k], new[k]
            total += a[0].size
            for p, n in enumerate(names):
                diff[n] += int((a[p] != b[p]).sum())
            hd.append(np.abs(a[0].astype(np.int32) - b[0].astype(np.int32)).ravel())
        hd = np.concatenate(hd)
        out[scale] = {"tiles": len(keys), "differ_share": {n: round(v / total, 6) for n, v in diff.items()},
                      "height_abs_diff": {"mean": round(float(hd.mean()), 5), "p99": float(np.percentile(hd, 99)),
                                          "max": int(hd.max()), "share_gt1": round(float((hd > 1).mean()), 6)}}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.pipe_bench")
    ap.add_argument("model_path", nargs="?", default="checkpoints/exp2/world_exp2b")
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--scale", nargs="+", default=["1:4"], choices=sorted(_RATES))
    ap.add_argument("--tiles", type=int, default=16)
    ap.add_argument("--mode", choices=("burst", "serial"), default="burst")
    ap.add_argument("--seed", type=int, default=12345)
    ap.add_argument("--origin", default="0,0")
    ap.add_argument("--stages", action="store_true")
    ap.add_argument("--dump")
    ap.add_argument("--compare")
    ap.add_argument("--json")
    ap.add_argument("--log", help="keep the service log here")
    ap.add_argument("--pkg-root", help="run the service from another copy of the package (A/B against a snapshot)")
    args = ap.parse_args(argv)
    args.origin = tuple(int(v) for v in args.origin.split(","))
    if not Path(args.model_path).is_absolute():
        args.model_path = str(ROOT / args.model_path)
    res = run(args)
    show = {k: v for k, v in res.items() if k != "scales"}
    print(json.dumps(show, indent=1))
    for scale, r in res["scales"].items():
        print(f"--- {scale}: wall {r['wall_s']} s, {r['tiles_per_s']} tiles/s, first {r['first_tile_s']} s, build median "
              f"{r['build_ms_median']} ms mean {r['build_ms_mean']} ms" +
              (f", latency median {r['latency_s_median']} s" if "latency_s_median" in r else "") +
              f", reserved {r['vram'].get('reserved_bytes', 0) / 2**20:.0f} MiB peak "
              f"{r['vram'].get('peak_bytes', 0) / 2**20:.0f} MiB process {r['vram'].get('process_bytes', 0) / 2**20:.0f} MiB"
              f" pinned host {r['vram'].get('pinned_host_bytes', 0) / 2**20:.0f} MiB failed {r.get('failed')}"
              f"; idle: reserved {r['vram_idle'].get('reserved_bytes', 0) / 2**20:.0f} MiB process "
              f"{r['vram_idle'].get('process_bytes', 0) / 2**20:.0f} MiB")
        ts = r.get("tile_stages") or []
        if len(ts) > 2:          # the median tile (by total time) after the first: steady-state composition
            rest = sorted(ts[1:], key=lambda d: d.get("tile", 0) + sum(d.values()))
            med = rest[len(rest) // 2]
            tot = sum(med.values())
            print(f"   median steady tile: {tot:.3f} s (profiled)")
            for name, sec in sorted(med.items(), key=lambda kv: -kv[1])[:20]:
                print(f"     {name:26s} {sec:8.4f} s  {100 * sec / tot:5.1f} %")
        if "stages" in r:
            tot = sum(v[1] for v in r["stages"].values())
            for name, (n, s) in sorted(r["stages"].items(), key=lambda kv: -kv[1][1]):
                if s / tot >= 0.002:
                    print(f"   {name:28s} {n:6d} calls {s:8.3f} s  {100 * s / tot:5.1f} %")
    if args.json:
        Path(args.json).write_text(json.dumps(res, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
