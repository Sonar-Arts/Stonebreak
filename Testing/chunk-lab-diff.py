#!/usr/bin/env python3
"""Print a chunk-lab ledger as a table, diffed against a baseline ledger when one exists.

    chunk-lab-diff.py <run.json> [<baseline.json>]
"""
import json
import sys

METRICS = [
    # (section, key, label, unit)
    ("generation", "nanosPerChunk", "gen ns/chunk (tier pass)", "ns"),
    ("generation", "bestNanosCentre", "gen ns/chunk (best-of)", "ns"),
    ("generation", "heapAllocBytesPerChunk", "gen heap alloc/chunk", "B"),
    ("chunkRam", "bytesPerChunk", "chunk RAM/chunk", "B"),
    ("chunkRam", "blockStorageBytesPerChunk", "  block storage/chunk", "B"),
    ("chunkRam", "sectionsUniform", "  sections uniform", ""),
    ("chunkRam", "sectionsNibbleTier", "  sections nibble-tier", ""),
    ("chunkRam", "sectionsByteTier", "  sections byte-tier", ""),
    ("chunkRam", "sectionsShortTier", "  sections short-tier", ""),
    ("chunkRam", "maxPaletteSize", "  max palette", ""),
    ("mesh", "nanosPerChunk", "mesh ns/chunk (tier pass)", "ns"),
    ("mesh", "bestNanosCentre", "mesh ns/chunk (best-of)", "ns"),
    ("mesh", "heapAllocBytesPerChunk", "mesh heap alloc/chunk", "B"),
    ("mesh", "kernelQuads", "kernel quads", ""),
    ("mesh", "greedyQuads", "greedy quads", ""),
    ("mesh", "greedyRatio", "greedy ratio", "x"),
    ("mesh.atlas", "vertices", "atlas vertices", ""),
    ("mesh.atlas", "vertexBytes", "atlas vertex bytes", "B"),
    ("mesh.atlas", "indexBytes", "atlas index bytes", "B"),
    ("mesh.atlas", "conformantQuads", "atlas conformant quads", ""),
    ("mesh.atlas", "quads", "atlas quads", ""),
    ("mesh.water", "bytes", "water mesh bytes", "B"),
    ("mesh.stamp", "bytes", "stamp mesh bytes", "B"),
    ("mesh", "atlasBytesPerQuad", "atlas bytes/quad", "B"),
    ("mesh", "totalBytes", "mesh bytes total", "B"),
    ("mesh", "bytesPerChunk", "mesh bytes/chunk", "B"),
    ("mesh", "bytesPerQuad", "mesh bytes/quad", "B"),
    ("vram.totals", "rawMeshBytes", "VRAM raw mesh bytes", "B"),
    ("vram.totals", "copyReservedBytes", "VRAM reserved (copy)", "B"),
    ("vram.totals", "copySlackBytes", "  slack (copy)", "B"),
    ("vram.totals", "sparseReservedBytes", "VRAM reserved (sparse)", "B"),
    ("vram.totals", "sparseSlackBytes", "  slack (sparse)", "B"),
    ("vram.totals", "planModeReservedBytes", "VRAM reserved (plan mode)", "B"),
    ("gl", "trackedChunkMeshBytes", "GL tracked CHUNK_MESH", "B"),
    ("gl", "realRegionBytes", "GL real region bytes", "B"),
    ("gl", "plannedRegionBytes", "GL planned region bytes", "B"),
    ("gl", "plannedMatchesReal", "GL planned == real", ""),
    ("gl", "driverDeltaBytes", "GL driver VRAM delta", "B"),
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
    print(f"\n=== chunk-lab tier {run['tier']} — '{run['label']}'"
          + (f" vs baseline '{base['label']}'" if base else " (no baseline yet)") + " ===")
    print(f"mesher={env.get('mesherBackend')} gen={env.get('terrainGenMode')} greedy={env.get('greedyMeshing')}"
          f" vertexFormat={env.get('vertexFormat')} stride={env.get('vertexStrideBytes')}B"
          f" features={env.get('featuresPopulated')} plan={env.get('cearlPlan')}")
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
