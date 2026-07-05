# Recipes

## Build a quadruped mob (script)
```python
import om
body = om.box("body", size=(10, 6, 14), at=(0, 8, 0))
head = om.box("head", size=(6, 6, 6), at=(0, 11, -9), parent=body)
leg = om.box("leg_fl", size=(3, 5, 3), at=(3.5, 2.5, -4.5))
leg2 = leg.duplicate("leg_bl", offset=(0, 0, 9))
om.mirror(leg, axis="x", name="leg_fr")
om.mirror(leg2, axis="x", name="leg_br")
snout = head.faces(facing="-z").inset(1.0)   # muzzle detail
```
Then: `model_face_list_textures` → `model_face_create_textures` (batch, use
suggested sizes) → open/draw/commit per face. Verify with `viewport_capture`.

- Game-scale reference: the player is ~1.8 blocks tall; a cow-sized mob body is
  roughly 10×6×14 model units at 16 units per block.
- Mirror limbs instead of duplicating + editing — it keeps symmetry exact.
- Model the rest pose facing -Z (north).

## Texture a cube face
1. `model_face_list_textures {part:"head"}` → pick faceId + suggested size.
2. `model_face_create_textures {faces:[{face_id, width:16, height:16,
   color:[120,90,60,255]}]}`.
3. `model_face_open` → `model_face_set_pixels` (batch!) / `model_face_fill` /
   `draw_line` → `model_face_commit`.

## Rig + idle animation
1. Bones: `bone_create {name:"root"}`, then per limb with parent "root"
   (names matching parts).
2. Sockets if needed: `attach_create {name:"Hatzone", parent_part:"head"}`.
3. Clip: `anim_new_clip` → `anim_set_clip {name, duration, fps, loop}` →
   keyframes at t=0 and t=duration for a breathing bob
   (`anim_insert_keyframe {part_id_or_name:"body", time:0, position:[0,0,0]}` ...).
4. Check: `anim_transport {action:"seek", time}` + `anim_apply_pose` + `viewport_capture`.

## Refine an existing model
`model_summary` → `viewport_capture` → small script (`om.part("body").faces(
facing="+y").inset(0.5).scale(0.9)`) → capture again. Iterate visually; every
script run is one undo step.
