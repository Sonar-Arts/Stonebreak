# Face Textures (per-face pixels)

Every logical face can carry its own texture. Two tool families:

## model_face_* — direct per-face GPU editing (usual path)
1. `model_face_list_textures` — compact rows {faceId, materialId, w, h,
   orientation, part}; filter by `part` or `face_ids`; `detail:true` for UV
   region, suggested sizes, material names.
2. `model_face_create_textures {faces:[{face_id, width, height, color}]}` —
   create textures for MANY faces in one atomic call (use suggested sizes from
   the listing; 16×16 is the usual voxel density).
3. Edit cycle per face: `model_face_open` → draw (`model_face_set_pixels`
   flat [x,y,r,g,b,a,...], `model_face_fill` (whole canvas or rect), `model_face_draw_line`,
   `model_face_flood_fill`) → `model_face_commit` (or `_discard`).
   Reads: `model_face_get_region` (flat RGBA — far cheaper than per-pixel).
4. Undo: part of the model history (`undo`, default domain).

## tex_* — the texture editor canvas (open editor required)
Layered pixel-art canvas with `tex_apply_noise`, layers, `tex_export_png`.
Same drawing verbs with a `tex_` prefix; own undo (`undo` with domain:"texture").

## Tips
- Batch: one `set_pixels` call with many pixels ≫ many single-pixel calls.
- Colors are `[r,g,b,a]` 0..255.
- Scripts assign *materials* (data-level: name/tint/layer) — pixels are still
  authored here. `set_face_uv` maps a face into a sub-region of its texture.
- Verify visually with `viewport_capture` after committing.
