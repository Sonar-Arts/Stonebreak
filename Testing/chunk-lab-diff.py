#!/usr/bin/env python3
"""Print a chunk-lab ledger as a table, diffed against a baseline ledger when one exists.

    chunk-lab-diff.py <run.json> [<baseline.json>]
"""
import json
import sys

METRICS = [
    # (section, key, label, unit)  — cube ledger (CubeFootprintLab, cubic runtime)
    ("stream", "wallNanos", "wall time to READY", "ms"),
    ("stream", "cubesLoaded", "cubes loaded", ""),
    ("stream", "generatedTotal", "cubes generated", ""),
    ("stream", "meshedTotal", "cubes meshed", ""),
    ("stream", "generatedPerSecond", "generated / s", ""),
    ("stream", "meshedPerSecond", "meshed / s", ""),
    ("stream", "tickUsMax", "owner tick max (us)", ""),
    ("cubeRam", "encodeBytesAvg", "cube record bytes avg", "B"),
    ("cubeRam", "encodeBytesP50", "  p50", "B"),
    ("cubeRam", "encodeBytesP95", "  p95", "B"),
    ("cubeRam", "encodeBytesMax", "  max", "B"),
    ("cubeRam", "nearUniformCubes", "  near-uniform cubes", ""),
    ("mesh", "meshedCubes", "meshed cubes (non-empty)", ""),
    ("mesh", "bytesPerCubeAvg", "mesh bytes/cube avg", "B"),
    ("mesh", "bytesPerCubeP95", "  p95", "B"),
    ("mesh", "bytesPerCubeMax", "  max", "B"),
    ("mesh", "totalBytes", "mesh bytes total", "B"),
    ("mesh", "opaqueQuads", "opaque quads", ""),
    ("mesh", "cutoutQuads", "cutout quads", ""),
    ("mesh", "translucentQuads", "translucent quads", ""),
    ("mesh", "waterQuads", "water quads", ""),
    ("mesh", "stampVertices", "stamp vertices", ""),
    ("vram.totals", "regions", "VRAM regions (all layers)", ""),
    ("vram.totals", "usedBytes", "VRAM used", "B"),
    ("vram.totals", "reservedBytes", "VRAM reserved (planned)", "B"),
    ("vram.totals", "slackBytes", "  slack", "B"),
    ("vram.totals", "reservedBytesPerMeshedCube", "  reserved / meshed cube", "B"),
]


def get(tree, section, key):
    node = tree
    for part in section.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    if not isinstance(node, dict):
        return None
    return node.get(key)


def fmt(v, unit):
    if v is None:
        return "-"
    if isinstance(v, bool):
        return "yes" if v else "NO"
    if isinstance(v, float):
        return f"{v:.3f}{unit}"
    if unit == "B":
        a = abs(v)
        if a >= 1 << 20:
            return f"{v / (1 << 20):.2f} MiB"
        if a >= 1 << 10:
            return f"{v / (1 << 10):.1f} KiB"
        return f"{v} B"
    if unit == "ns":
        return f"{v / 1000:.1f} us"
    if unit == "ms":
        return f"{v / 1e6:.1f} ms"
    return f"{v}"


def main():
    run = json.load(open(sys.argv[1]))
    base = None
    if len(sys.argv) > 2:
        try:
            base = json.load(open(sys.argv[2]))
        except FileNotFoundError:
            base = None
    env = run.get("environment", {})
    print(f"\n=== cube lab radius {run['tier']} — '{run['label']}'"
          + (f" vs baseline '{base['label']}'" if base else " (no baseline yet)") + " ===")
    print(f"seed={env.get('seed')} scale={env.get('scale')} workers={env.get('workers')}"
          f" plan={env.get('cearlPlan')} allColumnsReady={env.get('allColumnsReady')}")
    w = 30
    head = f"{'metric':<{w}} {'run':>16}"
    if base:
        head += f" {'baseline':>16} {'delta':>10}"
    print(head)
    print("-" * len(head))
    for section, key, label, unit in METRICS:
        v = get(run, section, key)
        if v is None and section.startswith("gl"):
            continue
        line = f"{label:<{w}} {fmt(v, unit):>16}"
        if base:
            b = get(base, section, key)
            line += f" {fmt(b, unit):>16}"
            if isinstance(v, (int, float)) and isinstance(b, (int, float)) and not isinstance(v, bool) and b:
                pct = (v - b) / b * 100
                line += f" {pct:>+9.1f}%"
            else:
                line += f" {'':>10}"
        print(line)
    notes = run.get("notes") or []
    for n in notes:
        print(f"note: {n}")
    print()


if __name__ == "__main__":
    main()
