# UI Editor workspace (#293)

The Open Mason workspace for authoring Stonebreak UI screens and reusable components. It is
hardcoded Open Mason UI (ImGui + glyphs drawn into draw lists); its canvas shows the **engine's own
result**: documents run through `GameUiDocuments` (the game's runtime context, painter, fonts and
Masonry backend) on `MasonryPreview`.

Code: `openmason-tool/src/main/java/com/openmason/main/systems/uiEditor/`.

## Layers

| Layer | Package | What lives there |
| --- | --- | --- |
| Document | `document` | `UiEditorDocument` (immutable `OmuiArchive` source, file identity, origin, selection, history), `UiTree` (structural edits with structural sharing), `UiIds` (fresh ids/names, subtree remap), `Nodes` (copy-with helpers that keep unknown fields), `NodeLocation` (parent + slot + index) |
| Commands | `command` | `UiCommand`, `UiEditContext`, `UiHistory` (snapshot undo/redo, merge keys, saved serial = dirty), `NodeCommands`, `OverrideCommands`, `DocumentCommands`, `UiClipboard` |
| Services | `service` | `UiDocumentService` (create/open/SBUI copy/import/save/export/close), `UiProjectContext` (project `UI/` folder, asset sources, scan), `UiRecoveryService`, `UiDiagnosticsService`, `UiDocumentTemplates`, `UiSnapshot` (CPU render: thumbnails, smoke tests) |
| Canvas logic | `canvas` | `CanvasTransform`, `ResizeHandle`, `ResizeMath`, `SnapEngine`, `DropTargets`, `AlignDistribute`, `Box` (GL-free, unit-tested) |
| Views | `view` | `UiEditorWorkspace` (wiring, shortcuts, file flows, session), `DesignerRuntime`, `CanvasView`, panels, `UiEditorDialogs`, `widgets/` (`Glyphs`, `EditorWidgets`, `ValueFields`) |

Engine additions: `StyleResolver.trace` / `StyleTrace` + `UiDocumentInstance.styleTrace(el)` (the cascade
explained, same matching as styling), `HitTester.pickDesign` (design-time picking that ignores
`picking-mode`), `UiFeatures.used(archive)`, `GameUiDocuments.open(omui, projectSources, ...)`.

## Commands and undo

* Every source mutation is a `UiCommand`; `UiEditorDocument.execute` runs it against a
  `UiEditContext` (snapshot + selection + project writes) and records one `UiHistory.Step`
  (before/after archives, selections, `AssetEdit`s). Undo and redo swap snapshots, so they are exact by
  construction; a step with project writes reverts them first and stays applied if that fails.
* `UiCommand.compound` makes any number of commands one atomic step; a failing step aborts all of it
  (writes rolled back, document untouched).
* Merge keys: consecutive commands with the same key during one interaction (drag, slider, nudge)
  amend the top step; `endInteraction()` (mouse up, field deactivated, key released) seals it. A step at
  the saved position never absorbs merges.
* Dirty = the top step's serial differs from the saved serial (undo back to the save is clean).
* `UiHistory.withRequiredFeatures` adds every format feature the document uses (`UiFeatures.used`) to
  `requires` after each command, so the writer never refuses with `UNDECLARED_FEATURE`. Declared but
  unused features are kept.
* Identity: create/duplicate/paste mint ids (`UiIds.fresh`/`remap`, names too); rename and reparent
  never touch ids. Rename rewrites in-archive `#name` selectors (`UiReferences.renameImpact`).
* Copy/paste payload is a small OMUI archive (`UiClipboard`): nodes under a holder root plus the
  dependency rows they reference (with `requires` closure) and embedded bytes; on the system clipboard as
  `OPENMASON-UI-ELEMENTS/1:<base64>`. Paste adds missing rows and never replaces existing ones.
* Selection is element keys: document nodes are ids, component internals are `instance/inner` keys;
  their edits become `InstanceOverride`s (`OverrideCommands`), removing an override = reset to source.

## Design and preview

`DesignerRuntime` owns one runtime per document:

* **Design**: no scripts, bindings or input. Source edits apply with `UiDocumentInstance.reload` (state
  by key); a changed dependency table or embedded assets rebuild the view. Forced pseudo-states and
  designer-only visibility are runtime state (`setState`, local `visibility: hidden`), never source.
* **Preview**: a fresh view bound to the document's fixtures (`<file>.fixture.json`, else
  `editor/fixtures.json`, else, for a document that declares `stonebreak:` host contracts, the game's
  canonical fixture `GameUiHost.fixtureJson()`) with code-behind and graphs (debug build), fed real input through
  `PreviewInput` (pointer, wheel, keys while the designer is focused). Edits hot-reload
  (`instance.reload` + `scripts.reload`). Host requests are listed, never performed. Leaving preview
  discards the runtime, so preview can never write the source. While the document holds the keyboard,
  editor shortcuts stand down (`setNextFrameWantCaptureKeyboard`); while the designer shows a Preview,
  only View and File shortcuts reach the editor at all (`UiEditorWorkspace.allowedInPreview`), so
  Delete/Ctrl+Z/Ctrl+V pressed at a running screen never edit the source.
* All documents paint through ONE Skia GPU context (`MasonryPreview(typeface, GPU, shareContext=true)`:
  one glyph atlas and resource cache), each with its own framebuffer; a designer popped out into its
  own OS window uses the CPU raster path (the FBO path flickers there on Mesa/XWayland).
* Asset watching: once a second the runtime stats the project files it resolved and, for shared rows
  that resolve nowhere yet, their hint and convention paths, so a file that appears outside the editor
  (a checkout, a copy) rebuilds the view.
* Frames repaint only when the revision, frame size, scales, forced states or hidden set change, when
  the instance reports a dirty region, or twice a second (texture loads); preview repaints every frame.
* Editor zoom is a view transform (`CanvasTransform`) and never changes UI scale. Frame = device px;
  style = logical px; `ResizeMath` divides by `uiScale x pixelRatio` and rounds to whole pixels. Nearest
  filtering at 200%+ so zoomed pixels are exact.

## Panels

Palette (built-ins by category with feature chips; project and dependency components; drag or
double-click), UI Assets (project screens/components/sheets/scripts/exports; dependency table with
embedded/shared badges, resolution source or MISSING, embed/refresh/extract/relink as undoable
`AssetEdit`s), Hierarchy (glyphs, slots as drop targets, component internals for override inspection,
inline rename, multi-select, drag above/inside/below, designer eye/lock), Designer (document tabs,
toolbar, canvas, status bar), Details (header + classes, search, Layout with anchor diagram / flex
glyph segments / box model, Appearance, Text, widget props, Interaction & Accessibility, Bindings,
Component params/overrides/slots, raw inline style, computed style with origins), Style Sheets (sheets
in precedence order, rule cards with live selector validation, specificity, match dots, overridden
declarations struck through, tokens, matched-rules cascade view), Script (code-behind module, line
numbers, checker squiggles, `ui` API completion on Tab, Apply = one undo step + hot reload, LuaLS
stubs), Diagnostics (format, assets, runtime, references, scripts; preview console, requests, fixture
calls), History (click to undo/redo to a step), Sprites (#294: sheet regions, nine-slice, skins, frames;
Apply is one undo step). Details' image fields pick textures and `sheet#sprite` references and open the
texture in the Texture Editor, whose saves repaint every document ([ui-sprites.md](ui-sprites.md) §6). The behavior graph window (#291) opens from the
toolbar; its saves become one `replaceGraphs(edited, base)` step, a three-way merge against the state the
window's copy started from: only graphs, Lua modules and the code-behind the window itself changed are
taken, so Lua edited meanwhile (Script panel, an agent's `set_script`) survives; a module both sides
changed refuses the save naming it (the graph edits stay in the window). The Script panel never
applies typed text over a newer version of its module either: Apply pauses behind a Reload / Keep My
Text banner, and typed-but-unapplied Lua counts as unsaved (save, close, exit and automation flush it
through `UiEditorContext.PendingEdits`).

## Project integration

* Workspaces: menu bar tabs `Modeling | UI Editor` (`layout/Workspace`, `WorkspaceState`). The UI
  workspace has its own dockspace (`OpenMasonUiDockSpace`, default layout `UiWorkspaceLayout`); the
  inactive one is kept alive with `KeepAliveOnly`.
* Files: documents save at their convention path `UI/<namespace>/<path>.omui` (so screens resolve the
  components they use without configuration); Save As otherwise. Saves are atomic (`OmuiWriter.save`)
  and stamp `editor/workspace.json` (frame size, scales, zoom/pan, selection, hidden/locked); a stamp
  supplier returning null bytes removes its entry. A re-save of the document's own file refuses when
  the file changed on disk since the editor read or wrote it (size + SHA-256 fingerprint; a git pull,
  another editor); Save As over it is the explicit replace. Files open by real path, so a symlinked
  alias activates the open document instead of opening a second copy.
* `.sbui` opens as an editable copy (`SbuiImporter.importEditable`, origin `SBUI_COPY`, never writes
  the export); "Import SBUI into Project" uses `SbuiProjectImport` (collected assets become shared
  project assets, never clobbering different ones) and saves the document at its convention path.
* Export: `UiExportService` (Shared or Collect all), report beside the `.sbui`. Every export is also
  checked against the real game host (`UiGameDeploy.check`: the gates `GameUiDocuments.openBound` runs,
  against `GameUiHost.declaration()`): unknown data roots, contracts, actions, providers and unresolvable
  shared rows are listed in the dialog / `hostCheck` before anyone ships the screen.
* Deploy to Game (export dialog, `ui_export {deploy:true}`): `ui/documents/<screen>.sbui` plus every
  shared project asset the export needs under `ui/shared/<ns>/<path><ext>` in
  `stonebreak-game/src/main/resources` (the layout `GameUiAssets` and `GameUiDocuments.readScreen`
  read). Never deploys an export the game would refuse; files that exist with different bytes are
  replaced only on confirmation (`overwrite:true`).
* `.omp` 1.3 optional `uiEditor` node: `workspace`, `documents` (project-relative), `activeDocument`.
  Absent (older files) = Modeling, no UI documents. Unknown workspace values read as Modeling.
  Opening, closing, activating or saving a UI document and switching workspace mark the project dirty
  when the session no longer matches what the `.omp` records (`ProjectService.uiSessionChanged`), so
  exit offers to save it; restoring the recorded session on open is not an edit. Save Project reports
  UI documents it could not save in its status line.
* File menu UI group, a `UI` menu in the UI workspace, Project Browser `.omui` entries with runtime
  thumbnails (`UiThumbnailRenderer`), dirty checks on exit/home/open-project, Save Project saves UI
  documents in place, "Don't Save" discards UI documents and their recovery slots.
* Recovery: dirty documents are written to `<data>/recovery/ui` every 20 s (complete OMUI archives);
  opening a file with a newer slot shows a Restore/Discard banner (restore is one undo step; a refused
  restore keeps the slot); untitled slots are offered at workspace start; shutdown keeps slots of
  still-dirty documents. Only slots left by another session (a crash) are "newer": this session's own
  autosave never blocks a save. A document an edit made unsaveable still gets a slot: the newest state
  in its history that writes (`skippedEdits` in the slot's meta; the status line says so).

## Shortcuts (context `ui`, rebindable)

Ctrl+Z/Y, Delete (resets overrides on internals), Ctrl+D, Ctrl+C/X/V, F2 rename, Ctrl+]/[ reorder,
Ctrl+A select siblings, Esc deselect, Shift+Enter select parent, F frame selection, Home fit, Ctrl+1
100%, F5 toggle preview, Ctrl+S/N/O/W. Arrow keys nudge absolute elements (Shift x10). Canvas: wheel
zoom, middle/right/Space drag pan, double-click drills into components, Alt bypasses snapping, Shift
keeps aspect on corner resize, Esc cancels a drag.

## Dev hooks

`-Dopenmason.uieditor=<project.omp>[,<doc.omui|.sbui>]` opens the project in the UI workspace,
`-Dopenmason.uieditor.select=<key,...>`, `-Dopenmason.uieditor.preview=true`; combine with
`-Dopenmason.autoscreenshot=<s>:<file.png>:quit`.

## Tests

`UiCommandsTest` (undo/redo, compound atomicity, move identity, duplicate remap, merge, dirty, features,
rename, overrides, cross-document paste), `UiTreeTest`, `CanvasMathTest`, `UiDocumentServiceTest`
(create/save/reopen, SBUI copy, import, missing dependencies kept, recovery), `UiAuthoringEndToEndTest`
(author, save, export, load like the game; canvas geometry at two scales, design picking, drop targets,
painted pixels), `OMPUiEditorReferenceTest` (1.3 round trip, older files untouched).

## Automation (#324)

Agents and scripts edit UI documents through the same command layer as the panels.

* **Op batches** (`systems/uiEditor/ops/`): `UiOpBatch` parses `{"ops":[{"op":...}], "label"?}`,
  validates every op's fields and `$alias` references up front (`UiOpField`, `UiOpCatalog`; nothing runs
  on a malformed batch), and compiles the batch into ONE `UiCommand`: one undo step, and a failing op
  leaves the document untouched with `failure()` naming the op. After the ops run, the result is checked
  with `OmuiValidator` (what the writer enforces): errors the batch introduced fail the op that first
  produced them, so an accepted batch always saves. Internal keys are checked against the component's
  source. `UiHistory.checkpoint/rollbackTo` retracts a step without leaving redo entries (a script whose
  later deferred write failed). Each op is a thin adapter over
  `NodeCommands`/`OverrideCommands`/`DocumentCommands`/`AnimationCommands`; internal keys
  (`quit/label`) become overrides; `"as":"x"` binds what an op created (the selection it leaves) as
  `$x`; `#name` resolves to the id. `UiInspector` = read-only tree / element (+ computed style origins
  from `StyleTrace`) / style sheets.
* **Facade** (`systems/uiEditor/automation/`): `UiAutomation` (documents, inspection, batches, undo,
  approved file targets) and `UiPreviewAutomation` (mode, forced states, frame, capture, input through
  the preview's `UiInputRouter`, console). Headless; preview state is runtime/view state only, so it
  can never dirty a document. `DesignerRuntime.step(dt)` advances a preview without painting.
* **Assets, graphs, state machines** (#282 hardening): `add_dependency {path | id+kind, embed?, optional?,
  fallback?, requires?, license?}` (project file or an id the project / game ships; a sprite sheet brings
  its texture), `set_dependency`, `remove_dependency {id, force?}` (refused while referenced),
  `embed_dependency`, `refresh_dependency`, `extract_dependency {collision}`, `relink_dependency {path}`
  (the #285 `AssetEdit`s: their project writes join the batch's single step and are reverted when a later
  op fails), `put_graph`/`remove_graph`, `put_state_machine`/`remove_state_machine` (wire JSON). Paths are
  project-relative and may not escape it (real-path checked). `om.ui` has the same methods.
* **MCP** (`systems/mcp/UiEditingService` + `UiToolDefinitions`): 20 `ui_*` tools (`ui_save` in place or at the convention path, `ui_save_as` elsewhere); writes go through
  `AssetWriteService` (`WriteKind.OMUI`/`SBUI`); preview captures read the live GPU frame back
  (`UiPreviewFrameGrabber`), design captures paint the source on the CPU (`UiSnapshot`). Guide topic
  `ui_editor`. Reached via `MainImGuiInterface.getUiEditor()`. A call that times out before the UI thread
  ran it is cancelled (it never applies late, so a retry cannot duplicate an edit). `ui_close
  {discard:true}` moves unsaved changes to a crash-recovery slot the author can restore; `ui_save`
  refuses a file changed on disk unless `overwrite:true`. Letter keys reach previewed documents
  translated to the active keyboard layout (`ToolInputTap` → `LayoutKeys`, as in the game).
* **Python** (`om.ui`): calls queue ops into `UiScriptCommands` (validated per call, line-accurate
  errors); on success the queue runs as one `ui_ops` batch through `LiveUiScriptTarget`, so a script and
  the equivalent batch produce identical documents (`UiScriptEquivalenceTest`).
* Nested components: internal keys reach through nested instances (`card/btn/label`), resolving each
  component where its placer lists it (embedded in that archive, embedded in the document, else the
  project). Placing a component adds its whole closure to the document's table
  (`service/ComponentDependencies`: nested components and their shared assets, the component's row
  `requires` them), which the export's `ComponentClosure` demands. The palette uses the same rows.
* Tests: `UiOpBatchTest`, `UiToolsEndToEndTest` (new → ops → capture → save → export → load like the
  game; preview never dirties; sandbox), `UiNestedComponentsTest`, `UiScriptEquivalenceTest`,
  `UiAssetOpsTest` (table/graph/state-machine ops, write reversal), `UiAgentHardeningTest` (own autosave,
  discard keeps recovery, timeout cancel, host check, deploy + conflicts), `UiEditorHardeningTest`
  (recovery fallback, disk fingerprint, symlink dedupe, stamps, graph merge), `ProjectUiSessionDirtyTest`,
  `PreviewShortcutGateTest`.

## Not yet

* (Timeline landed in #295: the Timeline tab, [ui-animation.md](ui-animation.md) §9.) (Texture and sprite authoring landed in #294: the
  Sprites panel, the inspector's image picker, Edit Texture — see [ui-sprites.md](ui-sprites.md) §6.)
* Box (marquee) selection of component internals; resizing several elements at once.
* Inline text editing on the canvas.
