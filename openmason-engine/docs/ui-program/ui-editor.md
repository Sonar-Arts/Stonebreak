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
  `editor/fixtures.json`) with code-behind and graphs (debug build), fed real input through
  `PreviewInput` (pointer, wheel, keys while the designer is focused). Edits hot-reload
  (`instance.reload` + `scripts.reload`). Host requests are listed, never performed. Leaving preview
  discards the runtime, so preview can never write the source. While the document holds the keyboard,
  editor shortcuts stand down (`setNextFrameWantCaptureKeyboard`).
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
toolbar; its saves become one `replaceGraphs` step.

## Project integration

* Workspaces: menu bar tabs `Modeling | UI Editor` (`layout/Workspace`, `WorkspaceState`). The UI
  workspace has its own dockspace (`OpenMasonUiDockSpace`, default layout `UiWorkspaceLayout`); the
  inactive one is kept alive with `KeepAliveOnly`.
* Files: documents save at their convention path `UI/<namespace>/<path>.omui` (so screens resolve the
  components they use without configuration); Save As otherwise. Saves are atomic (`OmuiWriter.save`)
  and stamp `editor/workspace.json` (frame size, scales, zoom/pan, selection, hidden/locked).
* `.sbui` opens as an editable copy (`SbuiImporter.importEditable`, origin `SBUI_COPY`, never writes
  the export); "Import SBUI into Project" uses `SbuiProjectImport` (collected assets become shared
  project assets, never clobbering different ones) and saves the document at its convention path.
* Export: `UiExportService` (Shared or Collect all), report beside the `.sbui`.
* `.omp` 1.3 optional `uiEditor` node: `workspace`, `documents` (project-relative), `activeDocument`.
  Absent (older files) = Modeling, no UI documents. Unknown workspace values read as Modeling.
* File menu UI group, a `UI` menu in the UI workspace, Project Browser `.omui` entries with runtime
  thumbnails (`UiThumbnailRenderer`), dirty checks on exit/home/open-project, Save Project saves UI
  documents in place, "Don't Save" discards UI documents and their recovery slots.
* Recovery: dirty documents are written to `<data>/recovery/ui` every 20 s (complete OMUI archives);
  opening a file with a newer slot shows a Restore/Discard banner (restore is one undo step); untitled
  slots are offered at workspace start; shutdown keeps slots of still-dirty documents.

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

## Not yet

* MCP/Python automation of UI documents (they must go through `UiEditorActions`/`UiCommand`s).
* (Timeline landed in #295: the Timeline tab, [ui-animation.md](ui-animation.md) §9.) (Texture and sprite authoring landed in #294: the
  Sprites panel, the inspector's image picker, Edit Texture — see [ui-sprites.md](ui-sprites.md) §6.)
* Box (marquee) selection of component internals; resizing several elements at once.
* Inline text editing on the canvas.
