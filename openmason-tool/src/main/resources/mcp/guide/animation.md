# Animation (.omanim clips)

The animation editor edits one clip at a time: per-part tracks of keyframes
(position/rotation/scale + easing), clip metadata (name/fps/duration/loop), and
layer metadata for mixing (BASE/OVERLAY + part mask + fades + priority).

Easing per keyframe: LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, STEP (hold the
pose, snap at the next key — blinks, gear ticks).

## Reading
`anim_get_info`, `anim_list_tracks`, `anim_list_keyframes {part_id_or_name}`.

## Editing
- `anim_insert_keyframe {part_id_or_name, time, position?, rotation?, scale?,
  easing?}` — creates the track on demand.
- `anim_edit_keyframe` / `anim_delete_keyframe` / `anim_delete_track`.
- Clip meta: `anim_set_clip {name?, fps?, duration?, loop?}`.
- Layers: `anim_set_layer {type?, mask_parts?, fade_in_seconds?,
  fade_out_seconds?, priority?}` — BASE drives all parts, OVERLAY only its mask.

## Transport & files
`anim_transport {action: play|pause|stop|seek, time?}`, `anim_apply_pose` (pose the
viewport at the playhead), `anim_new_clip`, `anim_load`, `anim_save {file_path?}`.
Undo: `undo` / `redo` with domain:"anim" (own history).

Workflow: pose at t=0 (insert keyframes for every animated part), advance the
playhead, pose again — keep the last keyframe time equal to the clip duration
for clean loops. Verify with `anim_apply_pose` + `viewport_capture`.

Preview safety: the viewport shows the clip's pose while the editor is open,
but the model's own rest transforms are what get saved (the .omo save puts the
model at rest for the write) and modelling edits made meanwhile are kept.
In the UI, **Auto-key** turns gizmo/property edits into keyframes at the
playhead (one drag = one undo step); an unkeyed edit of an animated part is
flagged in the timeline header. Tracks whose part is missing from the model
show as "? name" rows and can be rebound or deleted from the row's context
menu. An OVERLAY can be previewed on top of a loaded BASE clip (Layer
section → Preview base). SBE/SBO state editors round-trip clips with the
editor ("From editor" / "Edit in editor") without touching disk.

## Scripted authoring (bulk keyframing)
For gait cycles, sine bobs, or many keys at once, prefer `run_python_script`
with `om.anim` (see topic `scripting`): build a detached clip with loops+math,
`save()` it as .omanim, then `anim_load` it here to inspect and play.
