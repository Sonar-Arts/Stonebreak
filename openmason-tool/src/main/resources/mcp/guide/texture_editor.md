# Texture Editor (canvas, layers, filters — driven remotely)

The texture editor is a layered pixel canvas. You can summon it yourself; the
user does not have to open it.

## Session
1. `tex_open_editor {face_id}` — open a model face exactly like the property
   panel's "Edit Texture" button: material created if missing, GPU pixels
   loaded, canvas masked to the face polygon, edits live-preview on the model.
   `tex_open_editor {width, height}` starts a fresh standalone canvas;
   no args just shows the window.
2. Paint with the `tex_*` tools below (all target the ACTIVE layer and honour
   the face mask + selection, like the mouse does).
3. `tex_flush` pushes edits to the model (then `viewport_capture`);
   `tex_close_editor` flushes, ends the face session and auto-saves the .omo.
`tex_editor_status` tells you window visibility, face session, canvas, tool,
symmetry and selection at any time. `.omt` projects: `tex_load_project` /
`tex_save_project`.

## Reading without vision — `tex_describe`
Returns the canvas as a glyph grid (one glyph per colour, `.` transparent)
with row/column rulers, a legend (glyph → hex, rough colour name, count, %),
opaque bounds, left-right / top-bottom symmetry scores (1.0 = mirror), orphan
pixels (lonely dots that read as dirt) and optional per-row run-lengths
(`rle:true` → `y3: A@2-5 B@6`). `tolerance:24` merges noise shades so the
shape stays legible; `layer:-1` reads the visible composite. Read this
instead of `tex_get_region` — ~4 bytes/pixel vs ~16. `model_face_describe`
does the same for any face with no editor open.

## Writing from text — `tex_paint_grid`
```json
{"rows": ["..AA..", ".ABBA.", "AABBAA"],
 "legend": {"A": "#3a2a1a", "B": "210,160,90"}, "x": 4, "y": 2}
```
`.` leaves pixels alone (`clear_dots:true` erases them), space always skips.
Loop: describe → edit the text → paint it back. `model_face_paint_grid` is the
sessionless twin.

## Layers
`tex_list_layers`, `tex_layer_add`, `tex_layer_remove`, `tex_layer_duplicate`,
`tex_layer_move {from,to}` (0 = bottom), `tex_layer_set {index, active,
visible, name, opacity}`, `tex_layer_merge_down`. Compositing is straight
alpha-over bottom→top scaled by opacity; there are no blend modes. Typical
build: base fill on layer 0 → `tex_layer_add "shade"` → paint → `tex_noise`
on a duplicate for grain at opacity 0.3 → merge down.

## Drawing + filters (active layer, one undo step each)
`tex_fill` (whole/rect; `[0,0,0,0]` clears), `tex_rect` (filled/outline),
`tex_ellipse`, `tex_line`, `tex_flood`, `tex_set_pixels`,
`tex_noise {generator: simplex|value|white, seed, strength, scale, gradient,
blur, octaves, spread, edge_softness}` — perturbs RGB, keeps alpha: fill
first. `tex_outline` grows a 1-px border (or `inside:true` recolours the edge
row); with no colour each pixel is a darker, cooler shade of its neighbour —
never flat black. `tex_set_selection {rect}` constrains everything after it.

## Handing the user a setup
`tex_set_tool {tool, color}` picks the interactive toolbar tool (Pencil,
Eraser, Fill, Line, Shapes, Color Picker, selections…) and paint colour;
`tex_set_symmetry {mode: none|horizontal|vertical|quadrant, offset_x,
offset_y, show_axes}` mirrors their brush strokes.

## Pixel-art habits that read well at 16px
- Shade with 3–4 tones per material (dark / base / light / highlight); pick
  a ramp, then paint by glyph.
- Outline with `tex_outline`, not black.
- Texture with `tex_noise` at low strength on its own layer, then check the
  composite with `tex_describe {layer:-1, tolerance:24}` — if the legend
  explodes, the noise is too strong.
- Check `symmetry` in the describe result when the design should mirror, and
  `orphanPixels` before calling it done.
- Undo domain is `texture` (`undo {domain:"texture"}`); scripted multi-step
  work (`om.canvas` via `run_python_script`) is one undo entry.
