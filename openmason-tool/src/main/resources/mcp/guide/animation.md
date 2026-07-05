# Animation (.omanim clips)

The animation editor edits one clip at a time: per-part tracks of keyframes
(position/rotation/scale + easing), clip metadata (name/fps/duration/loop), and
layer metadata for mixing (BASE/OVERLAY + part mask + fades + priority).

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

## Scripted authoring (bulk keyframing)
For gait cycles, sine bobs, or many keys at once, prefer `run_python_script`
with `om.anim` (see topic `scripting`): build a detached clip with loops+math,
`save()` it as .omanim, then `anim_load` it here to inspect and play.
