# Saving & exporting (agents)

Every file an agent writes goes through one sandbox + one policy + one dialog.

**Roots** (`save_targets` lists them with sub-folders):
- `project:` — the open .omp's folder (default for .omo/.omt/.omanim; scenes → `Scenes/`)
- `game:` — `stonebreak-game/src/main/resources` (default for .sbo → `sbo/<type>/`, .sbe → `sbe/Mobs/`)
- `exports:` — `~/.openmason/exports` (default for .png)

**Path grammar** for every `file_path`: absolute · `project:models/x.omo` · `game:sbo/blocks/SB_X.sbo` ·
bare `SB_X` (kind's default root + sub-folder; extension added). Anything outside the roots is refused
(`path_outside_sandbox`) — no dialog, no write.

**When the user is asked** (policy `ASK_RISKY`, Preferences → Assistant → Agent File Writes):
- new file in `project:`/`exports:` → written silently (status toast)
- overwrite of an existing file, or anything under `game:` → the in-app **Save Sheet** opens
- no `file_path`, or `prompt:true` → Save Sheet
- `ASK_ALWAYS` asks for everything; `ASK_NEVER_IN_PROJECT` lets `overwrite:true` replace silently outside `game:`.

The Save Sheet is a modal the human answers (root, folder, name, existing files, overwrite warning,
countdown). While it is open, `ui_prompt_status` says so — explain the wait, do not retry blindly.
Outcomes are structured: `{ok, status: saved|declined|failed, path, root, overwritten, promptedUser, reason, message}`.
A decline is `reason: user_declined|timeout|busy|prompt_unavailable`, never a protocol error.

**Tools**
- `model_save` / `tex_save_project` / `anim_save` / `scene_save`: without `file_path` they re-save the
  open file in place (silent — it is the user's own working file) or open the sheet for untitled work.
- `model_open` / `model_new`: replacing unsaved work asks through the approval dialog (Approve / Save current first / Decline).
- `sbo_export` / `sbe_export`: current model → new asset. The .omo is saved first when needed (may open the
  sheet), parameters get the export windows' defaults (next free `numericId`, free atlas slot), the result is
  handed to the SBO/SBE editor window like the UI export. Source files inside `params` (clips, override models,
  sound samples) must live inside a root.
- `sbo_editor_open/get/set/save`, `sbe_editor_*`: drive the editor windows the user sees. `get` returns the
  draft manifest; `set` patches metadata / gameProperties / sounds / drops (states and embedded bytes stay);
  `save` validates like the Save button and writes through the sandbox — a shipped asset is under `game:`, so it asks.

**`sbo_export` params** — `objectId`, `objectName`, `objectType` (block|item|entity|decoration|particle|other),
`objectPack`, `author`, `description`, `gameProperties{numericId, hardness, solid, breakable, atlasX, atlasY,
renderLayer, transparent, flower, stackable, maxStackSize, category, placeable}`, `states[{name, omo?, clip?,
loop?: clip_default|loop|once}]`, `defaultState`, `sounds[{event, file?|resource?, volume, pitchMin, pitchMax,
variation}]`, `drops{drops[{objectId, min, max, chance}], toolOverrides[{tool, drops[]}]}` (`"drops": []` =
drops nothing; absent = game default). Missing fields get the export window's defaults: next free `numericId`,
a free atlas slot, `stonebreak:<slug>` id, the OS user as author.

**`sbe_export` params** — `objectId`, `objectName`, `entityType` (mob|npc|projectile|vehicle|other), `objectPack`,
`author`, `description`, `states[{name, model?, clip?}]`, `variants[{name, model?}]`, `sounds[…]`.

**`*_editor_set` patch** — SBO: `objectId`, `objectName`, `objectType`, `objectPack`, `author`, `description`,
`gameProperties{…}` (merged; `null` removes), `defaultStateName`, `fuel{burnTicks}|null`, `sounds[]`
(`resource:` or an already-embedded `filename`), `drops{…}|null`. SBE: the metadata fields, `entityType`, `sounds[]`.
