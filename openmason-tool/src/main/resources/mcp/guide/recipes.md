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
Then texture it with `om.tex` (next recipe), or `model_face_list_textures` →
`model_face_create_textures` (batch, use suggested sizes) →
`model_face_fill`/`set_pixels`. Verify with `viewport_capture`.

- Game-scale reference: the player is ~1.8 blocks tall; a cow-sized mob body is
  roughly 10×6×14 model units at 16 units per block.
- Mirror limbs instead of duplicating + editing — it keeps symmetry exact.
- Model the rest pose facing -Z (north).

## Texture a mob part (script)
```python
import om
head = om.part("head")
t = om.tex.create(head.faces(facing="-z"), size=(16, 16), color=(120, 90, 60))
t.rect(3, 5, 2, 3, (250, 250, 250), filled=True)    # left eye
t.rect(11, 5, 2, 3, (250, 250, 250), filled=True)   # right eye
t.noise("simplex", seed=7, strength=0.25)           # fur grain
```
One shared texture covers the whole selection. Live viewport only; the run is
one model-domain undo entry. Verify with `viewport_capture`.

## Paint the editor canvas via script
Requires the texture editor window to be OPEN.
```python
import om
om.canvas.fill((90, 60, 40, 255))
om.canvas.add_layer("shade")                        # becomes the active layer
om.canvas.noise("value", seed=3, strength=0.4)
om.canvas.set_layer(1, opacity=0.6)
```
Verify with `canvas_capture` (visible composite, or one layer via `layer`).
Canvas edits land in the texture editor's own history (`undo` domain:"texture").

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
