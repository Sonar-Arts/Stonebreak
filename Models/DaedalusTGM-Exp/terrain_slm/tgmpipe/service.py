"""TGMPipe: the game's one terrain process (serving DaedalusTGM-Exp), spoken to over stdin/stdout.

    python -m terrain_slm.tgmpipe [model_dir] [--device cuda] [--cache-size 4G] [--disk-cache 8G]

The game launches this as a child (TGMPipe in stonebreak-game) and talks `protocol` frames
over its pipes: HELLO with the world config, then TILE requests for any seed, answered as tiles
finish, in priority order. No ports, no seed pinning, no second process:

  * stdout carries only frames. The real fd 1 is kept for the protocol and fd 1 is pointed at
    stderr, so a stray print or a native library's chatter lands in the log, not the stream.
  * stdin closing means the game is gone (exited, crashed or killed), so the service exits too;
    it never outlives the game.
  * any seed, per request: the generator's caches are keyed by seed (WorldGenerator.use_seed).

Threads: the reader parses frames and hands them, in order, to the front thread (disk-cache hits
answered at once, misses queued, cancels); one GPU thread builds tiles in priority order; the
back thread caches and sends finished tiles so the GPU starts the next one immediately.
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from terrain_slm.tgmpipe import protocol as W
from terrain_slm.tgmpipe.scheduler import Scheduler, TileKey

log = logging.getLogger("tgmpipe")


class Output:
    """Frames to the game, whole and one at a time, from any thread."""

    def __init__(self, stream):
        self._stream, self._lock = stream, threading.Lock()

    def send(self, data: bytes) -> None:
        with self._lock:
            self._stream.write(data)
            self._stream.flush()


class Service:
    def __init__(self, builder, cache, out: Output, info: dict):
        self.builder, self.cache, self.out, self.info = builder, cache, out, info
        self.scheduler = Scheduler()
        self._front = ThreadPoolExecutor(1, thread_name_prefix="front")
        self._back = ThreadPoolExecutor(1, thread_name_prefix="back")
        self._gpu = threading.Thread(target=self._gpu_loop, name="gpu", daemon=True)
        self.stats = {"served": 0, "cache_hits": 0, "generated": 0, "failed": 0, "gen_ms": 0.0}

    def start(self) -> None:
        self._gpu.start()

    def dispatch(self, kind: int, body: bytes) -> None:
        """Reader thread: route one frame. Order is kept (one front thread), so a CANCEL always
        lands after the TILE it names."""
        if kind == W.TILE:
            self._front.submit(self._request, *W.parse_tile(body))
        elif kind == W.CANCEL:
            self._front.submit(self.scheduler.cancel, W.parse_u32(body))
        elif kind == W.STATUS:
            self._front.submit(self._status, W.parse_u32(body))
        else:
            raise W.ProtocolError(f"unexpected frame type 0x{kind:02x}")

    def close(self) -> None:
        self.scheduler.close()
        self._front.shutdown(wait=False, cancel_futures=True)
        self._back.shutdown(wait=False, cancel_futures=True)

    # ------------------------------------------------------------------ front
    def _request(self, req_id, seed, tile_x, tile_z, lod, priority) -> None:
        key = TileKey(seed, tile_x, tile_z, lod)
        try:
            planes = self.cache.get(key)
        except Exception as e:  # noqa: BLE001 - a bad cache file must not cost the tile
            log.warning("cache read failed for %s: %s", key, e)
            planes = None
        if planes is not None:
            self.stats["cache_hits"] += 1
            self._send_tile(req_id, key, planes, cached=True)
        else:
            self.scheduler.submit(req_id, key, priority)

    def _status(self, token: int) -> None:
        self.out.send(W.status_reply(token, {**self.info, "queue": self.scheduler.depth(),
                                             "cache": self.cache.stats(), **self.stats}))

    # ------------------------------------------------------------------ GPU
    def _gpu_loop(self) -> None:
        while True:
            job = self.scheduler.take()
            if job is None:
                return
            key = job.key
            t0 = time.monotonic()
            try:
                planes = self.cache.get(key)  # a request that missed may have raced a finishing job
                if planes is None:
                    planes = self.builder.build(key.seed, key.tile_x, key.tile_z, key.lod)
                    self._back.submit(self.cache.put, key, planes)
                    ms = (time.monotonic() - t0) * 1000
                    self.stats["generated"] += 1
                    self.stats["gen_ms"] += ms
                    log.info("tile %s built in %.0f ms (queue %d)", key, ms, self.scheduler.depth())
            except Exception as e:  # noqa: BLE001 - answered to every waiter, then the next job
                log.exception("tile %s failed", key)
                self.stats["failed"] += 1
                _release_gpu_memory()
                message = f"{type(e).__name__}: {e}"
                for req_id in self.scheduler.finish(job):
                    self._back.submit(self.out.send, W.tile_error(req_id, message))
                continue
            for req_id in self.scheduler.finish(job):
                self._back.submit(self._send_tile, req_id, key, planes, False)

    def _send_tile(self, req_id: int, key: TileKey, planes, cached: bool) -> None:
        i1, j1, _, _ = self.builder.bounds(key.tile_x, key.tile_z)
        self.out.send(W.tile_data(req_id, cached, i1, j1, planes))
        self.stats["served"] += 1


def _release_gpu_memory() -> None:
    try:
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:  # noqa: BLE001
        pass


def _claim_stdio():
    """(protocol in, protocol out): private copies of fds 0 and 1, with fd 1 then pointed at
    stderr so nothing but frames can ever reach the game's end of the pipe."""
    proto_in = os.fdopen(os.dup(0), "rb", buffering=0)
    proto_out = os.fdopen(os.dup(1), "wb", buffering=1 << 20)
    os.dup2(2, 1)
    sys.stdout = sys.stderr
    return proto_in, proto_out


def _bytes(size: str) -> int:
    units = {"K": 1 << 10, "M": 1 << 20, "G": 1 << 30}
    s = size.strip().upper()
    return int(float(s[:-1]) * units[s[-1]]) if s[-1] in units else int(s)


def main(argv=None) -> None:
    proto_in, proto_out = _claim_stdio()
    out = Output(proto_out)
    logging.basicConfig(level=logging.INFO, stream=sys.stderr,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    from terrain_slm import MODEL_NAME
    from terrain_slm.paths import MODEL_DIR, TILE_CACHE_DIR

    ap = argparse.ArgumentParser(prog="python -m terrain_slm.tgmpipe")
    ap.add_argument("model_path", nargs="?", default="checkpoints/v3")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--cache-size", default="4G", help="in-memory generation cache (GPU)")
    ap.add_argument("--disk-cache", default="8G", help="finished-tile cache on disk, all versions")
    ap.add_argument("--disk-cache-dir", default=str(TILE_CACHE_DIR))
    ap.add_argument("--t-start", type=float, default=float(os.getenv("TERRAIN_SLM_T_START", "0.6")))
    ap.add_argument("--steps", type=int, default=int(os.getenv("TERRAIN_SLM_STEPS", "8")))
    ap.add_argument("--amp-scale", type=float, default=float(os.getenv("TERRAIN_SLM_AMP_SCALE", "1.0")))
    env_rt = os.getenv("TERRAIN_SLM_RIVER_THRESHOLD")
    ap.add_argument("--river-threshold", type=float, default=float(env_rt) if env_rt else None,
                    help="river density knob: log1p(upslope cells) where a river starts (lower = more "
                         "rivers); default: calibrated for the model")
    args = ap.parse_args(argv)

    try:
        import torch

        from terrain_slm.tgmpipe.tile_cache import TileCache, namespace, source_fingerprint
        from terrain_slm.world.generator import GenConfig, WorldGenerator
        from terrain_slm.world.tiles import TileBuilder
        from terrain_slm.world.world_config import WorldConfig

        device = args.device
        if device == "cuda" and torch.cuda.device_count() > 1:
            device = os.getenv("TERRAIN_SLM_DEVICE", "cuda:1")  # keep GPU 0 for rendering
        model_dir = Path(args.model_path)
        if not model_dir.is_absolute():
            model_dir = MODEL_DIR / model_dir
        regions = max(64, min(8192, _bytes(args.cache_size) // (384 * 384 * 4)))  # 384^2 fp32 regions
        t0 = time.monotonic()
        gen = WorldGenerator(model_dir, 0, device,
                             GenConfig(t_start=args.t_start, steps=args.steps, amp_scale=args.amp_scale,
                                       river_log_threshold=args.river_threshold),
                             cache_regions=regions)
        log.info("model %s on %s loaded in %.1f s; rivers: %s", gen.model_id, device,
                 time.monotonic() - t0, gen.river.describe())

        hello = W.read_frame(proto_in)
        if hello is None:
            return
        kind, body = hello
        if kind != W.HELLO:
            raise W.ProtocolError(f"expected HELLO, got frame type 0x{kind:02x}")
        version, payload = W.parse_hello(body)
        if version != W.VERSION:
            raise W.ProtocolError(f"game speaks terrain protocol v{version}, this service v{W.VERSION}")
        world = WorldConfig.from_dict(payload["world"])
        builder = TileBuilder(gen, world)
        source = source_fingerprint(Path(__file__).resolve().parents[1])
        cache = TileCache(Path(args.disk_cache_dir), namespace(world.fingerprint(), gen.model_id, source),
                          _bytes(args.disk_cache))
    except Exception as e:  # noqa: BLE001 - reported to the game, which shows it; then exit
        log.exception("TGMPipe failed to start")
        out.send(W.fatal(f"{type(e).__name__}: {e}"))
        sys.exit(2)

    info = {"name": MODEL_NAME, "model_id": gen.model_id, "device": device, "protocol": W.VERSION,
            "cache_namespace": cache.dir.name, "river_threshold": gen.cfg.river_log_threshold}
    service = Service(builder, cache, out, info)
    service.start()
    out.send(W.ready(info))
    log.info("ready: world %s, cache %s", world.fingerprint(), cache.dir)

    code = 0
    try:
        while (frame := W.read_frame(proto_in)) is not None:
            service.dispatch(*frame)
        log.info("game closed the connection; exiting")
    except W.ProtocolError as e:
        log.error("protocol error, exiting: %s", e)
        out.send(W.fatal(str(e)))
        code = 1
    finally:
        service.close()
        # Exits without waiting on a tile mid-build: nobody is left to receive it.
        os._exit(code)
