# Parts & Mesh Editing

A model is a list of named parts. Each part owns its geometry (vertices,
triangles grouped into logical **faces**) plus a transform and optional parent.

## Creating
- `create_part {shape, name, size}` — shapes: CUBE, PYRAMID, PANE, SPRITE
  (scripts additionally offer cylinder/sphere/cone/wedge/torus/hemisphere/cross).
- `duplicate_part`, and in scripts `om.mirror(part, axis="x")` for symmetric
  limbs (winding-safe — always prefer mirror over scale -1).

## Transforms
- `part_transform` — absolute fields (origin/position/rotation/scale, omit to
  keep) plus delta fields (translate / rotate / scale_by).
- `origin` is the pivot the part rotates/scales around (part-local).
- Hierarchy: parent parts to move limbs with a body (`set_parent` in scripts).

## Mesh topology (part-local face ids from part_mesh)
- `extrude_faces {local_face_ids, distance}` — each face along its own normal;
  returns the NEW side-quad ids; the original id keeps the moved cap.
- `inset_faces {local_face_ids, amount}` — even border of new quads per face;
  the original id keeps the inner cap.
- `scale_faces {local_face_ids, factor}` — shrink/grow faces in place.
- `subdivide_edge {edge_index, t}` — insert a vertex on an edge.
- `part_move {element: vertex|edge|face, index, xyz}` — positional nudges.
- `set_part_geometry` — replace a part's mesh wholesale with your own
  vertices/indices/face mapping (build any shape from scratch).

Typical detailing loop: select faces → inset (creates a border) → extrude the
inner cap outward/inward → scale. Verify with `viewport_capture`.

## Reading
`list_parts` (compact rows; `detail:true` for transforms), `get_part`,
`inspect_part` (bounds, matrix; array flags off by default), and `part_mesh`
(vertices/edges/faces topology, pick sections with `include`).
