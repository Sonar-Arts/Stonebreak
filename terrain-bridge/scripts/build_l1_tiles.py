"""Build a block of adjacent L1 macro-tiles from live terrain, and audit their seams.

Phase 7's exit criterion is "a debug render of four adjacent L1 tiles shows continuous
rivers across all three internal seams and lake surfaces flat to the block". A render
alone cannot answer that -- at 30 m/cell over 61 km a river is a hairline, and a seam
step of a few metres is invisible -- so this script measures the seams numerically and
renders them, and the render exists to show *where* rather than *whether*.

Four numbers per seam, each a different way the phase could have failed:

  continuity   a channel arriving at the border must still be a channel across it.
               This is the failure a player sees: a river that stops at a line.
  discharge    the total upslope area crossing the border, compared either side. Position
               without discharge is a hairline drawn in the right place -- what an
               unseeded tile produces.
  width        what the carve will actually cut, so what the seam will look like.
  surface      the step in water level across the border, in metres and in Phase 5
               blocks. A step is a waterfall the terrain has no reason to have.

Lake levelness is checked in blocks as well as metres, because "flat to the block" is
the criterion and the elevation curve is non-linear -- 4 m in the lowlands is a whole
block, 24 m in the highlands is not.

    cd terrain-bridge
    TERRAIN_BRIDGE_SEED=<seed> TERRAIN_BRIDGE_UPSTREAM_URL=http://127.0.0.1:8010 \
      ./venv/bin/python scripts/build_l1_tiles.py --render
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
from bridge.height_mapping import HeightCurve  # noqa: E402
from hydrology import basins as B  # noqa: E402
from hydrology import local as L  # noqa: E402
from scripts.build_l0_region import hillshade  # noqa: E402


def seam_report(
    up: L.L1Solution, down: L.L1Solution, params: L.L1Params, curve: HeightCurve, axis: int
) -> dict:
    """Compare the two rows (or columns) of owned ground that touch across a seam.

    `axis=0` means `down` lies at greater i than `up`; `axis=1`, greater j. The
    comparison is one cell either side of the border and nothing else: everything
    further in is a different question.
    """
    if axis == 0:
        a, b = up.accumulation[-1, :], down.accumulation[0, :]
        wa, wb = up.width_m(params)[-1, :], down.width_m(params)[0, :]
        sa, sb = up.water_surface[-1, :], down.water_surface[0, :]
        ca, cb = up.channels(params)[-1, :], down.channels(params)[0, :]
        leaving, arriving = up.crossings("i+"), down.crossings("i-")
    else:
        a, b = up.accumulation[:, -1], down.accumulation[:, 0]
        wa, wb = up.width_m(params)[:, -1], down.width_m(params)[:, 0]
        sa, sb = up.water_surface[:, -1], down.water_surface[:, 0]
        ca, cb = up.channels(params)[:, -1], down.channels(params)[:, 0]
        leaving, arriving = up.crossings("j+"), down.crossings("j-")

    # Only channels that actually *cross* are asked to continue. A river running along a
    # border, or away from it, owes the other side nothing -- and on real terrain most
    # channels touching a border are one of those, so counting them all would report a
    # discontinuity that is really just the shape of the drainage.
    #
    # `arriving` is the reverse case: water crossing back the other way. Both directions
    # are seams, and a tile border does not know which way the ground slopes.
    def spread(mask):
        """A D8 step across the border may land one cell sideways."""
        return mask | np.roll(mask, 1) | np.roll(mask, -1)

    crosses = ca & leaving          # up's channels that leave through this border
    returns = cb & arriving         # down's channels that leave back through it
    crossing = np.flatnonzero(crosses)
    reverse = np.flatnonzero(returns)
    continued = int(spread(cb)[crossing].sum()) if crossing.size else 0
    continued_back = int(spread(ca)[reverse].sum()) if reverse.size else 0

    ratios = np.array(
        [float(b[max(c - 1, 0): c + 2].max() / a[c]) for c in crossing]
    ) if crossing.size else np.zeros(0)
    wratios = np.array(
        [float(wb[max(c - 1, 0): c + 2].max() / wa[c]) for c in crossing if wa[c] > 0]
    ) if crossing.size else np.zeros(0)

    # Discharge is compared only where water actually crosses: the cells it leaves from,
    # against the cells it lands on. Summing the whole edge instead would divide the two
    # sides' entire drainage, which for a border water runs *along* is a ratio of two
    # unrelated numbers.
    dis_up = float(a[crosses].sum())
    dis_down = float(b[spread(crosses) & cb].sum())

    both_wet = np.isfinite(sa) & np.isfinite(sb)
    step_m = np.abs(sa[both_wet] - sb[both_wet]) if both_wet.any() else np.zeros(0)
    step_blocks = (
        np.abs(curve.to_block_height(sa[both_wet]) - curve.to_block_height(sb[both_wet]))
        if both_wet.any() else np.zeros(0)
    )

    def pct(x, q):
        return float(np.percentile(x, q)) if x.size else 0.0

    return {
        "axis": "i" if axis == 0 else "j",
        "channel_cells_crossing": int(crossing.size),
        "channel_cells_continuing": continued,
        "channel_cells_crossing_back": int(reverse.size),
        "channel_cells_continuing_back": continued_back,
        "continuity_fraction": (
            (continued + continued_back) / (crossing.size + reverse.size)
            if crossing.size + reverse.size else 1.0
        ),
        "discharge_total_upstream": dis_up,
        "discharge_total_downstream": dis_down,
        "discharge_conservation": (dis_down / dis_up) if dis_up else 1.0,
        "discharge_ratio_p10": pct(ratios, 10),
        "discharge_ratio_median": pct(ratios, 50),
        "discharge_ratio_p90": pct(ratios, 90),
        "width_ratio_median": pct(wratios, 50),
        "width_ratio_min": float(wratios.min()) if wratios.size else 0.0,
        "wet_cells_both_sides": int(both_wet.sum()),
        "surface_step_median_m": pct(step_m, 50),
        "surface_step_max_m": float(step_m.max()) if step_m.size else 0.0,
        "surface_step_max_blocks": float(step_blocks.max()) if step_blocks.size else 0.0,
    }


def lake_report(sol: L.L1Solution, curve: HeightCurve) -> dict:
    """Is every lake surface level -- in metres, and in the blocks it will be placed as?"""
    from scipy import ndimage

    lake = sol.lake
    if not lake.any():
        return {"lakes": 0}
    labels, n = ndimage.label(lake, structure=np.ones((3, 3), dtype=int))
    index = np.arange(1, n + 1)
    hi = np.asarray(ndimage.maximum(sol.water_surface, labels, index)).reshape(-1)
    lo = np.asarray(ndimage.minimum(sol.water_surface, labels, index)).reshape(-1)
    sizes = np.bincount(labels.ravel(), minlength=n + 1)[1:]
    blocks = curve.to_block_height(hi) - curve.to_block_height(lo)
    return {
        "lakes": int(n),
        "lake_cells": int(lake.sum()),
        "largest_lake_cells": int(sizes.max()),
        "largest_lake_km2": float(sizes.max()) * (sol.geometry.cell_m / 1000.0) ** 2,
        "max_surface_spread_m": float(np.max(hi - lo)),
        "max_surface_spread_blocks": float(np.max(blocks)),
        "lakes_not_level_to_the_block": int((blocks > 0).sum()),
    }


def render(tiles: dict, params: L.L1Params, seams: list[dict], path: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    ids = sorted(tiles)
    rows = sorted({i for i, _ in ids})
    cols = sorted({j for _, j in ids})
    geom = tiles[ids[0]].geometry
    n = geom.tile_native_px

    def mosaic(fn):
        return np.block([[fn(tiles[(i, j)]) for j in cols] for i in rows])

    elev = mosaic(lambda s: s.elevation)
    acc = mosaic(lambda s: s.accumulation)
    surf = mosaic(lambda s: s.water_surface)
    ocean = mosaic(lambda s: s.ocean.astype(float))
    lake = mosaic(lambda s: s.lake.astype(float))

    hs = hillshade(elev, geom.cell_m)
    channels = (acc >= params.river_threshold_cells) & (ocean < 0.5)
    log_acc = np.ma.masked_where(~channels, np.log10(np.maximum(acc, 1.0)))
    sea = np.ma.masked_where(ocean < 0.5, ocean)
    lakes = np.ma.masked_where(lake < 0.5, np.where(lake > 0.5, surf - elev, np.nan))

    fig, axes = plt.subplots(1, 3, figsize=(21, 7.6), constrained_layout=True)
    km = elev.shape[0] * geom.cell_m / 1000.0

    axes[0].imshow(hs, cmap="gray", vmin=0, vmax=1)
    axes[0].imshow(np.ma.masked_where(ocean < 0.5, elev), cmap="Blues_r", alpha=0.55)
    axes[0].set_title(f"{len(ids)} L1 tiles — {km:.0f} km, {geom.cell_m:.0f} m/cell")

    axes[1].imshow(hs, cmap="gray", vmin=0, vmax=1)
    axes[1].imshow(log_acc, cmap="viridis")
    axes[1].imshow(sea, cmap="Blues", alpha=0.35)
    axes[1].imshow(lakes, cmap="winter_r")
    worst = min((s["continuity_fraction"] for s in seams), default=1.0)
    axes[1].set_title(
        f"channels ≥ {params.river_threshold_cells:.0f} cells and lakes\n"
        f"{len(seams)} internal seams, worst continuity {worst * 100:.1f}%"
    )

    for ax in (axes[0], axes[1]):
        for k in rows[1:]:
            ax.axhline((k - rows[0]) * n - 0.5, color="orangered", lw=0.8, alpha=0.7)
        for k in cols[1:]:
            ax.axvline((k - cols[0]) * n - 0.5, color="orangered", lw=0.8, alpha=0.7)

    # The whole-block panel cannot answer the question the exit criterion asks: at 30 m
    # per cell a trunk river is one pixel and a tile border is one line, so a seam either
    # side of which the network differs looks exactly like one where it does not. The
    # third panel is a 9 km window centred on the largest river that actually crosses a
    # seam -- the place a discontinuity would be visible if there were one.
    side = min(300, n)
    hottest = max(
        seams, key=lambda s: s["discharge_total_upstream"], default=None
    ) if seams else None
    if hottest is None:
        r0 = c0 = 0
    else:
        (ai, aj), (bi, bj) = (tuple(x) for x in hottest["between"])
        up = tiles[(ai, aj)]
        along_i = hottest["axis"] == "i"
        strip = up.accumulation[-1, :] if along_i else up.accumulation[:, -1]
        crossed = up.crossings("i+" if along_i else "j+")
        hot = int(np.argmax(np.where(crossed, strip, -1.0)))
        # The seam sits at the far edge of `up`'s own ground, in mosaic coordinates.
        border_i = (ai - rows[0] + 1) * n if along_i else hot + (ai - rows[0]) * n
        border_j = hot + (aj - cols[0]) * n if along_i else (aj - cols[0] + 1) * n
        r0 = int(np.clip(border_i - side // 2, 0, elev.shape[0] - side))
        c0 = int(np.clip(border_j - side // 2, 0, elev.shape[1] - side))
    win = np.s_[r0: r0 + side, c0: c0 + side]

    axes[2].imshow(hs[win], cmap="gray", vmin=0, vmax=1)
    axes[2].imshow(log_acc[win], cmap="viridis")
    axes[2].imshow(sea[win], cmap="Blues", alpha=0.35)
    axes[2].imshow(lakes[win], cmap="winter_r")
    # Only the borders that actually fall inside the window; an axhline outside it
    # silently rescales the axes and shrinks the map to a thumbnail.
    for k in rows[1:]:
        y = (k - rows[0]) * n - r0 - 0.5
        if 0 <= y < side:
            axes[2].axhline(y, color="orangered", lw=1.2, alpha=0.9)
    for k in cols[1:]:
        x = (k - cols[0]) * n - c0 - 0.5
        if 0 <= x < side:
            axes[2].axvline(x, color="orangered", lw=1.2, alpha=0.9)
    axes[2].set_xlim(-0.5, side - 0.5)
    axes[2].set_ylim(side - 0.5, -0.5)
    axes[2].set_title(
        f"busiest seam crossing, {side * geom.cell_m / 1000:.0f} km across\n"
        "tile borders in red — does the river notice them?"
    )

    axes[1].add_patch(
        plt.Rectangle((c0, r0), side, side, fill=False, ec="orangered", lw=1.4)
    )
    for ax in axes:
        ax.set_xticks([])
        ax.set_yticks([])
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=110)
    print(f"wrote {path}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tile", type=int, nargs=2, default=(1, 1), metavar=("I", "J"),
                    help="top-left tile of the block")
    ap.add_argument("--grid", type=int, default=2, help="build a GRID x GRID block")
    ap.add_argument("--threshold", type=float, default=None,
                    help="channel threshold in cells (default: L1Params')")
    ap.add_argument("--cache-dir", type=str, default=None)
    ap.add_argument("--out-dir", type=str, default="../Dev Working/phase7")
    ap.add_argument("--render", action="store_true")
    ap.add_argument("--verify", action="store_true",
                    help="re-solve the first tile from scratch and compare payload bytes")
    args = ap.parse_args()

    cfg = BridgeConfig.from_env()
    curve = HeightCurve.from_config(cfg)
    params = L.L1Params(
        **({"river_threshold_cells": args.threshold} if args.threshold else {})
    )
    geom = params.geometry
    cache_dir = args.cache_dir or cfg.cache_dir

    l0_cache = B.L0Cache(cache_dir, B.L0Params())
    l1_cache = L.L1Cache(cache_dir, params)
    source = B.upstream_source(cfg)
    mosaic = L.L0Mosaic(l0_cache, source, cfg.seed)

    px = geom.solve_native_px
    print(f"L1 tiles {args.grid}x{args.grid} from {tuple(args.tile)} @ {l1_cache.root}")
    print(f"  owns   {geom.tile_native_px}^2 px = "
          f"{geom.tile_native_px * geom.cell_m / 1000:.1f} km/side")
    print(f"  solves {px}^2 px (halo {geom.halo_native_px}), {px * px / 1e6:.1f} M px "
          f"in {(px // geom.fetch_native_px) ** 2} blocks")
    print(f"  fingerprint {params.fingerprint()}, threshold "
          f"{params.river_threshold_cells:.0f} cells "
          f"({params.river_threshold_cells * (geom.cell_m / 1000) ** 2:.1f} km2)")

    tiles: dict[tuple[int, int], L.L1Solution] = {}
    timings = {}
    for di in range(args.grid):
        for dj in range(args.grid):
            ti, tj = args.tile[0] + di, args.tile[1] + dj
            t0 = time.perf_counter()
            sol = l1_cache.get_or_solve(L.L1TileId(cfg.seed, ti, tj), source, mosaic)
            dt = time.perf_counter() - t0
            tiles[(ti, tj)] = sol
            timings[f"{ti}_{tj}"] = round(dt, 2)
            st = sol.stats(params)
            print(f"  tile ({ti},{tj}) in {dt:6.1f} s  "
                  f"land {st['land_cells'] / geom.tile_native_px ** 2 * 100:4.1f}%  "
                  f"channels {st['channel_cells']:6d} ({st['channel_km']:.0f} km)  "
                  f"lakes {st['lake_cells']:5d}  max catchment "
                  f"{st['max_catchment_km2']:.0f} km2")
            if sol.report:
                s = sol.report.get("seeding", {})
                d = sol.report.get("deferral", {})
                m = sol.report.get("monotone", {})
                print(f"      seeded {s.get('seeded_edges', 0)} edges "
                      f"({s.get('seeded_area_cells', 0) / 1e6:.2f} M cells of catchment) "
                      f"from L0 {s.get('l0_regions_used')}")
                print(f"      wide basins {d.get('wide_basins', 0)} "
                      f"(deferred {d.get('deferred_to_l0', 0)}, "
                      f"dropped {d.get('dropped_no_l0_lake', 0)}); "
                      f"monotone lowered {m.get('cells_lowered', 0)} cells "
                      f"(max {m.get('max_lowering_m', 0):.1f} m, "
                      f"{m.get('protected_violations', 0)} lake violations)")

    seams = []
    for (ti, tj), sol in sorted(tiles.items()):
        for axis, nb in ((0, (ti + 1, tj)), (1, (ti, tj + 1))):
            if nb in tiles:
                rep = seam_report(sol, tiles[nb], params, curve, axis)
                rep["between"] = [[ti, tj], list(nb)]
                seams.append(rep)
                print(f"  seam ({ti},{tj})->{nb} along {rep['axis']}: "
                      f"continuity {rep['continuity_fraction'] * 100:5.1f}% "
                      f"({rep['channel_cells_continuing'] + rep['channel_cells_continuing_back']}"
                      f"/{rep['channel_cells_crossing'] + rep['channel_cells_crossing_back']}), "
                      f"discharge {rep['discharge_conservation']:.3f}, "
                      f"width median {rep['width_ratio_median']:.2f}, "
                      f"surface step max {rep['surface_step_max_m']:.2f} m "
                      f"({rep['surface_step_max_blocks']:.0f} blocks)")

    # The default threshold puts only a handful of channels on any one border, which is
    # too thin a sample to conclude anything from. Accumulation is stored raw, so the
    # same tiles answer for any threshold -- and a seam that is sound is sound at every
    # scale of river, not only at the one the knob happens to be set to.
    sweep = []
    for thresh in (1000.0, 2000.0, 5000.0, 10000.0, 25000.0):
        p2 = L.L1Params(**{**params.__dict__, "river_threshold_cells": thresh})
        rows = []
        for (ti, tj), sol in sorted(tiles.items()):
            for axis, nb in ((0, (ti + 1, tj)), (1, (ti, tj + 1))):
                if nb in tiles:
                    rows.append(seam_report(sol, tiles[nb], p2, curve, axis))
        crossed = sum(r["channel_cells_crossing"] + r["channel_cells_crossing_back"] for r in rows)
        kept = sum(r["channel_cells_continuing"] + r["channel_cells_continuing_back"] for r in rows)
        widths = [r["width_ratio_median"] for r in rows if r["width_ratio_median"] > 0]
        steps = [r["surface_step_max_blocks"] for r in rows]
        sweep.append({
            "threshold_cells": thresh,
            "threshold_km2": thresh * (geom.cell_m / 1000.0) ** 2,
            "crossings": crossed,
            "continued": kept,
            "continuity_fraction": (kept / crossed) if crossed else 1.0,
            "width_ratio_median": float(np.median(widths)) if widths else 0.0,
            "surface_step_max_blocks": max(steps) if steps else 0.0,
        })
        print(f"  threshold {thresh:7.0f} cells ({sweep[-1]['threshold_km2']:5.1f} km2): "
              f"{kept}/{crossed} crossings continue "
              f"({sweep[-1]['continuity_fraction'] * 100:5.1f}%), width median "
              f"{sweep[-1]['width_ratio_median']:.2f}, worst surface step "
              f"{sweep[-1]['surface_step_max_blocks']:.0f} blocks")

    lakes = {f"{i}_{j}": lake_report(s, curve) for (i, j), s in sorted(tiles.items())}
    for key, rep in lakes.items():
        if rep.get("lakes"):
            print(f"  tile {key} lakes: {rep['lakes']}, largest "
                  f"{rep['largest_lake_km2']:.1f} km2, surface spread "
                  f"max {rep['max_surface_spread_m']:.3f} m / "
                  f"{rep['max_surface_spread_blocks']:.0f} blocks, "
                  f"{rep['lakes_not_level_to_the_block']} not level to the block")

    result = {
        "fingerprint": params.fingerprint(),
        "threshold_cells": params.river_threshold_cells,
        "geometry": {
            "tile_native_px": geom.tile_native_px,
            "halo_native_px": geom.halo_native_px,
            "fetch_native_px": geom.fetch_native_px,
            "cell_m": geom.cell_m,
        },
        "seconds": timings,
        "stats": {f"{i}_{j}": s.stats(params) for (i, j), s in sorted(tiles.items())},
        "reports": {f"{i}_{j}": s.report for (i, j), s in sorted(tiles.items())},
        "seams": seams,
        "seam_threshold_sweep": sweep,
        "lakes": lakes,
    }

    if args.verify:
        tid = L.L1TileId(cfg.seed, *args.tile)
        payload = l1_cache.path(tid).read_bytes()
        l1_cache.path(tid).unlink()
        t0 = time.perf_counter()
        again = l1_cache.get_or_solve(tid, source, L.L0Mosaic(l0_cache, source, cfg.seed))
        redo = time.perf_counter() - t0
        identical = L.encode(again) == payload
        result["verify"] = {"seconds": round(redo, 2), "byte_identical": identical}
        print(f"  re-solved ({args.tile[0]},{args.tile[1]}) in {redo:.1f} s: "
              f"byte-identical = {identical}")
        if not identical:
            raise SystemExit("DETERMINISM FAILED: a second solve of the same tile differs")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    name = f"l1_s{cfg.seed}_t{args.tile[0]}_{args.tile[1]}_{args.grid}x{args.grid}"
    (out / f"{name}.json").write_text(json.dumps(result, indent=2, default=float))
    print(f"wrote {out / f'{name}.json'}")
    if args.render:
        render(tiles, params, seams, out / f"{name}.png")


if __name__ == "__main__":
    main()
