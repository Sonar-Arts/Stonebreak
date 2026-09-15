# Asset lens (SBO/SBE eyeglass)

Read the game's shipped assets straight from disk — no editor session, no
external scripts. All tools address assets by `objectId` (namespaced, e.g.
`stonebreak:oak_door`); the file name (`SB_Oak_Door` / `SB_Oak_Door.sbo`) and
the id without namespace also resolve. Paths are accepted only inside the
game resource tree.

- `asset_list {query?, kind?, type?, limit?, offset?, refresh?}` — ranked
  search over every SBO (blocks/items/models) and SBE (all sbe/ folders).
- `asset_manifest {asset}` — full manifest (gameProperties, recipes, drops,
  sounds) + states/variants inventory.
- `asset_mesh_summary {asset, state?, variant?}` — parts, counts, bounds,
  materials, attachment points.
- `asset_face_data {asset, part?, face_ids?, offset?, limit?}` — per-face
  winding loop, geometric normal/orientation, material + UV region.
  Paginated (32/page, max 128).
- `asset_texture_describe {asset, material?, rect?, ...}` — glyph grid of a
  material PNG or the flattened default OMT (multi-layer composite, never a
  single layer). Textures over 64px are downsampled unless rect is given.
- `asset_texture_export {asset, material?, out?, inline?}` — PNG into
  `~/.openmason/exports/<assetId>/`; never writes into game resources.
  `inline:true` returns the image for viewing.
- `asset_check_winding {asset, state?, variant?, part?}` — winding validator
  (inverted/degenerate/non-planar faces, face-id contract violations).
  Open/flat geometry (panes, sprite crosses) reports `indeterminate` —
  informational, not an error.
- `asset_open {asset, state?, variant?, timeout_seconds?}` — APPROVAL-GATED:
  the user must accept an in-tool dialog. Loads a COPY of the embedded model
  into the editor (the SBO/SBE file is never written; Save becomes Save As).
  The call blocks until answered; declines return
  `{opened:false, reason:"user_declined"|"timeout"|"busy"}` — do not retry a
  decline; ask the user what they prefer.

Read-only tools run off the UI thread and never require a loaded model. The
live-model counterparts are `model_describe` / `model_check_winding`; the
live session and the asset files can diverge after edits.
