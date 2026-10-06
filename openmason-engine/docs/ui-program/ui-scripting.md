# UI scripting: Lua code-behind, the `ui` API and minigames (#292)

Documents run behaviour in **native Lua 5.5**, compiled into the Cenda library and called through FFM. The Open
Mason preview and Stonebreak use the same runtime, so a script behaves identically in both. Python (GraalPy) stays
editor automation only. Compiled graphs (#291) will target this same runtime.

| Part | Where |
| --- | --- |
| Native host (ABI) | `cenda/native/kernels/{include/cenda/lua_host.h,src/lua_host.cpp}`, `CL_ABI_VERSION` **2** |
| FFM binding | `engine/cenda/{CendaLua,LuaState,LuaValueWriter,LuaValueReader,LuaValueFunction,LuaWatchdog}` |
| Runtime | `engine/ui/script`: `UiScriptRuntime`, `UiScripts` (host sequence), `ScriptOps` (host side of the API), `prelude.lua` (Lua side) |
| Animation sampler | `engine/ui/runtime/anim/UiAnimator` (the seam #295 grows) |
| Canvas | `Canvas` widget (`ui-canvas` feature), `UiCanvasCommands`, `paint/CanvasPainter` |
| Editor support | `UiApiCatalog`, `UiApiStubs` (LuaLS), `UiScriptChecker`; tool `UiDocumentPreviewPanel` "Scripts" section |
| Game host | `com.stonebreak.ui.runtime.GameUiDocuments.scripts/openBound/reload`, `GameUiScriptServices`, the dev overlay |
| Samples | `openmason-engine/src/test/resources/ui/script/{scripted_pause,minigame}.omui` (sources under `samples/`) |

## 1. Native host and loading

Lua 5.5.1 is built into `libcenda_kernels`. It is fetched by SHA-256, and `io`, `os`, `debug`, `package` and `linit`
are not compiled in. It has its own handshake, `cl_abi_version`.

There is **no Java fallback**:

- When a document has code-behind and the library is missing or has another ABI, `UiScriptRuntime.load` throws
  `CendaLuaUnavailableException` with a diagnostic naming the path and both versions.
- The game refuses the screen (`openBound`), and the preview shows the error instead of the document.
- `build-kernels.sh` warns on a `CL_ABI_VERSION` / `CendaLua.EXPECTED_ABI` mismatch.

A document **without** code-behind never creates a Lua state. It still needs the library, because layout is Yoga
in Cenda.

**Launch handshake.** `UiNativeHealth.check()` loads both mandatory bindings (Lua host and Yoga flex host) and logs
every problem as an error. The game calls it in `Game.initCoreComponents` and also posts each problem to the chat
in red. The tool calls it at startup, and the UI document preview repeats it in red above the document. So a
missing or stale library shows when the program starts, not when a scripted screen first opens.

**Build guard.** `build-kernels.sh`, the IntelliJ before-launch step, **stops the launch (exit 1)** on a Lua or
Flex ABI mismatch between the C headers and the Java bindings. Compile failures still exit 0 (the kernels are
optional), with a warning that scripted screens will refuse to open until it builds.

**Platforms.** The CMake build is portable:

- GCC and Clang share one warning set (`-Werror`). The Clang build is verified here, which is the macOS compiler
  family.
- MSVC gets `/W3 /permissive- /utf-8`, `/fp:precise` (no FMA contraction) and `WINDOWS_EXPORT_ALL_SYMBOLS` for the
  `extern "C"` ABI.
- `std::popcount` replaces the one GCC builtin.
- Presets name their configuration, for multi-config generators.
- `CendaKernels.locateLibrary` finds `cenda_kernels.dll`, `libcenda_kernels.dylib` and `.so`, including in
  `Release/` and `Debug/`.

`.github/workflows/cenda-native.yml` builds and tests the library on Linux, Windows and macOS. It then runs the
Java handshake and UI scripting suites against it. Those suites **fail, never skip**, without the library
(`-Dui.script.allowMissingNative=true` opts out), so a platform that cannot build or load it turns the job red.

### ABI 2: typed values

The #283 spike passed only numbers. ABI 2 adds the **CL value encoding**: little-endian, one tag byte per value.

| Tag | Value |
| --- | --- |
| `0x00` | nil |
| `0x01` / `0x02` | false / true |
| `0x03` | number (`f64`) |
| `0x04` | integer (`i64`). The host sends integral numbers within ±2^53 as integers, so a script sees `3`, not `3.0`. |
| `0x05` | string (`u32` length + bytes) |
| `0x06` | array (`u32` n + n values). A Lua table with keys 1..n, or dense keys with at most one hole per element. A hole is a nil, so a JSON `null` inside a list survives the round trip. |
| `0x07` | map (`u32` n + n × (string, value)). A table with all-string keys; an empty table is an empty map. |
| `0x08` | registry ref (host → Lua only) |

Tables nest at most 32 deep, so a cycle fails. Functions, threads, userdata, mixed tables and sparse integer keys
cannot cross: encoding one raises a Lua error that names it.

| Call | Purpose |
| --- | --- |
| `cl_register_host_v(s, env, name, fn, user)` | A value-typed upcall. The host reads `args` and writes its results into the buffer it registered with `cl_set_host_buffer` (which it may switch, even mid-upcall). `-1` raises a Lua error with the host's message. |
| `cl_call_v(s, fn, args, len, nargs, max, &out, &len, &count)` | A protected value call. Decoding the args, the call and encoding the results all run under `lua_pcall`, so a memory error or an unencodable result is a status, never a crash. |
| buffer `cursor()` / `reset()` | Lua-side control of a bound float buffer (the canvas). |
| `__cenda_traceback(co, msg)` | Coroutine tracebacks (no debug library). The prelude takes it and removes the global. |

**Re-entrancy.** A host function may call back into its own state: a signal reaching another environment, or a
converter run by a binding write. Only the outermost entry point bumps the watchdog token and resets the budget.
So a nested call is timed and charged as part of the outer one, and a deadline inside it ends the outer call
(`lua_host_test` `test_reentrant_calls`).

**Java side.** `LuaValueWriter` and `LuaValueReader` are allocation-free for numbers and booleans. Strings are
UTF-8 encoded char by char into reused native memory, and reads use one absolute view of the address space.
`LuaState.callValues` reads the out pointer as a `long`. Reading it as a `MemorySegment` allocated 40 B per call.

## 2. One state per screen, one environment per script

`UiScriptRuntime.load(instance, options, services)` finds the contexts:

- the screen's `codeBehind`;
- the module of every component instance whose component document has a `codeBehind`.

It then creates **one sandboxed `lua_State`** for the screen and **one environment per context**. An environment
is a copy of the curated globals with its own library tables, so each instance has its own globals, `string`,
`math` and so on.

| Limit (`UiScriptOptions`) | Default | Enforcement |
| --- | --- | --- |
| `memoryLimitBytes` | 16 MiB | Per-state capped allocator. An allocation over the cap is a Lua memory error. |
| `deadlineMillis` | 100 ms | Watchdog thread: any single call into the state that runs longer is interrupted. Costs ~0: the patched VM polls at back-jumps and calls. |
| `instructionBudget` | 0 (off) | Opt-in exact budget per call. Any count hook doubles VM-bound cost in Lua 5.5 (#283). |

**Sandbox.** The sandbox allows:

- source text only (binary chunks are refused at load, through `load`, and in shared modules);
- `base` without `dofile`, `loadfile` and `collectgarbage`;
- `coroutine`, `math`, `string` without `dump`, `table` and `utf8`;
- the `ui` API, `require` and `print` (which goes to `ui.log`).

The runtime's own entry points (`__cenda_ui_factory`, `__cenda_ui_dispatch`, `__h`) are taken as registry refs
and removed. Every shared metatable (`ui.Element`, `ui.Canvas`, `ui.Handle`, `ui.Event`) is protected with
`__metatable`. One instance therefore cannot patch methods another instance uses (`ScriptSafetyTest`). Nothing
hands Lua a Java object, a GL handle, a file, the Open Mason interface or a mutable game object: values cross
as plain data, and elements as keys.

**Scope.** A screen script sees the whole tree. A component script sees only its instance's subtree, keyed
`instance/...`; slot content belongs to the document that authored it. Queries are scoped. An operation on an
element outside the scope fails: "outside this component's scope".

## 3. Code-behind modules and lifecycle

A document's `codeBehind` names one of two kinds of module (wire contract §4, §5.2):

- an in-archive part, `scripts/<id>.lua`;
- a dependency of kind `script`.

The module either returns a table of hooks or defines the hooks as environment globals.

| Hook | When | Kind |
| --- | --- | --- |
| top level | load, before the binder opens (declare converters here) | plain |
| `on_open(ui)` | after binding; component modules first, then the screen | **task** |
| `update(dt)` | once per frame (`view.frame(dt)`) | plain; allocation-free path |
| `on_input(ev)` | raw key, pointer, wheel and navigation input, trickle-down at the scope root, before element handlers; `true` consumes it | plain |
| `on_close(ui)` | the screen closes (screen first); its tasks are then cancelled | plain |
| `on_reload(ui)` | after a hot reload (default: `on_open` runs again) | **task** |

**Modules.** `require(name)` resolves only two things:

- an in-archive part of the context's document (`require("util")` → `scripts/util.lua`);
- a dependency of kind `script` that the document declares.

Shared modules resolve through #285 asset resolution (`UiDocumentSource.script`, `ResolvedUiAssets.script`), so
they follow project relocation, collect-all export and portable import (`ScriptModuleResolutionTest`). There is
no filesystem search. Modules are cached per environment, and a require cycle is an error.

**Hosting** (`UiScripts.open(view, host, fallback, options, services)`, the one sequence both hosts use):

1. Load the modules.
2. `UiBinder.open` with the scripts' converters, then `fallback`.
3. Attach the scope and input router.
4. Run `on_open`.
5. Attach the runtime to the view: `view.frame(dt)` drives `update`, and `view.close()` runs `on_close` before
   the binder and document close.

The game uses `GameUiDocuments.scripts/openBound`. The preview runs against its `FixtureHost`.

## 4. Execution model

Everything runs synchronously on the UI thread, at defined points:

- **Input dispatch.** Element handlers and `on_input`.
- **`update(dt)`.** It first delivers the results of awaited actions, timers and animation completions, clip
  events and data-watch changes (coalesced per watch), then runs every `update(dt)`.
- **Binding evaluation.** Converters.

Built-in input, focus, animation sampling and battle timing never wait on a script.

**Tasks.** Event handlers, `on_open`, watch and animation callbacks, and `ui.async(fn)` run as coroutines that may
`ui.await(handle)`:

```lua
ui.q("#resync"):on("click", function()
  local result, err = ui.await(ui.action("stonebreak:network.resync"))
  ui.q("#status"):setText(result and ("Audited " .. result.audited) or ("failed: " .. err))
end)
```

- `ui.await` returns the result, or `nil` plus a message (`failed`, `rejected`, `stopped`).
- Awaiting outside a task is a clear error.
- A task may only yield through `ui.await`; scripts use `coroutine.wrap` for their own coroutines.

**Cancellation.** All three of these cancel the screen's pending calls, and their tasks are closed, never resumed:

- closing the screen;
- a reload (the generation moves on);
- leaving the world (`UiHost.advanceEpoch`, which the game calls on `MENU`).

`<close>` handlers run. A completion that arrives later is dropped, never applied (`ScriptLifetimeTest`).

## 5. The `ui` API (uiApi 1)

`UiApiCatalog` is the single list: the prelude implements it, `UiApiStubs` generates LuaLS stubs from it, and
`UiApiSurfaceTest` fails when the two drift.

| Area | Calls |
| --- | --- |
| Queries | `ui.q(sel)`, `ui.qAll(sel)`, `ui.get("panel/resume")` (stable node-id path), `ui.root`, `ui.key`, `ui.document`, `ui.param(name)`, `ui.params()` |
| Elements | `el:prop/set/clear/text/setText`, `el:classes/hasClass/addClass/removeClass/toggleClass`, `el:style/clearStyle/computed`, `el:hasState/setState/enabled/setEnabled`, `el:focus/scrollTo/rect`, `el:parent/children/q/qAll`, `el:name/type/id/exists` |
| Events | `el:on(event, fn [, "trickle"])`, `el:off(event, fn\|id)`. Events are kebab-case (`click`, `pointer-down`, `key-down`, `change`, `commit`, `drag-drop`, …). The event table has `type`, `target`, `current`, `phase` plus per-type data (`x`/`y`/`lx`/`ly` logical px, `button`, `key`, `mods`, `text`, `value`, `dx`/`dy`, `payload`, …) and the methods `ev:stop()`, `ev:stopImmediate()`, `ev:prevent()`, `ev:accept()`. |
| Component signals | `ui.emit(signal, args)` in a component script, validated against the contract's `events[].args`; the instance's users call `instanceEl:on(signal, fn)` |
| Data (#289) | `ui.read(path)` → value, `"ready"\|"loading"\|"missing"\|"failed"`; `ui.watch(path, fn)` → `{cancel}`. Paths are absolute host paths. |
| Actions (#289) | `ui.action(id, args)` / `ui.request(id, args)` → handle, through `UiScope.invoke` with `CallSite.Origin.SCRIPT`, so the same parameter, capability and reentrancy checks apply as for bindings and graphs |
| Converters | `ui.converter(name, {result = "string", to = fn [, back = fn]})`. `result` is a `ValueType` wire name or `any`; `?` makes it nullable. A plain module function of that name also works, typed `any`. |
| Animation | `ui.tween(el, {opacity = 0, ["translate-y"] = 8}, 0.3, "ease-out", {delay = 0})`, `ui.play(clipId, {speed, loop, on_event})`, `ui.stop(h)`, `ui.release(el, prop)`, `ui.sleep(s)` |
| Host requests | `ui.sound(id, {volume})`, `ui.navigate(target, args)`, `ui.close()` (`UiScriptServices`: the game plays sounds; the preview lists requests) |
| Misc | `ui.async(fn)`, `ui.await(h)`, `ui.focus(el)`, `ui.time()`, `ui.log(...)`, `ui.warn(...)`, `ui.api` |

**Purity.** A converter call is pure. Only reads are allowed (`prop`, `classes`, `read`, `q`, …). An action or any
write raises "converters are pure", and the binder shows `CONVERTER_FAILED`.

**Ownership.** Writes respect binding ownership (#289). A `to-target` or `once` target refuses local writes with
a message naming the binding. `two-way` writes stage into the draft. Values are checked against the widget
descriptor before they are set.

## 6. Failures keep the screen usable

Errors, deadlines, memory and budget violations, and invalid values never escape the runtime:

1. The dispatch's **local writes are rolled back** (`ScriptJournal`: props, styles, classes, states, enabled), so
   the screen keeps its last good presentation.
2. The culprit is **disabled**: the handler (Java `off` + Lua drop), `update`, `on_input`, the watch or the
   converter. A failing `on_open` or `on_close` is reported.
3. A `UiScriptDiagnostic` is recorded, with the code (`SYNTAX`, `RUNTIME`, `DEADLINE`, `MEMORY`, `BUDGET`,
   `HANDLER_DISABLED`, `MODULE_NOT_FOUND`, `UNDECLARED_MODULE`, `BINARY_SCRIPT`, `API_VERSION`, `UNAVAILABLE`,
   `TASK_CANCELLED`), `chunk:line`, the element key and the traceback. It is mirrored as an instance
   `SCRIPT_ERROR` and written to the script console.

After a memory error the state runs a full GC. Closing always works: `on_close` failures are reported, and the
state is freed regardless (`ScriptSafetyTest`).

## 7. Hot reload

`UiScriptRuntime.reload()` runs after `instance.reload(doc)`; `GameUiDocuments.reload` and the preview's "Reload"
do both.

- **Each context.** Pending tasks are cancelled, then the new module is compiled. If it compiles, it runs in the
  **same environment** and `on_reload` (or `on_open`) re-attaches its handlers. If it does not compile, the last
  good version re-runs, and the diagnostic says so.
- **Contexts added or removed.** Component instances that appeared get new contexts. Removed ones run
  `on_close` and are freed.
- **What survives.** Environment globals survive; write `count = count or 0` for state that should. Module
  locals start fresh. Element state survives by key (#287).

## 8. Canvas and minigames

`Canvas` (widget version 1) needs the **`ui-canvas`** feature. Its `capacity` prop is the size of its native
float buffer in floats, 32768 by default.

`el:canvas()` returns a `ui.Canvas` whose draw calls go **straight into the native buffer**. No host crossing
happens per command, and strings and textures are registered once and cached in Lua. The painter reads the
buffer in place.

| Command | Floats |
| --- | --- |
| `rect` | `2 x y w h rgb a` |
| `circle` | `3 cx cy r rgb a` |
| `line` | `4 x0 y0 x1 y1 width rgb a` |
| `sprite` | `5 tex x y w h u0 v0 u1 v1 a` (`u1 < 0`: the whole texture) |
| `text` | `6 str x y size rgb a` (y = baseline) |
| `number` | `7 value x y size rgb a decimals` |
| `clip` / `unclip` | `8 x y w h` / `9` |
| `translate` / `resetTransform` | `10 dx dy` / `11` |

Coordinates are logical px from the canvas's top-left. `rgb` is `0xRRGGBB`, and `a` is in [0, 1]. A truncated or
unknown command ends that frame's drawing. Scripts call `c:clear()` each frame.

**Budget** (`MinigameBenchmarkTest`). The sample is 1,000 sprites with gravity, wall bounce and a pointer hit
test, plus 200 eased sparkles: **8,228 floats per frame**. Measured on the development machine (JDK 25.0.3):

| Measure | Result |
| --- | --- |
| Script frame | **p50 125 µs, p95 128 µs** (milestone 0: 76 µs for the bare Lua loop; the difference is the draw commands) |
| Java garbage | about 1 B per frame across 6,000 frames; it does not grow with frame count |
| Lua heap | 187 KiB |

The test fails above 2 ms at the median, above 4 ms at p95, or above 16 B of Java garbage per frame. The painter
(Skija `Rect`s and fonts) is outside that measure.

**Other costs** (`ScriptCostBenchmarkTest`, same machine):

| Measure | Result |
| --- | --- |
| A click through a Lua handler (router dispatch, event table, task) | 8.3 µs |
| One `ui` host op round trip (Lua → Java → Lua, typed values) | 0.26 µs |
| A state with one scripted component | 124 KiB |
| Each further component environment | 10.7 KiB |
| One typed-value FFM crossing (ABI 2, a number) | Java → Lua 54 ns, Lua → Java 43 ns (#283's number-only ABI: 22 / 26 ns) |

**Host bulk systems.** Per-sprite work stays in Lua and the canvas buffer. Native sprite, particle and collision
helpers are follow-ups, for when a minigame needs more than this budget.

## 9. Animation through the host sampler

`UiAnimator` (one per runtime) samples script tweens and timeline clips (`animations/<id>.anim.json`, `style:`
tracks) into the elements' **animation channel**, which wins over every other style layer.

- **What interpolates.** Numbers and colours interpolate. Other values switch at the end of their segment.
  Easing uses the format's `UiEasing` curves.
- **Holding and releasing.** A finished tween or one-shot clip holds its values until `ui.release`, `ui.stop` or
  a new animation of the property; the last writer wins.
- **Delivery to scripts.** Completion settles the handle, so `ui.await(ui.tween(...))` works. Clip events call
  `on_event`.
- **Reduced motion.** Animations jump to their end state (#288 preference).

Style transitions, precedence and blending rules, time sources and the timeline panel belong to #295, which grows
this sampler.

## 10. Editor and tooling

**Preview.** `UiDocumentPreviewPanel` runs code-behind against the fixture host, and its "Scripts" section shows:

- the running modules, Lua heap, calls and last-frame cost;
- runtime and static diagnostics with `chunk:line`;
- the console (`ui.log`, errors with tracebacks on hover);
- the host requests (sounds, navigation, close).

"Reload" hot-swaps.

**LuaLS.** "Write LuaLS stubs" writes `types/ui.d.lua` and a `.luarc.json` next to the document (`UiApiStubs`).
The `.luarc.json` turns off the libraries the sandbox lacks and allows lowercase environment globals. Completion,
signatures and type diagnostics then work in any LuaLS editor.

Annotate a module table with `---@type ui.Module` so the hooks' `ui` parameter is typed too. With lua-language-server
3.19.1 this reports:

- `undefined-field` for `ui.qq` or `el:setTxt`;
- `param-type-mismatch` for `ui.q(42)`;
- `missing-parameter` for `ui.tween()`;
- `undefined-global` for `os`.

Both samples check clean. `UiApiSurfaceTest` runs this check when given `-Dui.luals=<lua-language-server>`.

**Dev hooks for live runs:**

- `-Dstonebreak.uidoc.autoclick=key@seconds,...` (game overlay) and `-Dopenmason.uidoc.autoclick=...` (preview) click
  elements through the real router.
- `-Dstonebreak.autoscreenshot=<s>:<file>[:quit]` with `-Dstonebreak.autoscreenshot.anystate=true` shoots the main
  menu, no world needed.
- `-Dopenmason.uidoc.autoscreenshot=<s>:<file>[:quit]` writes the preview's raster frame.

Both samples were verified this way in the running game and the running tool. The tool run uncovered that the
preview's default Raster path never painted: `MasonryUI` refuses a frame on an unallocated raster backend. Fixed in
`MasonryPreview.paint`.

**Static check.** `UiScriptChecker.check(source, chunk)` compiles in a throwaway state and reports:

- syntax errors;
- `ui.x` members the API lacks, with a suggestion;
- event names that are not UI events (an info, since they may be signals);
- near-miss hook names (`onOpen` → `on_open`).

**Samples.** Both open with `-Dstonebreak.uidoc=<file>` in the game (a script's `ui.close()` hides the overlay)
and `-Dopenmason.uidoc.preview=<file>` in the tool. Regenerate them with `-Dui.script.write=true` after editing
`samples/*.lua`.

| Sample | Shows |
| --- | --- |
| `scripted_pause.omui` | code-behind events, a Lua-converter binding, fade-in, colour and fade-out tweens, an awaited `stonebreak:network.resync`, `stonebreak:screen.pause.resume`; its `editor/fixtures.json` answers the actions in the preview |
| `minigame.omui` | the Canvas benchmark scene; move the pointer to bat sprites, click to reset |

## 11. Not yet

- **Runtime-inserted component instances** (ListView rows of a component with code-behind) get no script
  context. Their template's bindings still work.
- **Relative data paths** in `ui.read`/`ui.watch` (`.x` against the inherited source). Use absolute host paths.
- **Packaging.** The native library is not packaged in the jars; it is found in the build tree, or through
  `-Dcenda.kernels.path` / `CENDA_KERNELS_PATH`.
- **Windows and macOS** are covered by the CI workflow, but until it has run they are unverified. Linux is built
  and tested, and the Clang build passes locally.
- **#295 owns** style transitions, `prop:` animation tracks, `scale`/`rotate`, and precedence and blending.
- **#293 owns** editor conveniences beyond the preview's script pane: an in-tool editor with inline completion,
  and fixture-run recording.

## Tests

| Test | Covers |
| --- | --- |
| `cenda/tests/lua_host_test.cpp` | value codec round trips and refusals, holes, malformed and over-cap args, host errors, value calls, refs, buffer reset, coroutine tracebacks, re-entrant calls with one watch token and nested deadlines |
| `CendaLuaValuesTest` | the codec through FFM: UI value round trip, host exceptions, unencodable values, re-entrancy, buffer growth, allocation-free number calls |
| `ScriptBehaviourTest` | the acceptance screen (events, Lua-converter binding, awaited action, tween), failed actions, clips and events, two isolated component instances with lifecycles and signals, scope limits, watches, `on_input`, `require`, documents without code-behind, canvas buffer, key events |
| `ScriptSafetyTest` | sandbox denials, tamper-proof shared metatables, binary refusal, rollback + disable + `chunk:line`, deadline, memory cap, opt-in budget, failing `update`, syntax errors, pure converters, binding ownership, `on_close` failure, widget type checks |
| `ScriptLifetimeTest` | cancellation on close, reload and world change (stale results never apply), await outside a task, `ui.sleep`, hot reload (new code, kept globals), a broken edit keeping the last good version, contexts added and removed by reload |
| `ScriptModuleResolutionTest` | a shared module from a moved project, from a collect-all export with no project, and after a portable import; an embedded module after the `.omui` file moves and through an export; a missing module names itself at `pause.lua:2` |
| `ScriptSampleTest` | packed samples match their sources; the scripted pause on `FixtureHost` |
| `MinigameBenchmarkTest` | 1,000-sprite frame budget and zero Java garbage; raster pixels of the canvas |
| `UiApiSurfaceTest` | catalog = prelude, stubs cover every member and are valid Lua, checker findings, samples pass the check; with `-Dui.luals`, a real LuaLS run |
| `ScriptCostBenchmarkTest` | event dispatch, host-op round trip, typed FFM crossings, per-instance memory |
| `UiNativeHealthTest` | the launch handshake of both native UI hosts |
| `ScriptedScreenGameTest` (game) | the same sample on `GameUiHost` |
| `ScriptedPreviewTest` (tool) | the same sample through the preview's path in the tool's JVM |
