"""Build one L0 macro-region from live terrain, and check it against Phase 6c's gate.

Phase 6c's exit criteria are "an L0 debug render of a 250 km region shows a plausible
dendritic network draining to ocean, and re-running produces identical bytes". This
script is what produces both: it solves a region through `hydrology.basins`, renders the
three panels, and -- with `--verify` -- discards the cache entry and solves the whole
region a second time to compare the payload byte for byte. The second solve re-fetches
every block, so what it checks is the *pipeline's* determinism, upstream generation
included, not just the solver's.

It talks to upstream directly rather than through the bridge. The bridge serves canonical
block tiles; L0 wants contiguous native-resolution elevation, which is a different request
shape and 33-50x cheaper per pixel (`Dev Working/Rivers and lakes plan.md` section 13.3).

    cd terrain-bridge
    TERRAIN_BRIDGE_SEED=<seed> TERRAIN_BRIDGE_UPSTREAM_URL=http://127.0.0.1:8010 \
      ./venv/bin/python scripts/build_l0_region.py --render --verify
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bridge.config import BridgeConfig  # noqa: E402
from hydrology import basins as B  # noqa: E402


def hillshade(z: np.ndarray, cell_m: float, az: float = 315.0, alt: float = 45.0) -> np.ndarray:
    gy, gx = np.gradient(np.nan_to_num(z.astype(np.float64)), cell_m)
    slope = np.arctan(np.hypot(gx, gy) * 4.0)
    aspect = np.arctan2(-gx, gy)
    a, e = np.radians(az), np.radians(alt)
    return np.clip(
        np.sin(e) * np.cos(slope) + np.cos(e) * np.sin(slope) * np.cos(a - aspect), 0.0, 1.0
    )


def network_report(sol: B.L0Solution, threshold: float) -> dict:
    """Does the drainage network actually go somewhere?

    The failure this catches is a network that looks dendritic in a thumbnail but
    terminates in interior pits -- which is what an over-capped fill, or a routing pass
    run on the capped surface instead of the complete one, would produce.

    "Reaches ocean" alone is the wrong bar, and would understate the result: this is a
    bounded window, so a river whose sea is in the *next* region correctly leaves through
    the border. Both endings are drainage. Only a component that does neither is a bug,
    which is why `undrained_components` is the number that matters here.
    """
    from scipy import ndimage

    land = ~sol.ocean
    edge = np.zeros(sol.ocean.shape, dtype=bool)
    edge[[0, -1], :] = edge[:, [0, -1]] = True
    terminal = (sol.flow_dir == B.TERMINAL) & land
    coastal = ndimage.binary_dilation(sol.ocean, structure=np.ones((3, 3), dtype=int))

    channels = sol.channels(threshold)
    near_ocean = ndimage.binary_dilation(sol.ocean, iterations=2)
    labels, n = ndimage.label(channels, structure=np.ones((3, 3), dtype=int))
    if n == 0:
        return {"channel_cells": 0, "components": 0}

    index = np.arange(1, n + 1)
    sizes = np.bincount(labels.ravel(), minlength=n + 1)[1:]
    reaches = np.asarray(ndimage.maximum(near_ocean, labels, index)).reshape(-1).astype(bool)
    exits = np.asarray(ndimage.maximum(edge, labels, index)).reshape(-1).astype(bool)
    major = sizes >= 50
    return {
        "channel_cells": int(channels.sum()),
        "channel_threshold_km2": threshold * (sol.geometry.cell_m / 1000.0) ** 2,
        "components": int(n),
        "components_reaching_ocean": int(reaches.sum()),
        "major_components": int(major.sum()),
        "major_components_reaching_ocean": int((major & reaches).sum()),
        "major_components_exiting_region": int((major & ~reaches & exits).sum()),
        "undrained_components": int((~reaches & ~exits).sum()),
        # Every land cell with nowhere to send water. All of these should be river
        # mouths or border exits; anything else is a pit the fill failed to remove.
        "terminal_land_cells": int(terminal.sum()),
        "terminal_at_coast": int((terminal & coastal).sum()),
        "terminal_at_region_edge": int((terminal & edge & ~coastal).sum()),
        "interior_pits": int((terminal & ~coastal & ~edge).sum()),
    }


def render(
    sol: B.L0Solution, net: dict, path: Path, seconds: float, max_raise: float | None
) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    geom = sol.geometry
    km = sol.elevation.shape[0] * geom.cell_m / 1000.0
    hs = hillshade(sol.elevation, geom.cell_m)
    ocean = sol.ocean
    land = ~ocean
    st = sol.stats()

    channels = sol.channels(1000.0)
    log_acc = np.ma.masked_where(~channels, np.log10(np.maximum(sol.accumulation, 1.0)))
    depth = np.ma.masked_invalid(np.where(sol.lake, sol.lake_surface - sol.elevation, np.nan))
    sea = np.ma.masked_where(land, ocean.astype(float))

    fig, axes = plt.subplots(1, 4, figsize=(27, 7.4), constrained_layout=True)

    axes[0].imshow(hs, cmap="gray", vmin=0, vmax=1)
    axes[0].imshow(np.ma.masked_where(land, sol.elevation), cmap="Blues_r", alpha=0.55)
    axes[0].set_title(
        f"L0 elevation — {km:.0f} km, {geom.cell_m:.0f} m/cell\n"
        f"native terrain box-downsampled ({geom.cell_native_px}x{geom.cell_native_px} px)"
    )

    axes[1].imshow(hs, cmap="gray", vmin=0, vmax=1)
    axes[1].imshow(log_acc, cmap="viridis")
    axes[1].imshow(sea, cmap="Blues", alpha=0.35)
    drained = net.get("major_components_reaching_ocean", 0) + net.get(
        "major_components_exiting_region", 0
    )
    axes[1].set_title(
        f"drainage network >= {net.get('channel_threshold_km2', 0):.0f} km²"
        f" — {net.get('channel_cells', 0)} cells\n"
        f"{drained}/{net.get('major_components', 0)} major components drain "
        f"({net.get('major_components_reaching_ocean', 0)} to ocean, "
        f"{net.get('major_components_exiting_region', 0)} across the border); "
        f"{net.get('interior_pits', 0)} pits"
    )

    # A 61 km window on the busiest catchment. The whole-region panel cannot answer the
    # question the exit criterion actually asks -- at 240 m/cell a trunk river is one
    # pixel wide, and branching is invisible until you are close enough to see it.
    side = min(256, sol.elevation.shape[0])
    outlet = np.unravel_index(int(np.argmax(np.where(channels, sol.accumulation, -1))), hs.shape)
    r0 = int(np.clip(outlet[0] - side // 2, 0, hs.shape[0] - side))
    c0 = int(np.clip(outlet[1] - side // 2, 0, hs.shape[1] - side))
    win = np.s_[r0: r0 + side, c0: c0 + side]
    axes[2].imshow(hs[win], cmap="gray", vmin=0, vmax=1)
    axes[2].imshow(log_acc[win], cmap="viridis")
    axes[2].imshow(sea[win], cmap="Blues", alpha=0.35)
    axes[2].imshow(depth[win], cmap="winter_r")
    axes[2].set_title(
        f"largest catchment ({st['max_catchment_km2']:.0f} km²), "
        f"{side * geom.cell_m / 1000:.0f} km across\n"
        "channels and lakes together — is it dendritic?"
    )
    for ax in (axes[0], axes[1]):
        ax.add_patch(plt.Rectangle((c0, r0), side, side, fill=False, ec="orangered", lw=1.2))

    axes[3].imshow(hs, cmap="gray", vmin=0, vmax=1)
    axes[3].imshow(depth, cmap="winter_r")
    axes[3].imshow(sea, cmap="Blues", alpha=0.35)
    axes[3].set_title(
        f"lake surfaces — {st['lake_cells']} cells "
        f"({st['lake_land_fraction'] * 100:.1f}% of land)\n"
        + (f"capped at max_raise = {max_raise:.0f} m; " if max_raise else "uncapped; ")
        + f"solved in {seconds:.1f} s"
    )

    for ax in axes:
        ax.set_xticks([])
        ax.set_yticks([])
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=110)
    print(f"wrote {path}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--region", type=int, nargs=2, default=(0, 0), metavar=("I", "J"))
    ap.add_argument("--max-raise", type=float, default=100.0,
                    help="basin fill cap in metres; 0 disables it (plan section 13.8)")
    ap.add_argument("--cache-dir", type=str, default=None)
    ap.add_argument("--out-dir", type=str, default="../Dev Working/phase6c")
    ap.add_argument("--render", action="store_true")
    ap.add_argument("--verify", action="store_true",
                    help="re-solve the region from scratch and compare the payload bytes")
    args = ap.parse_args()

    cfg = BridgeConfig.from_env()
    params = B.L0Params(max_raise_m=args.max_raise or None)
    geom = params.geometry
    cache = B.L0Cache(args.cache_dir or cfg.cache_dir, params)
    region = B.L0RegionId(cfg.seed, *args.region)
    source = B.upstream_source(cfg)

    bounds = geom.solve_bounds_native(*args.region)
    px = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
    print(f"region {region.cache_key()} @ {cache.root}")
    print(f"  owns   {geom.region_cells}^2 cells = "
          f"{geom.region_cells * geom.cell_m / 1000:.1f} km/side")
    print(f"  solves {geom.solve_cells}^2 cells (halo {geom.halo_cells}), "
          f"{px / 1e6:.1f} M native px in {(geom.solve_cells // geom.fetch_cells) ** 2} blocks")
    print(f"  fingerprint {params.fingerprint()}, max_raise {params.max_raise_m}")

    t0 = time.perf_counter()
    sol = cache.get_or_solve(region, source)
    seconds = time.perf_counter() - t0
    payload = cache.path(region).read_bytes()
    print(f"  built in {seconds:.1f} s -> {len(payload) / 1e6:.1f} MB "
          f"({px / max(seconds, 1e-9) / 1000:.0f}k native px/s)")

    st = sol.stats()
    net = network_report(sol, 1000.0)
    print(f"  elevation {st['elev_min_m']:.0f}..{st['elev_max_m']:.0f} m, "
          f"land {st['land_cells'] / (st['land_cells'] + st['ocean_cells']) * 100:.0f}%")
    print(f"  lakes {st['lake_cells']} cells ({st['lake_land_fraction'] * 100:.1f}% of land), "
          f"max catchment {st['max_catchment_km2']:.0f} km2")
    print(f"  channels {net.get('channel_cells', 0)} cells in {net.get('components', 0)} "
          f"components; of {net.get('major_components', 0)} major ones "
          f"{net.get('major_components_reaching_ocean', 0)} reach ocean and "
          f"{net.get('major_components_exiting_region', 0)} exit the region")
    print(f"  land terminals {net.get('terminal_land_cells', 0)} "
          f"({net.get('terminal_at_coast', 0)} at coast, "
          f"{net.get('terminal_at_region_edge', 0)} at the region edge); "
          f"interior pits {net.get('interior_pits', 0)}")

    result = {"region": region.cache_key(), "fingerprint": params.fingerprint(),
              "max_raise_m": params.max_raise_m, "build_seconds": round(seconds, 2),
              "native_px": int(px), "payload_bytes": len(payload), "stats": st, "network": net}

    if args.verify:
        cache.path(region).unlink()
        t1 = time.perf_counter()
        again = cache.get_or_solve(region, source)
        redo = time.perf_counter() - t1
        identical = B.encode(again) == payload
        result["verify"] = {"seconds": round(redo, 2), "byte_identical": identical}
        print(f"  re-solved in {redo:.1f} s: payload byte-identical = {identical}")
        if not identical:
            raise SystemExit("DETERMINISM FAILED: a second solve of the same region differs")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"l0_{region.cache_key()}.json").write_text(json.dumps(result, indent=2, default=float))
    print(f"wrote {out / f'l0_{region.cache_key()}.json'}")
    if args.render:
        render(sol, net, out / f"l0_{region.cache_key()}.png", seconds, params.max_raise_m)


if __name__ == "__main__":
    main()
