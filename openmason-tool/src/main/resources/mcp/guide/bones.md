# Bones (animation skeleton)

Bones form a hierarchy used by the animation editor; they don't deform the mesh
directly (parts bind to bones by name at animation time).

- `bone_list` — id, name, parent, plus resolved world head/tail positions.
- `bone_create {name, parent_bone_id?, origin?, position?, rotation?, endpoint?}`
  — position is the bone head relative to its parent; endpoint is the tail.
- `bone_set_transform`, `bone_set_parent`, `bone_rename`, `bone_delete`.
- `bone_select` / `bone_clear` control the editor selection.
- Undo: `undo` / `redo` with domain:"bone" (own history).

Convention: one root bone (e.g. "root" at the model origin), children per
articulated part ("head", "leg_fl", ...), names matching the parts they drive.
Model_summary lists the skeleton compactly.
