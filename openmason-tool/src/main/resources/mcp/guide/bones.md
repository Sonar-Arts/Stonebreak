# Bones (rigging skeleton)

Bones are a modelling-side rig hierarchy saved in the .omo (v1.6+); they don't
deform the mesh. **Animation does not key bones**: `.omanim` tracks key parts
directly (by part id, name as fallback), and the game samples parts. Use bones
to author and visualise a hierarchy; give parts a parent (`set_part_parent`)
if you want them to move together in an animation.

- `bone_list` — id, name, parent, plus resolved world head/tail positions.
- `bone_create {name, parent_bone_id?, origin?, position?, rotation?, endpoint?}`
  — position is the bone head relative to its parent; endpoint is the tail.
- `bone_set_transform`, `bone_set_parent`, `bone_rename`, `bone_delete`.
- `bone_select` / `bone_clear` control the editor selection.
- Undo: `undo` / `redo` with domain:"bone" (own history).

Convention: one root bone (e.g. "root" at the model origin), children per
articulated part ("head", "leg_fl", ...), names matching the parts they drive.
Model_summary lists the skeleton compactly.
