# Scripting (the preferred way to build models)

`run_python_script` executes sandboxed Python with the `om` API. One call =
one undo entry; a failed script rolls back completely. Loops + math produce
clean symmetric geometry that would take dozens of single-tool calls.

```python
import om, math

body = om.box("body", size=(8, 3, 6), at=(0, 3, 0))
for i in range(4):                       # radial/legged symmetry via loops
    a = i * math.pi / 2
    om.cylinder(f"leg{i}", size=(1, 3, 1),
                at=(math.cos(a) * 3, 1.5, math.sin(a) * 2), parent=body)

hump = body.faces(facing="+y").extrude(1.0)   # returns the NEW side faces
hump.inset(0.2)
om.material("shell", tint=(220, 80, 40))
body.faces(facing="+y").set_material("shell")
print(om.summary())                      # stdout comes back in the result
```

## API sketch (print(om.help()) for the full cheatsheet)
- Create: `om.box/cylinder/sphere/cone/pyramid/plane/wedge/torus/hemisphere/
  cross/sprite(name, size=(x,y,z), at=(x,y,z), rotate=(x,y,z), parent=p)`
- Parts: `om.part(name)`, `om.parts()`, `p.move/rotate/scale` (deltas, chainable),
  `p.set_position/set_rotation/set_origin`, `p.duplicate(name, offset=..)`,
  `om.mirror(p, axis="x")` (winding-safe symmetric limbs), `p.delete()`
- Faces: `p.faces(facing="+y")` or `p.faces([ids])` → `.extrude(d)`, `.inset(a)`,
  `.scale(f)`, `.delete()`, `.set_material(name)`, `.set_uv((u0,v0,u1,v1))`
- Vertices: `p.subdivide_edge(a, b, t)`, `p.set_vertex(i, pos)`, `p.vertex(i)`,
  `p.set_geometry(verts, tris, faces=[...])` (any custom shape)
- Materials: `om.material(name, tint=(r,g,b), emissive=False)` — data-level;
  pixels are painted with `om.tex` (below) or the face-texture tools.
- Face textures: `t = om.tex.create(p.faces(facing="+z"), size=(16,16),
  color=(200,120,60))` — ONE shared material+texture for the selection;
  chain `t.fill/rect/line/flood/set_pixels/noise/resize/get_region/info`;
  `om.tex.of(part, face)` for existing textures; `sel.texture(...)` shorthand.
  LIVE viewport only — headless runs get a teaching error (author data-level
  materials/UVs instead). Pixel edits flush to the GPU immediately, yet the
  run is still ONE model undo entry; a failed script rolls back painted
  pixels and script-created textures too.
- Canvas: `om.canvas.fill/rect/line/flood/set_pixels/noise` paint the texture
  editor's ACTIVE layer (honors the shape mask + active selection); layers via
  `add_layer(name)`, `remove_layer(i)`, `set_layer(i, active/visible/name/
  opacity)`; `om.canvas.info()/layers()/get_region(...)`. Requires the texture
  editor window to be OPEN (live-only; teaching error otherwise).
  `om.canvas.export("/abs/out.png")` is DEFERRED like `c.save`: absolute path
  required, validated now, written only if the whole script succeeds, and
  listed in the result's files.
- Animation: `c = om.anim.clip("idle", duration=2.0, fps=30, loop=True)`;
  `c.key(part, t, position=…, rotation=…, scale=…, easing="ease_in_out")` —
  omitted components come from the part's current transform, so pose the parts
  then key them; `c.layer(type="overlay", mask=[parts])`;
  `c.save("idle.omanim")`. Clips are DETACHED (they never touch the animation
  editor); files are written only if the whole script succeeds. Live runs need
  an absolute save path — load the result with `anim_load`.
- Query: `om.summary()`, `p.info()`, `c.info()`

## Undo: two histories
Model edits (parts, geometry, `om.tex` pixels) are ONE entry in the model
history; `om.canvas` edits are ONE entry in the texture editor's OWN history.
A mixed script produces one entry in each — `undo {domain:"model"}` AND
`undo {domain:"texture"}` are both needed to fully revert it.

## texture_* / canvas_* ops (JSON twin)
- `texture_create {part, faces, size:[w,h], color?, name?}` (faces: [ids] /
  {"facing"} / {"ref"})
- `texture_set_pixels {part, face, pixels:[x,y,r,g,b,a,...]}`
- `texture_fill {part, face, color, rect?}` · `texture_rect {part, face,
  rect, color, filled?}` · `texture_line {part, face, from, to, color}` ·
  `texture_flood {part, face, at, color}`
- `texture_noise {part, face, generator:simplex|value|white, seed?, strength?,
  scale?, gradient?, blur?, octaves?, spread?, edge_softness?}`
- `texture_resize {part, face, size:[w,h]}`
- `canvas_set_pixels {pixels}` · `canvas_fill {color, rect?}` ·
  `canvas_rect {rect, color, filled?}` · `canvas_line {from, to, color}` ·
  `canvas_flood {at, color}` · `canvas_noise {generator, ...same knobs}`
- `canvas_add_layer {name}` · `canvas_remove_layer {index}` ·
  `canvas_set_layer {index, active?, visible?, name?, opacity?}` ·
  `canvas_export_png {path}` (deferred, absolute)

## Sandbox & limits
Python core modules + `om` only — no files, network, imports, or threads.
Default timeout 30 s; runs block the editor thread, so keep scripts short.
Errors return a short message + line number + hint (no tracebacks).

`run_model_ops` is the declarative twin (JSON `{"ops":[...]}`, validated fully
before executing; `validate_model_ops` dry-runs it). `include_trace:true` on a
Python run returns the equivalent JSON ops for replay/audit.
