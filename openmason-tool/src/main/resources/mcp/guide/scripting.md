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
  pixels are painted with the face-texture tools afterwards.
- Animation: `c = om.anim.clip("idle", duration=2.0, fps=30, loop=True)`;
  `c.key(part, t, position=…, rotation=…, scale=…, easing="ease_in_out")` —
  omitted components come from the part's current transform, so pose the parts
  then key them; `c.layer(type="overlay", mask=[parts])`;
  `c.save("idle.omanim")`. Clips are DETACHED (they never touch the animation
  editor); files are written only if the whole script succeeds. Live runs need
  an absolute save path — load the result with `anim_load`.
- Query: `om.summary()`, `p.info()`, `c.info()`

## Sandbox & limits
Python core modules + `om` only — no files, network, imports, or threads.
Default timeout 30 s; runs block the editor thread, so keep scripts short.
Errors return a short message + line number + hint (no tracebacks).

`run_model_ops` is the declarative twin (JSON `{"ops":[...]}`, validated fully
before executing; `validate_model_ops` dry-runs it). `include_trace:true` on a
Python run returns the equivalent JSON ops for replay/audit.
