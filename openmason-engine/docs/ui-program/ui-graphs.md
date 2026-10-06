# UI behavior graphs compiled to Lua (#291)

Behavior graphs are Unreal Widget Blueprint-style visual scripts for authored UI. They compile to **readable,
source-mapped Lua** that runs on the #292 runtime, in the same sandbox, scheduler and budgets as code-behind. There is
no graph interpreter, so anything a graph does a script can do. The graph file stays canonical, and generated Lua is
never edited.

| Part | Where |
| --- | --- |
| Graph source | `graphs/<id>.graph.json` in an OMUI (wire contract §5.4), record `format.omui.UiGraph` |
| Node kinds, typing | `engine/ui/graph`: `NodeKinds` (+ `EventKinds`, `FlowKinds`, `ValueKinds`, `ElementKinds`, `HostKinds`, `CallKinds`), `PortType`, `PortSpec`, `PropSpec` |
| Checks | `GraphPlan` (via `GraphCompiler.validate`), `GraphDiagnostic` |
| Compiler | `GraphCompiler` → `LuaGenerator` + `LuaWriter`; `CompiledGraph`, `SourceMap` |
| Environment | `GraphEnvironment` / `DocumentEnvironment` (elements, Lua signatures from `LuaSignatures`, clips, contract) |
| Derived caches | `GraphDerived` (export), `UiDocumentSource.derivedGraph` + `ResolvedUiAssets.withDerived` (runtime) |
| Runtime | `engine/ui/script/UiScriptRuntime` (graph modules per script context), `prelude.lua` (`OP_GRAPH_LOAD`, `OP_GRAPH_HOOK`, `ui.on`/`ui.raise`) |
| Debugger | `engine/ui/script/GraphDebugger` (hits, watches, breakpoints over debug builds) |
| Editing model | `engine/ui/graph/edit`: `GraphEditor`, `GraphLayout`, `PaletteEntry`; `GraphScripts` (Convert to script) |
| Editor UI | `openmason-tool` `systems/uiPreview/graph/` (dev window beside the UI document preview) |
| Samples | `openmason-engine/src/test/resources/ui/script/graph_pause.omui` (built by `ScriptSamples.graphPause`) |

## 1. Model

A graph has typed **variables**, an **event graph**, and **functions**. Each body is a set of nodes and links. A node
has a namespaced, versioned `kind`, literal values for unconnected inputs (`inputs`), kind settings (`props`) and a
canvas position. Ports are resolved from the kind and the node's props, so a node's ports can depend on its settings:
a function call takes its signature, a format node takes the placeholders of its template, and a signal event takes
its component's declared arguments.

**Port types** are the format's value types (`bool int number string color asset list object`), plus `any` and
`exec`. Execution ports carry control flow; data ports carry values. When a data output connects to an input of a
different type:

| From → to | Allowed |
| --- | --- |
| same type, `any` either way | yes (an `any` value is checked at run time) |
| `int` → `number` | yes |
| `color`, `asset` → `string` | yes |
| `int`, `number`, `bool` → `string` | yes, rendered with a shared `text()` helper (`3.0` prints `3`) |
| anything else, or exec ↔ data | no (`TYPE_MISMATCH`) |

A data input takes one link. An exec output takes one link: to run several chains, use a Sequence. An exec input may
receive many links.

**Roles.** A node's role follows from its ports:

- **Event.** An entry point. Every event starts a task.
- **Statement.** It has an exec input, and it runs in order.
- **Pure.** It has no exec ports. It is evaluated where a statement reads it, at most once per statement.

Function entry and return nodes are statements that the compiler handles specially.

### Node kinds (kindVersion 1)

| Category | Kinds |
| --- | --- |
| Events | `ui:event.open`, `ui:event.close` (sync), `ui:event.update` (sync, `dt`), `ui:event.click`, `ui:event.element` (any UI event: `pointer-enter`, `change`, `commit`, …), `ui:event.signal` (a component instance's declared signal), `ui:event.custom` (raised by Lua or graphs), `ui:event.watch` (host data changes) |
| Flow | `ui:flow.branch`, `ui:flow.sequence` (2–16 outputs), `ui:flow.wait`, `ui:flow.for-each` |
| Logic and math | `ui:compare` (eq/ne/lt/le/gt/ge), `ui:logic.and/or/not`, `ui:select`, `ui:is-set`, `ui:math` (number or int), `ui:math.round` |
| Text and data | `ui:format` (`{name}` placeholders become inputs), `ui:to-text`, `ui:object.get`, `ui:data.read` |
| State | `ui:variable.get/set/increment` |
| Elements | `ui:element.set-text/set-visible/set-enabled/set-class/set-style/set-prop/get-prop/has-class`, `ui:element.focus` |
| Animation | `ui:anim.tween` (one style property, optional wait), `ui:anim.play` (a clip, optional wait), `ui:anim.release` |
| Host | `ui:action.invoke` (awaits: `result`, `ok`, `error`), `ui:action.request`, `ui:navigate`, `ui:screen.close`, `ui:sound.play`, `ui:log` |
| Events out | `ui:signal.emit` (component graphs), `ui:event.raise` (custom events) |
| Functions | `ui:function.entry`, `ui:function.return`, `ui:function.call`, `lua:call` |

**Targets.** Elements are picked by **stable node-id path** (`resume`, or `resume/label` inside an instance), never by
name. Renaming a `#name` therefore never breaks a graph. `UiReferences` reports the graph's targets when a node id is
renamed.

**Versions.** A kind's `version` is the newest `kindVersion` the compiler reads. A node saved with a newer version is
refused (`NEWER_KIND_VERSION`), so an older host never runs semantics it does not know.

### Functions and macros

- **Exec function.** A function with an exec input is a statement. It may have several exec outputs, which makes it a
  macro-like multi-exit call: `ui:function.return` picks one with `props.output`, and the call site branches on it.
- **Pure function.** A function without exec ports. Its body is its entry, pure nodes and one return.
- **Converter.** A pure function with one input and one output is also registered as a **binding converter** under its
  id (`ui.converter`). This is how a computed-binding graph becomes a pure binding function.
- **Recursion.** Graph functions may not recurse (`SYNC_CYCLE`).

**Lua functions.** An exported function of the code-behind, an in-archive script or a declared script dependency
becomes a palette node automatically when it has a LuaLS `---@` signature (`LuaSignatures`):

- `---@param` and `---@return` give typed ports;
- `?` and `|nil` make a port optional;
- `---@async` marks it latent.

Lifecycle hooks are never nodes. A `lua:call` can be made pure (`props.pure`), unless the function is async.

## 2. Checks before compiling

`GraphCompiler.validate` (`GraphPlan`) reports every problem with its graph, function, node and port. Nothing with an
error compiles.

| Code | When |
| --- | --- |
| `UNKNOWN_KIND`, `NEWER_KIND_VERSION` | the kind is unknown, or was saved by a newer version |
| `INVALID_PROP` | a property is missing, malformed, not in its enum, or has a malformed template |
| `MISSING_ELEMENT` | a target path that is not in the document |
| `UNKNOWN_SIGNAL`, `UNKNOWN_VARIABLE`, `UNKNOWN_FUNCTION`, `UNKNOWN_LUA_FUNCTION`, `UNKNOWN_CLIP` | the named thing does not exist |
| `REQUIRED_INPUT`, `INVALID_LITERAL` | an input with no link, literal or default; or a literal of the wrong type |
| `BROKEN_LINK`, `WRONG_DIRECTION`, `TYPE_MISMATCH`, `DUPLICATE_LINK` | a link to a port that is gone (after a signature change), from an input, between types that do not connect, or a second link where one is allowed |
| `SYNC_CYCLE` | a data cycle among pure nodes; an exec loop with no wait in it; a recursive function |
| `LATENT_IN_SYNC` | a wait (wait, awaited tween or clip, invoke, an async Lua call, or a function that contains one) after `update` or `close`, or inside a pure function |
| `OUT_OF_SCOPE` | a value read from a statement that does not run in the same event. Share values through variables. |
| `FUNCTION_SHAPE` | a missing or extra entry or return node, statements in a pure function, or events in a function |
| `UNREACHABLE` (warning) | a statement that no event reaches; it is not compiled |

## 3. What the compiler emits

The output is deterministic: the same graph, environment and `GraphCompiler.VERSION` always give the same bytes. The
chunk name is `<graph id>.graph.lua`. The chunk runs in its script context's environment, with
`(ui, script, dbg, dbgv)`, and returns its hooks:

```lua
-- omui-graphc 1: graphs/pause.graph.json, source sha256 …
local ui, script, dbg, dbgv = ...
local G, F, H = {}, {}, {}
local v_resyncs = 0                                  -- variables: chunk locals

-- @node fn:display_if/entry ui:function.entry
F.display_if = function(a_online) … end             -- functions
ui.converter("display_if", { result = "string?", to = F.display_if })

-- @node on_resume ui:event.click
H.on_resume = function(ev)                           -- one handler per event
  -- @node fade_out ui:anim.tween
  ui.await(ui.tween(ui.get("panel"), { ["opacity"] = 0 }, 0.2, "ease-in", { delay = 0 }))
  -- @node resume ui:action.request
  ui.request("stonebreak:screen.pause.resume", {})
end

function G.on_open()                                 -- handlers attach, open events start as tasks
  ui.get("resume"):on("click", H.on_resume)
  ui.async(H.opened)
end
return G
```

- **Chains** are straight-line code. A branch is an `if`. Sequence outputs and loop bodies are local functions, so
  each output runs to completion before the next.
- **Joins and loops.** A statement reached by more than one link becomes a local function, entered with
  `return b()`. Lua's proper tail calls keep a looping graph flat. Every chain ends at its transfer, so the transfer is
  always the last statement of its block.
- **Pure inputs** are evaluated into temporaries right before the statement that reads them. The temporaries sit in a
  `do … end` block that closes before the statement's continuation, so nesting follows branches, not chain length.
  Unit locals spill into a table beyond 120, which keeps clear of Lua's 200-local limit.
- **Waits** become `ui.await(...)` yields inside the handler's task.
- **Values from statements** are unit locals. A value cannot leak into another event: that is `OUT_OF_SCOPE`.
- **Literals and names.** Every literal is quoted by `LuaText` with escapes, and never spliced raw. Locals never shadow
  the globals the code uses.

## 4. Running with code-behind

A document instance (the screen, or each component instance) gets **one script context** when it has code-behind
**or** graphs. Its graphs load after the module, into the **same environment**:

- they see the code-behind's module table as `script`;
- they share its handler table, tasks, journal and lifetime;
- graph variables are chunk locals, so each instance has its own;
- the context's diagnostics and console name the graph chunk.

| Hook | Order |
| --- | --- |
| load | code-behind top level, then each graph's top level (graph converters register here, before the binder) |
| `on_open` | component contexts first, then the screen. In each context, the code-behind's `on_open`, then each graph's (attach handlers, start open events), each as its own task and dispatch |
| `update` | the code-behind's `update(dt)`, then each graph's `update` events, in one dispatch. A failure disables only the failing one. |
| close | each graph's close events, then the code-behind's `on_close`, then all tasks are cancelled |
| reload | the module reloads (or keeps its last good version), then every graph recompiles. A graph that fails its checks keeps its previous Lua, with a warning. Then its `on_open` runs again. Graph variables reset; code-behind globals survive (#292). |

**Lua and graphs together.**

- A graph calls a Lua function with `lua:call` (`script.fn(...)`, a global, or `require(module).fn(...)`).
- Lua raises a custom event with **`ui.raise(name, args)`**, and graph `ui:event.custom` nodes handle it.
- A graph raises one with `ui:event.raise`, and Lua handles it with **`ui.on(name, fn)`** (both new in uiApi 1).
- Raised events are queued and delivered after the current dispatch, as tasks in raise order. So neither side calls
  the other re-entrantly, and no recursion guard is needed. More than 256 in one dispatch is reported as a raise
  loop.
- Component signals work across instances as in #292: `ui.emit` or `ui:signal.emit` in the component, and `el:on` or
  `ui:event.signal` in the screen.

**Lifetime.** Graph handlers are ordinary tasks. Close, reload and world change (`UiHost.advanceEpoch`) cancel them,
and late results are dropped, so a delayed graph handler can never touch a closed or reloaded screen
(`GraphRuntimeTest`).

## 5. Errors, budgets and traces point at nodes

Every generated line belongs to the node named by the `-- @node <loc> <kind>` marker above it: `count`, or
`fn:log_click/print`. `SourceMap` reads the markers from the Lua text alone, so a cached chunk maps exactly like a
fresh one.

When a dispatch fails (an error, the watchdog deadline, the memory cap or the instruction budget), the runtime
searches the message and its traceback for the first `<id>.graph.lua:<line>` frame. It maps that frame to a node and
records it as `UiScriptDiagnostic.node()` (`pause#resume`). When a graph calls a Lua function that fails, the frame
below the Lua one names the calling node.

Rollback and disabling work as in #292. A graph that fails its checks is reported per node as `GRAPH_INVALID`, and is
never loaded.

**Debug builds.** The preview opens runtimes with `UiScriptOptions.withGraphDebug(true)`. Graphs then compile with
`dbg(node)` before every statement and `dbgv(node, port, value)` for every value produced. `GraphDebugger` records:

- **hits**, for active-node highlighting and hit counts;
- the **last value of every port** (watches);
- **breakpoints**. At a breakpoint the task awaits a debug handle until `resume(token)`, then continues at the next
  frame. Outside a task (`update`, close, converters) a breakpoint only records the hit.

Release builds (the game, and derived caches) contain no trace calls.

## 6. Derived caches in SBUI

`UiExportService.export(doc, sources, request, assetId)` compiles every graph with `GraphDerived.compileAll`:

- the release Lua goes to `derived/graphs/<id>.lua`;
- the row records `source: graph:<id>`, the SHA-256 of the canonical graph entry, `compiler: omui-graphc` and
  `compilerVersion: 1`;
- a graph with errors **blocks the export**, with `GRAPH_INVALID` diagnostics whose pointer names the node
  (`/functions/0/nodes/2`).

At run time the game serves the caches through `ResolvedUiAssets.withDerived(sbui)`, in `GameUiDocuments.open` and
`openBound`. The runtime uses a cache only while all of these still hold:

- its compiler and version are current;
- its source hash matches the graph;
- the **inputs hash** in its header (`-- inputs sha256 …`, `GraphInputs`) still matches. That hash covers the
  resolved signatures of the Lua functions the graph calls, and the contracts of the signals it handles or emits.

So a shared module or component that changed its signatures cannot leave stale code running. Otherwise the runtime
compiles the graph itself and logs an INFO line. The editor always compiles, with the debug build.
`SbuiReader` already rejects caches whose source changed (`STALE_DERIVED`).

Layout lives in `editor/graphs/<id>.layout.json` (comment frames and node groups), apart from the graph file. It never
changes the source hash, so annotating a graph never invalidates its cache. Node positions stay on the nodes, as the
format defines; the compiler ignores them.

## 7. Editing

`GraphEditor` is the headless model that the Open Mason window drives. Every edit is one undoable step on the archive;
a drag token merges a drag's moves into one step.

- **Nodes and links.** Add a node, from a kind or a palette entry. Remove nodes, which also removes their links and
  group memberships. Move or place nodes. Set properties and literals.
- **Linking rules.** `canConnect` and `connect` accept a drag from either end. They replace an existing link on a data
  input or an exec output, and drop the literal of an input that gains a link.
- **Variables and functions.** Add, edit and remove them. Renames update every referencing node and the layout.
- **Comments and groups.** Moving a comment carries the nodes inside its frame.
- **Clipboard.** `copy`, `paste` and `duplicate` use canonical JSON, remap ids, and carry only the links inside the
  selection.
- **Palette and inspector.** `palette(query)` covers built-ins, variables, graph functions and annotated Lua
  functions. `options(prop)` lists element paths, variables, functions, modules, clips, signals, UI events, enums and
  style properties.
- **Jump to element.** `elementTargets` and `nodesTargeting` link nodes and elements in both directions.
- **Live checks.** `diagnostics()` re-validates after each edit.
- **Convert to script.** `convertToScript(moduleId)` copies the release Lua into a new editable module
  `scripts/<moduleId>.lua`, through `GraphScripts`. The markers become plain comments, and a `bind(M)` function
  replaces the chunk arguments. The graph stays, and nothing regenerates the copy.
- **Several graphs, one history.** `switchGraph(id)` edits another graph of the document, creating it as an undo
  step. Undo returns to the graph each step was made in, and dirty state is measured against the last save.
- **Arrange.** `arrange(function)` lays a body out in one undo step: a band per event (or the function entry),
  statements in columns by exec depth with branch arms stacked, and pure inputs below the column that reads them.

**The Open Mason window** ("UI Behavior Graph", `openmason-tool` `systems/uiPreview/graph/`) drives `GraphEditor`. It
opens with **Graphs...** in the UI document preview (`-Dopenmason.uidoc.preview=<file>`; add
`-Dopenmason.uigraph.open=true` to open it on load).

- **Pure logic, unit-tested without GL.** `GraphCanvasView` (transform, sizing, pin anchors, hit tests, bezier
  links), `GraphScene`, `GraphValues`, `PaletteSelection` and `PinConnectPicker`.
- **Canvas** (`GraphCanvasPanel`):
  - drag to move, box select, pan and zoom about the cursor;
  - pin-to-pin linking, with the `canConnect` reason shown on an invalid drop. A drop on empty space opens the
    palette filtered to compatible nodes;
  - Alt+click to unlink;
  - comment frames that move and resize, and group outlines;
  - error badges, and a context menu;
  - shortcuts: Delete, Ctrl+C/V/D/Z/Y/A, F (frame), F9 (breakpoint).
- **Side tabs.**
  - Inspector: a widget for each `PropSpec.Kind`, with pickers from `options`, and typed literals for unconnected
    inputs.
  - Data: variables, plus functions with a signature editor.
  - Problems: click a problem to frame its node.
  - Debug: paused tasks with Continue, and the recent trace.
- **Toolbar.** Graph and body pickers, Undo/Redo, Save, Show Lua (the generated code, scrolled to the selected
  node), Convert to script, Frame all and Arrange.
- **Saving.** Save writes the `.omui` atomically, and the preview hot-reloads it.
- **Debugging.** The preview runs graphs as debug builds. Nodes it just ran glow, show hit counts and show breakpoint
  and paused markers. Output pins show their last value as a tooltip.
- **Jump to element.** It outlines the element in the preview. Ctrl+click on a preview element selects the nodes
  that target it.

Live runs: `-Dopenmason.autoscreenshot=<s>:<file.png>[:quit]` writes the tool window's own back buffer, which shows
the graph window.

## 8. Samples and parity

`graph_pause.omui` is the scripted pause sample's layout with **no code-behind**. One graph drives:

- the open animation;
- conditional visibility: a watch on `session.online`, and a graph-function converter that binds the online badge;
- pause navigation: initial focus, resume after an awaited fade, and quit;
- an awaited resync action, with a coloured status line.

It behaves identically in the engine on its fixture host, in the game on `GameUiHost` from its shipped derived Lua,
and through the preview's path in the tool, with traces. Its resume handler is the **parity fixture** of
`scripted_pause.lua`'s: `theGraphResumeHandlerMatchesTheHandwrittenLuaOne` runs both frame by frame and compares the
panel opacity and the action stream.

Open it with `-Dstonebreak.uidoc=<file>` in the game or `-Dopenmason.uidoc.preview=<file>` in the tool. Regenerate it
with `-Dui.script.write=true`.

## Tests

| Test | Covers |
| --- | --- |
| `GraphRuntimeTest` | a graph driving events, state, branch, awaited tween and action; Lua function calls and custom events both ways; isolated component instances; cancellation on reload and close; a graph converter in a binding; runtime errors at their node; invalid graphs reported per node; the debugger |
| `GraphValidationTest`, `GraphCompilerGoldenTest`, `SourceMapTest`, `LuaSignaturesTest`, `GraphDerivedTest` | every check, golden Lua (regenerate with `-Dui.graph.write=true`), determinism, release vs debug, the source map, signature parsing, derived inputs and blocked exports |
| `GraphEditorTest` | undo and redo, linking rules, drag merging, copy and paste remapping, removals, comments and groups apart from semantics, renames, live diagnostics, palette and pickers, the save, export and import round trip with reproducible Lua, Convert to script |
| `ScriptSampleTest` | the graph sample on the fixture host, and graph vs handwritten-Lua parity |
| `GraphScreenGameTest` (game), `GraphPreviewTest` (tool) | the same sample on `GameUiHost` from derived Lua, and through the preview path with traces |
| `GoldenFixtureTest` | `pause_menu.sbui`'s derived cache is real compiler output |

## Not yet

- **Element data type.** Element references cannot flow through ports. Targets are static properties.
- **Relative data paths.** `ui:data.read` and `ui:event.watch` take absolute host paths only, as in #292.
- **Kind upgrades.** There is no upgrade path between node-kind versions yet (only version 1 exists).
- **Graph variables on reload.** They reset on hot reload, while code-behind globals survive.
