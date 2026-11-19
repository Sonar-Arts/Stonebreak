"""Manifest section + Python adapters of stages/tiles (world/tiles.py TileBuilder, world/ocean.py, biomes.py, the block
scale / height curve, the surface channels). Every constant and table is read from the live Python modules (only the
literals inside the vendored rule classifier's body -- 0.78, 2500 m, ... -- are code on both sides)."""
from __future__ import annotations


def _world(gen):
    """The WorldConfig of the native generator's current state (NativeGen.sync_state(world=...) sets it)."""
    from terrain_slm.world.world_config import WorldConfig
    w = getattr(gen, "_native_world", None)
    if w is None:
        raise RuntimeError("no world config bound to the generator (NativeGen.sync_state(world=...))")
    return w if isinstance(w, WorldConfig) else WorldConfig.from_dict(w)


def _builder(gen):
    """A Python TileBuilder for the current world (cached per world on the generator); sets the generator's block
    scale to that world's, as TileBuilder.build does."""
    from terrain_slm.world.tiles import TileBuilder
    w = _world(gen)
    cache = gen.__dict__.setdefault("_native_tile_builders", {})
    tb = cache.get(w)
    if tb is None:
        tb = cache[w] = TileBuilder(gen, w)
    gen.set_block_scale(tb.scale)
    return tb


def _scale_vars(gen):
    from terrain_slm.river.scale import BlockScale
    sc = BlockScale.from_world(_world(gen), gen.device) if getattr(gen, "_native_world", None) is not None else gen.scale
    return {k: v for k, v in vars(sc).items()}


def _d(f):
    d = f[0]
    return int(d) if d == int(d) else d


def _build(gen, i):
    """TileBuilder.build(seed, tile_x, tile_z, lod) -> {"planes": int16 (PLANES, rows, cols), "surface.<name>": uint8}."""
    import numpy as np
    import torch
    planes = _builder(gen).build(*i)
    out = {"planes": torch.from_numpy(np.array(planes, dtype=np.int16))}
    for name, v in (getattr(planes, "surface", None) or {}).items():
        out[f"surface.{name}"] = torch.from_numpy(np.array(v, dtype=np.uint8))
    return out


def _classify_biome(gen, i, f, t):
    from terrain_slm import biomes as B
    return B._classify_biome(t[0], t[1], i[0], i[1], elev_padded=t[2], pixel_size_m=f[0])


def _bn_classify(gen, i, f, t):
    from terrain_slm.models import biomenet as BN
    return BN.classify(gen.biomenet, t[0], t[1], i[0], i[1])


def _m(mod, name):
    import importlib
    return getattr(importlib.import_module(mod), name)


PY_CALLS = {
    "block_scale": lambda gen, i, f, t: _scale_vars(gen),
    # biomes.py / world/ocean.py module functions
    "B._classify_biome": _classify_biome,
    "B.swamp_candidates": lambda gen, i, f, t: _m("terrain_slm.biomes", "swamp_candidates")(
        t[0], t[1], t[2], i[0], i[1], step=i[2]),
    "B.majority": lambda gen, i, f, t: _m("terrain_slm.biomes", "majority")(t[0], i[0]),
    "B.smooth_mask": lambda gen, i, f, t: _m("terrain_slm.biomes", "smooth_mask")(t[0], i[0]),
    "B.apply_swamps": lambda gen, i, f, t: _m("terrain_slm.biomes", "apply_swamps")(t[0], t[1]),
    "BN.classify": _bn_classify,
    "O.sea_mask_for_samples": lambda gen, i, f, t: _m("terrain_slm.world.ocean", "sea_mask_for_samples")(gen, *i),
    "O.sea_cells": lambda gen, i, f, t: _m("terrain_slm.world.ocean", "sea_cells")(t[0], t[1]),
    "O.land_cells": lambda gen, i, f, t: _m("terrain_slm.world.ocean", "land_cells")(gen, *i, gen.device),
    # TileBuilder methods (the builder of the bound world)
    "SC.encode": lambda gen, i, f, t: _m("torch", "from_numpy")(_m("terrain_slm.world.surface_channels", "encode")(
        _m("terrain_slm.world.surface_channels", "CHANNELS")[i[0]].name, t[0])),
    "T.despeckle": lambda gen, i, f, t: _m("terrain_slm.world.tiles", "despeckle")(t[0], t[1]),
    "T.alpine_elevation": lambda gen, i, f, t: _m("terrain_slm.world.tiles", "alpine_elevation")(t[0], t[1]),
    "T.alpine_zones": lambda gen, i, f, t: _m("terrain_slm.world.tiles", "alpine_zones")(t[0], t[1], t[2], t[3], *f),
    "TileBuilder.build": lambda gen, i, f, t: _build(gen, i),
    "TileBuilder._red_deserts": lambda gen, i, f, t: _builder(gen)._red_deserts(t[0], i[0], i[1], _d(f)),
    "TileBuilder._blocks": lambda gen, i, f, t: _builder(gen)._blocks(*i[:4], _d(f)),
    "TileBuilder._blocks_fine": lambda gen, i, f, t: _builder(gen)._blocks_fine(*i),
    "TileBuilder._overview": lambda gen, i, f, t: _builder(gen)._overview(*i[:4], _d(f)),
    "TileBuilder._planes": lambda gen, i, f, t: _m("torch", "from_numpy")(_builder(gen)._planes(
        t[0], t[1], t[2], t[3], (t[4], t[5], t[6]), t[7] if len(t) > 7 else None)),
}

#: TileBuilder.surface_providers, in its order: (provider name, module with provider(gen)). Re-check against
#: world/tiles.py TileBuilder.__init__ when it changes.
_PROVIDERS = (("vegetation", "terrain_slm.world.vegetation_node"), ("surface", "terrain_slm.world.surface_node"))


def _surface_values(name, mod):
    def run(gen, i, f, t):
        tb = _builder(gen)
        ctx = {"d": _d(f), "i1": i[0], "j1": i[1], "mb": i[2], "ground": t[0], "climate": t[1], "regional": t[2],
               "height": t[3], "water": t[4], "wet": t[5], "biome": t[6]}
        p = _m(mod, "provider")(gen)
        return {} if p is None else p(tb, ctx)
    return run


for _n, _mod in _PROVIDERS:
    PY_CALLS[f"TileBuilder.surface.{_n}"] = _surface_values(_n, _mod)

# Adapters for methods other stages own, added only when no other manifest module defines them (their owners' adapters
# win; these are the calling conventions stages/tiles uses).
_DEP_CALLS = {
    "G.red_desert_cells": lambda gen, i, f, t: _m("terrain_slm.world.generator", "red_desert_cells")(t[0], t[1],
                                                                                                    gen.seed),
    "river.columns": lambda gen, i, f, t: gen.river.columns(*gen.river_field(*i), _d(f), t[0] if t else None),
    "G2._shelf_remap": lambda gen, i, f, t: _m("terrain_slm.world.generator2", "_shelf_remap")(t[0]),
    "shelf": lambda gen, i, f, t: gen.shelf(t[0], *i),
    "continent_land_cells": lambda gen, i, f, t: gen.continent_land_cells(*i, gen.device),
    "TileBuilder._carve": lambda gen, i, f, t: _builder(gen)._carve(*i[:4], _d(f)),
}


def _install_dep_calls():
    import importlib
    import pkgutil

    import terrain_slm.export as pkg
    taken = set()
    for info in pkgutil.iter_modules(pkg.__path__):
        if info.name.startswith("native_manifest_") and info.name != "native_manifest_tiles":
            try:
                taken |= set(getattr(importlib.import_module(f"terrain_slm.export.{info.name}"), "PY_CALLS", {}))
            except Exception:  # noqa: BLE001 - a broken sibling is its owner's problem
                pass
    for k, v in _DEP_CALLS.items():
        if k not in taken:
            PY_CALLS[k] = v


_install_dep_calls()


def contribute(gen, m: dict) -> None:
    from terrain_slm import biomes as B
    from terrain_slm.data.descriptors import CELL_PX
    from terrain_slm.export.native_manifest import f32
    from terrain_slm.river import pipeline as RP
    from terrain_slm.river.field import NO_FLOW, NO_TUNNEL
    from terrain_slm.river.scale import NATIVE_M
    from terrain_slm.world import generator as G
    from terrain_slm.world import ocean as O
    from terrain_slm.world import surface_channels as SC
    from terrain_slm.world import tiles as T

    c = m["constants"]
    c["CELL_PX"] = int(CELL_PX)
    c["CP"] = int(G.CP)
    c["NATIVE_M"] = float(NATIVE_M)
    c["RP.BLOCK_MARGIN"] = int(RP.BLOCK_MARGIN)
    c["NO_FLOW"], c["NO_TUNNEL"] = int(NO_FLOW), int(NO_TUNNEL)
    for name in ("PLANES", "SWAMP_SMOOTH", "OVERVIEW_MIN_PX", "FINE_K"):
        c[f"T.{name}"] = int(getattr(T, name))
    c["T.FINE_KEEP_M"] = float(T.FINE_KEEP_M)
    # FX4 block look (world/tiles.py end of file): doubles, rounded like torch rounds them
    for name in ("ALPINE_LAPSE_GAIN", "ALPINE_BASE_M", "TREELINE_GS_DAYS", "TREELINE_NOISE_C", "ALPINE_MIN_M",
                 "ALPINE_MIN_NOISE_M"):
        c[f"T.{name}"] = float(getattr(T, name))
    c["T.ALPINE_LOCAL_M0"], c["T.ALPINE_LOCAL_M1"] = (float(v) for v in T.ALPINE_LOCAL_M)
    for name in ("ALPINE_ROCK_ID", "ALPINE_SNOW_ID", "DESPIKE_UP", "DESPIKE_DOWN", "DESPECKLE_PASSES"):
        c[f"T.{name}"] = int(getattr(T, name))
    c["T.REGIONAL_LAPSE"] = int(bool(T.REGIONAL_LAPSE))
    for name in ("SEA_GROW_CELLS", "SEA_COAST_CELLS", "SEA_REACH_SAMPLES", "SEA_CELL_MARGIN"):
        c[f"O.{name}"] = int(getattr(O, name))
    c["O.SEA_LAND"] = f32(O.SEA_LAND)
    c["O.LAKE_CLASSIFY_ELEV_M"] = f32(O.LAKE_CLASSIFY_ELEV_M)
    for name in ("HYPER_ARID_PRECIP_MM", "SNOW_COVER_M_WE", "SNOW_COVER_MAX_TEMP_C", "SOIL_SWAMP_MIN_M3",
                 "SWAMP_BOG_MIN_TEMP_C", "SWAMP_MIN_MOISTURE", "SWAMP_FULL_MOISTURE", "SWAMP_MIN_TEMP_C",
                 "SWAMP_SNOW_MARGIN_C", "SWAMP_MAX_REGIONAL_M", "SWAMP_MAX_ELEV_M", "SWAMP_PATCH_DRY", "SWAMP_PATCH_WET"):
        c[f"B.{name}"] = float(getattr(B, name))          # doubles: the kernels round them like torch does
    c["B.SWAMP_ID"] = int(B.SWAMP_ID)
    c["B.RED_DESERT_ID"] = int(B.RED_DESERT_ID)
    noise = {}
    for name in ("_TEMP_NOISE", "_TEMP_NOISE_FINE", "_PRECIP_NOISE", "_SNOW_NOISE", "_SNOW_NOISE_FINE", "_SWAMP_NOISE"):
        n = getattr(B, name)
        if n.noise_type.name != "NoiseType_Perlin" or n.fractal_type.name != "FractalType_FBm":   # tiles_noise
            raise ValueError(f"biomes.{name}: the native noise is Perlin + FBm only")
        noise[name] = {"seed": int(n.seed), "frequency": f32(n.frequency), "octaves": int(n.fractal_octaves),
                       "lacunarity": f32(n.fractal_lacunarity), "gain": f32(n.fractal_gain),
                       "weighted": f32(n.fractal_weighted_strength)}
    m["tiles"] = {
        "biome_id": {k: int(v) for k, v in B._BIOME_ID.items()},
        "OCEAN_IDS": [int(v) for v in B.OCEAN_IDS],
        "SWAMP_HOST_IDS": [int(v) for v in B.SWAMP_HOST_IDS],
        "SOIL_SWAMP_M3": [float(v) for v in B.SOIL_SWAMP_M3],
        "D8_OCTANT": [int(v) for v in T._D8_OCTANT],
        "ALPINE_TREE_IDS": [int(v) for v in T.ALPINE_TREE_IDS],
        "ALPINE_SNOWY_TREE_IDS": [int(v) for v in T.ALPINE_SNOWY_TREE_IDS],
        "block_look": int(getattr(gen, "block_look", 0) or 0),
        "noise": noise,
        "biomenet": gen.biomenet is not None,
        "default_climate_layout": getattr(gen, "default_climate_layout", "belts"),
        "channels": {ch.name: {"scale": f32(ch.scale), "offset": f32(ch.offset), "slot": ch.slot}
                     for ch in SC.CHANNELS},
        "channel_order": [ch.name for ch in SC.CHANNELS],
        "providers": [name for name, mod in _PROVIDERS if _m(mod, "provider")(gen) is not None],
    }
    c["G.river_log_threshold"] = f32(gen.cfg.river_log_threshold)
    # The sidecar networks' engine models (biome / vegetation / surface: the plans the Python proxies run).
    be = getattr(gen, "_cenda", None)
    for name, mod in (be.models.items() if be is not None else ()):
        if name.startswith(("biome_", "veg_", "surface_")):
            m["models"][name] = {"path": str(mod.path), "max_batch": 1}
    _contribute_nodes(gen, m)
    from terrain_slm.models import biomenet as BN
    if gen.biomenet is not None:
        t = m["tiles"]
        t["biomenet_extras"] = bool(gen.biomenet.cfg.extras)
        t["biomenet_n_in"] = BN.N_IN + (BN.N_EXTRA if gen.biomenet.cfg.extras else 0)
        t["biomenet_classes"] = [int(v) for v in BN.CLASS_IDS]
        if float(BN.PIXEL_M) != 60.0:
            raise ValueError("biomenet.PIXEL_M changed: update tiles_bn_input")


def _contribute_nodes(gen, m: dict) -> None:
    """Tables of the native surface providers (stages/tiles/nodes.cpp), from the live node modules."""
    from terrain_slm.export.native_manifest import f32
    from terrain_slm.world import surface_node as SFN
    from terrain_slm.world import vegetation_node as VEGN
    t = m["tiles"]
    if getattr(gen, "vegnet", None) is not None:
        from terrain_slm.models import vegnet as VN
        if float(VN.PIXEL_M) != 60.0:
            raise ValueError("vegnet.PIXEL_M changed: update tiles_veg_input")
        idx = {name: k for k, name in enumerate(VN.OUTPUTS)}
        t["veg"] = {"n_in": int(VN.N_IN), "outputs": list(VN.OUTPUTS), "LAPSE_C_PER_M": f32(VN.LAPSE_C_PER_M),
                    "channels": [{"channel": ch, "index": idx[name], "scale": f32(sc)} for ch, name, sc in VEGN._CHANNELS]}
    if getattr(gen, "surfacenet", None) is not None:
        from terrain_slm.models import surfacenet as SN
        if (SN.FIELDS[:4] != ("rock_exposure", "scree", "smooth", "roughness") or SN.FRACTIONS != 3
                or SN.N_FIELDS != 6 or float(SN.PIXEL_M) != 60.0):
            raise ValueError("surfacenet fields / activate changed: update tiles_surf_activate")
        t["surface"] = {"n_in": int(SN.N_IN), "n_fields": int(SN.N_FIELDS), "sub": int(SN.SUB),
                        "fractions": int(SN.FRACTIONS),
                        "channels": [{"channel": ch, "index": int(k)} for ch, k in SFN.CHANNEL_OF.items()]}
