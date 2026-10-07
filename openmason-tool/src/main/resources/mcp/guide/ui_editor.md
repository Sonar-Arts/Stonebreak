# UI Editor (Stonebreak UI documents: .omui / .sbui)

Agents edit UI documents through the same command layer as the author: every `ui_ops` call is
ONE undo step in the History panel (the author can undo it), and nothing here touches Open
Mason's own panels, layout or settings. Start with `ui_documents`.

## Documents
`ui_new {id, template?, name?}` (templates: blank_screen, menu_screen, blank_component,
button_component) · `ui_open {path}` (.omui; .sbui opens as an editable copy, never written
back) · `ui_activate` · `ui_close {discard?}`. Tools take `doc` (document id, file name or
title); omitted = the active document.

## Keys, not names
- An element's **key** is its node id (`panel`). Inside a component instance the key is
  `<instance>/<node>` (`quit/label`, nested `inner/leaf`): `ui_tree {internals:true}` lists them.
- **Names** (`#name`) are style handles, unique per document; rename rewrites `#old` rules.
  Anywhere a key is expected, `#name` (or `#name/inner`) is accepted and resolved to the id.
- Ids are minted on create (`label`, `label_2`, ...) unless `id` is given. Bind new elements
  with `"as":"x"` and refer to them later in the same batch as `$x` (or `$x/inner`).

## Overrides
`set_prop` / `set_style` / `set_classes {add, remove}` on an internal key write an **instance
override** (the component file is untouched). `reset_override {keys}` = reset to source.
`set_param {key:<instance>, param, value}` sets an exposed component parameter (null resets).

## ui_ops
```json
{"ops":[
 {"op":"create","type":"Box","name":"panel","style":{"width":400,"padding-top":16},"as":"panel"},
 {"op":"create","parent":"$panel","type":"Label","props":{"text":"Paused"},"classes":["title"]},
 {"op":"create","parent":"$panel","type":"Label","bindings":[{"target":"prop:text","path":"session.name"}]},
 {"op":"add_instance","parent":"$panel","component":"stonebreak:ui/components/stone_button","as":"btn"},
 {"op":"set_prop","keys":"$btn/label","prop":"text","value":"Resume"},
 {"op":"add_sheet","id":"hud"},
 {"op":"add_rule","sheet":"hud","selector":"#panel","style":{"background-color":"#203040"}},
 {"op":"set_token","sheet":"hud","name":"--accent","value":"#E8D9A8"}
]}
```
Validation runs first (unknown op/field, wrong type, unbound `$x` → nothing runs). A failure
while running names `op i` and the reason; the document is unchanged. Edits the .omui writer
would refuse (bad binding path, undeclared provider widget, colours not `#RRGGBB[AA]`, keys of
component elements that do not exist) fail at their op too, so an `ok` batch always saves. Style values: numbers
are logical px, strings are keywords/colours/`var(--token)`; `null` removes a declaration.
`set_rule {sheet, rule: index|selector, selector?, style?}`. Clips: `put_clip {clip:{id,
duration, loop?, tracks:[{target, property, keys:[{time, value, easing?}]}]}}`.
Optional features the document uses are added to the manifest's `requires` automatically.

Assets, graphs, state machines (same batch, same single undo step; project writes are undone too):
```json
{"ops":[
 {"op":"add_dependency","path":"UI/stonebreak/ui/textures/panel.sbt"},
 {"op":"add_dependency","path":"UI/stonebreak/ui/textures/slots.sprites.json"},
 {"op":"add_dependency","id":"stonebreak:ui/fonts/minecraft","kind":"font","license":"OFL"},
 {"op":"set_dependency","id":"stonebreak:ui/textures/panel","optional":true,"fallback":"stonebreak:ui/textures/plain"},
 {"op":"embed_dependency","id":"stonebreak:ui/textures/panel"},
 {"op":"put_graph","graph":{"id":"behaviors","nodes":[],"edges":[]}},
 {"op":"put_state_machine","machine":{"id":"button","driver":"interaction","element":"resume","initial":"normal",
   "states":[{"name":"normal"},{"name":"hover","clip":"lift"}],"transitions":[{"to":"hover","blend":0.1}]}}
]}
```
`add_dependency`: `path` (project file; kind from the extension, a `.sprites.json` also adds its
texture) or `id` + `kind` (texture|sprites|image|component|stylesheet|script|font|sound) found in
the project or the game's packaged assets; `embed:true` snapshots it. `remove_dependency` refuses
while anything still references the id (`force:true` overrides). `extract_dependency {collision:
fail|keep_project|replace}`, `relink_dependency {id, path}`, `refresh_dependency {id}`.

## Host contracts
A document reads host data (`session.online`) and calls host actions (`stonebreak:screen.pause.resume`)
only under the contracts its manifest declares (`hostApis`); undeclared roots read as failed and the
game refuses the screen. `set_host_api {id, version, optional?}` declares one (`version: null` removes
it); `set_provider` does the same for draw providers (`stonebreak:item-icon`). Every export's
`hostCheck` lists what the game host knows.

## Inspect
`ui_tree`, `ui_get {key, computed?}` (computed values + origin: `rule sheet#i selector`,
inline, override, binding, local, animation), `ui_style_sheets {key?}`, `ui_diagnostics`.

## Preview (runtime only — never source, never dirty)
`ui_preview {mode:"preview"}` runs code-behind, graphs and fixture data with real input;
`mode:"design"` throws that runtime away. In design, `force {key:["hover"]}` shows
pseudo-states. Frame: `width/height` (device px), `ui_scale`, `pixel_ratio`.
`ui_preview_input {events:[{type:"click",key:"resume"}], advance_ms}` · `ui_console`.
`ui_capture {source: design|preview|auto, max_size}` returns the engine-painted PNG.

## Files (write sandbox + Save Sheet, see topic saving)
`ui_save` → in place, or for a new document its **convention path** `UI/<ns>/<path>.omui`
(so screens resolve project components; save a component before placing it elsewhere).
`ui_save_as {path | prompt:true, overwrite?}` saves elsewhere (it becomes the document's file).
Re-saving a file under `game:` asks the user; a document with a newer
crash-recovery copy cannot be saved until the author Restores or Discards it (`ui_documents`
shows `recovery`; the editor's own autosave never counts). A re-save of a file someone changed
on disk meanwhile is refused unless `overwrite:true`. `ui_close {discard:true}` keeps the unsaved
changes in crash recovery for the author. `ui_export {mode: shared|collect_all}` →
`Exports/UI/<stem>.sbui` + report; every export reports `hostCheck` (what the real game host
would refuse: unknown data roots, contracts, actions). `ui_export {deploy:true}` ships it to the
game: `game:ui/documents/<stem>.sbui` and, in shared mode, each shared asset under
`game:ui/shared/<ns>/<path>` (asks the user; never deploys an export the game would refuse).
`ui_import_sbui {path}` imports into the project (new files only).

## Scripting
`run_python_script`: `om.ui.create(...)`, `om.ui.set_style(...)` etc. queue the same ops and
apply them as one `ui_ops` batch when the script succeeds (see topic scripting).
