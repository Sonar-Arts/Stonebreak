# Open Mason MCP — Overview

Open Mason edits voxel-game models (.omo): named **parts** (primitive shapes with
transforms, optionally parented into a hierarchy) whose faces carry per-face
textures. Optional **bones** (animation skeleton) and **attachment sockets**
(mount points for other models).

## Conventions
- **Y-up**, right-handed. north = -Z, south = +Z, east = +X, west = -X.
- Rotations are **Euler XYZ in degrees**. Sizes are full extents in model units.
- Parts are addressed by **name or id** everywhere (`id_or_name`). Names are
  case-sensitive; keep them unique.
- Face/vertex/edge indices are **part-local** (0-based). Get them from
  `part_mesh`, or select faces by direction in scripts (`facing:"+y"`).
- A part's transform: `origin` (pivot) → scale → rotate → `position`.

## Workflow
1. `model_summary` — orient yourself (parts, counts, bbox, bones, sockets).
2. **Multi-step edits: use `run_python_script`** (the `om` API — loops and math
   beat many single calls; see topic `scripting`) or `run_model_ops` (JSON).
   One call = one undo entry, atomic rollback on failure.
3. Single tweaks: the per-domain tools (`part_transform`, `scale_faces`, ...).
   Mutations return terse acks; pass `verbose:true` when you need full state.
4. `viewport_capture` — see the model (select a part first to frame it).
   No vision? `model_face_describe` / `tex_describe` render pixels as a
   glyph grid with legend + symmetry/orphan stats.

## Undo domains
`undo` / `redo` take a `domain`: model (default — parts, geometry, face
textures; a whole script run is one step), texture (texture editor), bone,
attach (sockets), anim. The five histories are separate.

## Topics
`parts`, `face_textures`, `bones`, `attachments`, `animation`, `scripting`,
`texture_editor` (summon/drive the layered editor; text pixel art via
`tex_describe`/`tex_paint_grid` for vision-less reading), `recipes`
