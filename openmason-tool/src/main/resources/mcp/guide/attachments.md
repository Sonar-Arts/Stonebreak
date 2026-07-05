# Attachment Points (sockets)

Named local frames bound to a part, where OTHER models mount at runtime (hats,
armor, held items). A socket's transform (pos + rot + scale) places, orients
AND scales the attached model: its world frame is `hostPart · T·R·S`.

- `attach_list` — id, name, host part, transform.
- `attach_create {name, parent_part, position?, rotation?, scale?}` — parent_part
  is the hosting part (id or name); position/rotation are in that part's
  rest-pose local space.
- `attach_set_transform`, `attach_set_parent`, `attach_rename`, `attach_delete`.
- Undo: `undo` / `redo` with domain:"attach" (own history).

Convention: name sockets by mount purpose ("Hatzone", "HandR"). +Z of the
socket is the attached model's forward. Sockets save into the .omo and carry
through SBE/SBO automatically.
