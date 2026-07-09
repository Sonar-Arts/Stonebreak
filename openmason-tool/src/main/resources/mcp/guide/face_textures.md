# Face Textures (per-face pixels)

Every logical face can carry its own texture. Faces sharing a material share
ONE texture — painting via any face shows on all of them.

## om.tex — script painting (usual path)
```python
import om
head = om.part("head")
t = om.tex.create(head.faces(facing="-z"), size=(16, 16), color=(120, 90, 60))
t.rect(4, 5, 3, 2, (250, 250, 250), filled=True)          # chainable
t.line(2, 2, 13, 2, (60, 40, 30)).flood(8, 8, (140, 100, 70))
t.noise("simplex", seed=7, strength=0.3)
old = om.tex.of(head, 3)                                  # existing texture
```
`create` takes a FaceSelection, allocates ONE shared material+texture for it
(autoResize off) and returns a paint handle: `fill/rect/line/flood/set_pixels/
noise/resize/get_region/info`. Verify with `viewport_capture`. Pixels are
GPU-backed — live viewport only; headless runs get a teaching error and author
data-level materials/UVs instead (`define_material`/`set_face_material`/
`set_face_uv`).

## model_face_* — inspection + tiny one-shot edits
Sessionless (open/commit are gone) — each call edits the GPU texture directly.
1. `model_face_list_textures` — compact rows {faceId, materialId, w, h,
   orientation, part}; filter by `part` or `face_ids`; `detail:true` for UV
   region, suggested sizes, material names.
2. `model_face_create_textures {faces:[{face_id, width, height, color}]}` —
   create textures for MANY unmapped faces in one atomic call.
3. `model_face_get_region` (flat RGBA read), `model_face_set_pixels`
   (flat [x,y,r,g,b,a,...]), `model_face_fill` (whole texture or rect),
   `model_face_resize_texture`.

## Tips
- Colors are `[r,g,b,a]` 0..255. Batch pixel writes.
- Undo: model domain — a whole script run is ONE entry (painted pixels
  included; failure rolls back everything), each one-shot tool call one step.
- The texture editor canvas is a separate surface: `om.canvas`/`canvas_*` +
  the `tex_*` tools, with its own history (`undo` domain:"texture").
