# Formats and conventions — OMO / OMT / OMANIM / SBO / SBE

## The family
- OMO — model: mesh (triangle soup + triangleToFaceId), parts (transforms,
  vertex/index/face ranges, parent + bone links), face mappings (faceId →
  material + UV region + rotation), materials (embedded PNGs), bones,
  attachment points (v1.7+ named sockets: pos+rot+scale local frames other
  models mount to at runtime).
- OMT — layered texture archive: canvas size + ordered layers (name,
  visibility, opacity, PNG bytes). The editor's project format. Flatten =
  composite ALL visible layers in order.
- OMANIM — animation clips: tracks of keyframes per part (pos/rot/scale +
  easing), fps/duration/loop, v1.1 layering (BASE/OVERLAY + part masks).
- SBO — Stonebreak object (block/item): ZIP with manifest.json + either
  model.omo (model-bearing) or texture.omt (texture-only sprite items) +
  optional states/<name>/ (alternate models + clip.omanim), sounds/, and
  manifest data: gameProperties, crafting recipes, smelting, fuel, drops.
- SBE — Stonebreak entity (mob/npc/...): ZIP with manifest.json + model.omo
  + states/<name>/ (behavior clips, optional model overrides) + variants/
  <name>/ (identity model swaps, e.g. cow breeds) + sounds/.

## Conventions
- Y-up, right-handed; world units are blocks; 16 texels per block edge.
- Face winding: counter-clockwise viewed from outside = outward.
- States (SBE) are BEHAVIORAL (idle/wandering/grazing... map to AI states by
  lowercase clip name); variants are IDENTITY (texture/model alternates
  chosen at spawn). Don't encode identity as a state.
- Sounds: manifest sounds[] binds event names (break/hit/place/step for
  blocks; hurt/death etc. for entities) to embedded or shared samples with
  volume, pitch range and a variation flag.
- Drops (SBO v1.8): default drop entries + per-tool overrides; presence is
  authoritative (empty = drops nothing, absent = engine default rule).

## Editing rules of thumb
- The embedded model.omo is self-contained (textures inside). asset_open
  extracts it into the editor as an unsaved copy; exporting back to SBO/SBE
  goes through the export windows (which re-embed a fresh OMO).
- Never hand-edit bytes inside the archives; parsers verify SHA-256
  checksums (SBE enforces, SBO warns).
- objectId is the stable cross-reference key (recipes, drops, spawn tables)
  — renaming an objectId breaks references; renaming a file does not.
