# UI fidelity fixtures, migration gates and runtime diagnostics (#296)

A screen leaves the legacy path only when its document is proven to look, lay out and behave like the
screen it replaces (#282). This page defines what "proven" means and the tools that check it:

1. **Pinned legacy baselines**: committed PNGs of today's renderers.
2. **The migration gate**: a document capture vs the legacy capture, in geometry and pixels.
3. **Ledger bookkeeping**: a row's status needs evidence.
4. **Runtime budgets**: soft and hard limits on every open document, in the game and in the preview.

Tolerances and budgets are the #283 version-one contracts.

## 1. Deterministic captures

A capture is only comparable when everything it depends on is fixed:

| Input | How it is pinned |
| --- | --- |
| Framebuffer, UI scale, pixel ratio | `FidelityCase.Viewport`. Pixel ratio is 1 everywhere: no DPI handling exists (ledger). |
| Font | The bundled `/fonts/Minecraft.ttf`, loaded from the classpath |
| Renderer | Skia CPU raster (N32, untagged), no GL. The `SkijaUIBackend` subclass seam, as in `BattleRasterFixture`. |
| Backdrop | An opaque 64 px checkerboard (`LegacyUiRaster.CHECK_A/B`), so a translucent scrim's strength shows in every pixel |
| Time and randomness | `com.stonebreak.ui.LegacyUiClock`, pinned at 1.25 s, seed 296 |
| State | The case's variant: online or offline, lit or unlit, hovered element, and so on |

### `LegacyUiClock`: the legacy screens' one time and randomness seam

The legacy screens read the wall clock in about a dozen places and drew from unseeded `Random`s, so no two
captures matched. These presentation reads now go through `LegacyUiClock`:

- `seconds()`: furnace crucible animation
- `millis()`: chat fade and expiry, GIF emoji frames, caret blinks (host/join, world select, terrain mapper),
  main-menu splash pulse
- `random()`: splash text choice, screen droplets

It passes the system clock through unchanged until it is pinned. Pinned, time stands still and every new
`random()` is seeded. Tests call `pin(seconds, seed)` and `release()`. A live run uses
`-Dstonebreak.ui.pinclock=<seconds>[:<seed>]`.

It does **not** cover:

- input and safety timing (double-click windows, search debounce, world-select hover-card delays, the UI-scale
  auto-revert): frozen, they would stop working
- save and backup timestamps
- gameplay
- `Game.getTotalTimeElapsed` and render-path frame deltas (3D previews, damage numbers). These are dt-driven and
  belong to #295 `UiClocks` once those screens migrate.

Documents use #295 `UiClocks`, which are deterministic by construction.

### Other seams added

- `UiOnlineState.override(BooleanSupplier)`: the online layout without a network session. The legacy pause
  menu (six buttons) and documents (`session.online` from `GameUiHost`) both read it, so a gate run compares
  the same state on both sides. Tests use it, and so does `-Dstonebreak.autopause=<s>:online`. It changes
  presentation only, never the network session.
- `-Dstonebreak.autopause=<s>[:online]`: opens the pause menu N seconds into a world (`Main.maybeAutoPause`).
  For a live back-buffer shot, pair it with a later `-Dstonebreak.autoscreenshot` and with
  `-Dstonebreak.ui.pinclock`. The world behind the menu is not pinned, so live shots are evidence for review,
  never baselines.

## 2. Engine toolkit: `com.openmason.engine.ui.fidelity`

The toolkit is main code, so game tests, engine tests and the tool can all use it. It has no JUnit
dependency and throws `AssertionError`.

| Class | Role |
| --- | --- |
| `FidelityImage` | Unpremultiplied ARGB frame, top-left origin; lossless PNG read and write |
| `FidelityCase` (+ `Viewport`) | `screen`, `variant`, viewport. `id()` is the baseline file name, for example `pause-field-online_1921x1081_s1_25`. `STANDARD` lists the agreed viewports. `matrix(...)` |
| `PixelTolerance` | `EXACT`, `RASTER_DRIFT`, `assetScale(scale, assetRects)`; outlier caps (`maxOutlierDelta`, `maxClusterPixels`, `withOutlierCaps`) |
| `PixelComparator` / `PixelReport` | Per-pixel compare: mismatches, shift matches, worst delta, worst mismatch delta, largest mismatch clump, bounds, diff image (grey baseline, red mismatch, amber shift match) |
| `GeometryRule` / `GeometryComparator` | Named rects vs the legacy oracle |
| `GoldenStore` | Committed baselines under `src/test/resources/<dir>`, read from the source tree (never a stale `target/` copy). If the runner's working directory is not the module, it falls back to the classpath copy and refuses write mode. Write mode via a system property; a new screen's directory is created on its first write (the module's `src/test/resources` must be visible). On failure, writes `target/ui-fidelity/<dir>/<name>.actual.png` and `.diff.png`; a passing comparison keeps no diff image |
| `MigrationGate` | Runs a legacy and a candidate `Renderer` over a case list and returns a `Report` with a per-case verdict (geometry, pixels, input) and `table()`. A `Capture` carries the frame, painted `rects`, `hits` (where the renderer's own hit testing answers per control) and `actions` (control → action id it fires). Two captures with no rects fail: they prove nothing. |

### Tolerances (#283 §3)

| Check | Rule |
| --- | --- |
| Geometry, float-math screens (pause) | `FLOAT_EXACT`: ≤0.001 px |
| Geometry, integer-centred screens (furnace) | `INTEGER_CENTRED`: ≤1 px on an axis whose framebuffer size is odd, 0 px otherwise. Verified on every committed furnace case (`LegacyGeometryGateTest`). |
| Pixels, document vs legacy, integer asset scale | `EXACT` |
| Pixels, document vs legacy, fractional asset scale | `assetScale`: ±1 texel (`ceil(scale)` px) inside asset rects, exact elsewhere |
| Pixels, a renderer vs its own baseline | `RASTER_DRIFT`: 2 levels per channel, at most 0.1 % of pixels beyond, none of those more than 96 levels off and no 8-connected clump over 12 px. Glyph coverage moves between Skia releases; a dropped icon or label is one solid clump and fails however small a share of the frame it is. |
| Hit regions | Same `GeometryRule` as the paint, under the names `hit <control>` |
| Actions | Exact: the action id each control fires must equal the legacy one |

The shift allowance is **two-way**: a pixel off by more than `levels` matches only when its capture value
appears in the baseline within the radius *and* its baseline value appears in the capture within the radius.
One-way matching passed a deleted outline or focus ring, since the backdrop showing where it vanished is
always within reach in the baseline.

## 3. Committed legacy baselines

They live under `stonebreak-game/src/test/resources/ui/fidelity/<screen>/`. Regenerate them after an intended
legacy change with `-Dui.fidelity.write=true`, and review the PNG diff before committing.

| Screen | Test | Cases |
| --- | --- | --- |
| pause | `ui.fidelity.LegacyPauseBaselineTest` | `field-offline` and `field-online` at the 4 standard viewports; `battle-offline` (one scrim), `field-offline-hover-quit` and `field-online-hover-resync` |
| furnace | `ui.fidelity.LegacyFurnaceBaselineTest` | `unlit` and `lit` (fuel 75 %, cook 60 %) at the 4 standard viewports; `unlit` 800x600@2 (overflow); `lit-hover-main0` |

The tests also pin facts a migration must keep:

- **Field vs battle pause.** The field pause is composited twice (about 72 %), the battle pause once (about
  47 %).
- **Hover isolation.** Hovering one button changes only that button.
- **Lit furnace.** Lighting the furnace changes only the crucible band.
- **Geometry.** The rects the renderers actually draw (pause: `SkijaPauseMenuRenderer.setLayoutSink`; no layout
  maths is copied into the capture) equal the committed oracle (`ui/fixtures/legacy-geometry.json`) exactly.
  The oracle test fails, never skips, when it cannot find the engine fixture.
- **Input.** `LegacyPauseCapture` reports each button's hit region by probing `PauseMenu`'s own bounds checks
  and the action id `UiMouseRouter` fires for it (`stonebreak:screen.pause.*`, `stonebreak:network.resync`).

Furnace slots are empty in the baselines. Item icons are the renderer's GL phase (ledger hard visual 2), which
a CPU raster cannot draw. Icon fidelity belongs to #298's host draw provider.

The legacy `MigrationGate.Renderer`s are `LegacyPauseCapture` and `LegacyFurnaceCapture`. #297 and #298 plug
their document renderer in as the candidate:

```java
MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT,
        c -> PixelTolerance.assetScale(c.viewport().uiScale(), assetRects(c)))
    .run("pause", LegacyPauseBaselineTest.cases(), new LegacyPauseCapture(), documentCapture);
assertTrue(r.passed(), r.table());
```

**Pause (#297)** runs it: `ui.fidelity.PauseDocumentGateTest` with `DocumentPauseCapture` (the shipped
`ui/documents/pause.sbui` opened through `GameUiDocuments.openBound` on the same `LegacyUiRaster`, painted twice for
`field` and once for `battle`, hover by a real pointer move, actions by real clicks against a recording
`GameUiHost`). All 12 cases (incl. `field-offline-hover-resume`, #297) pass at `FLOAT_EXACT` + `EXACT`: 0 pixels differ, worst channel delta 0.

**After the legacy renderer is deleted** (`migrated`), `MigrationGate` can no longer run against it. The gate
then becomes the candidate against the legacy baselines committed while both existed, at `EXACT` (or the
`assetScale` contract at fractional scales), not `RASTER_DRIFT`. Never regenerate those PNGs from the candidate
with the write flag: they are the legacy oracle, and rewriting them from the new renderer makes the gate compare
the document with itself.

## 4. The ledger gate

The summary table's status column is now checked by `UiMigrationLedgerCoverageTest`. The allowed statuses are
`not started`, `baselined`, `in progress`, `migrated`, `blocked`, `retained-bridge` and `n/a`.

- Any row in `baselined`, `in progress` or `migrated` needs a `**Fidelity:**` line naming its baselines
  (`<screen>/<case id>`). Each named PNG must exist.
- A `migrated` row also needs a `**Gate:**` line naming a test class whose source constructs and runs a
  `MigrationGate` (a class that merely exists does not count).
- A `blocked` row needs a `**Blocked:**` line (the issue or missing capability it waits for); a
  `retained-bridge` row (stays host code, bridged by a draw provider) a `**Decision:**` line.
- Coverage: every game class named `*Screen`, `*Menu`, `*Overlay`, `*Renderer`, `*Panel`, `*Dialog`,
  `*Tooltip`, `*Hud`, `*Popup`, `*Toast`, `*Prompt` or `*Banner` (plus the Focus battle element packages; the
  document host `ui/runtime` excepted) must be named in the summary table or a `### row`. Mentions in the
  cross-cutting notes or known gaps do not count.

The pause rows (offline, online, over-battle) and the furnace row are `baselined`.

The other gates in #282 are checked by the suites of their own issues: interaction replay (#288
`InputReplayTest`), host and action behaviour (#289), and save, open and export (#293 and #285). For each
migration, the screen's issue states the evidence it attached.

## 5. Runtime budgets: `com.openmason.engine.ui.diag`

| Budget | Menu (`UiBudgets.MENU`) | HUD (`HUD`) | Minigame (`MINIGAME`, any `ui-canvas` document) | Kind |
| --- | --- | --- | --- | --- |
| Lua heap | 4 MiB | 2 MiB | 32 MiB | hard: Lua state allocator cap |
| Watchdog | 50 ms per call | 16 ms per call | 50 ms per call | hard: the call ends with `DEADLINE` |
| Lua time per frame (mean) | 0.5 ms | 0.25 ms | 2 ms | soft: diagnostic |
| Lua time in one frame (spike) | 4 ms | 2 ms | 16 ms | soft: diagnostic, 3 spikes per window |
| Relayout per invalidation | 0.25 ms per 100 elements (scales linearly above) | 0.15 ms per 100 | 0.25 ms per 100 | soft: diagnostic |

`UiBudgets.forDocument(doc)` picks menu or minigame; `forDocument(doc, true)` is for documents the host draws on
every gameplay frame (hotbar, vitals, chat feed: #300/#301), which get `HUD` unless they are a canvas minigame. `scriptOptions()` turns it into the Lua state's hard limits. The
game (`GameUiDocuments.scripts`) and the Open Mason preview open code-behind with these limits.
`UiScriptOptions.DEFAULTS` (16 MiB / 100 ms) stays the permissive default for tests and tools.

`UiFrameMonitor.attach(view)` is a view extension, so it closes with the view. Per host frame it charges the
Lua wall time since the previous frame (handlers, deliveries, `update(dt)`; load and `on_open` are excluded)
and reads the Lua heap. It also observes every `UiDocumentInstance.update()` that ran Yoga:
`UpdateStats.nanos` is new, and so is `addUpdateObserver`. `UiBudgetTracker` holds the rules, and it is pure:

- **Script time** is over when the mean over a 120-frame window exceeds the budget. It is judged from frame
  30, and it clears below 80 %.
- **Memory** is over above 80 % of the cap (`memoryWarnBytes`; the cap is the Lua state's hard limit, so the
  warning comes before the out-of-memory error), and it clears below 90 % of that.
- **Script spikes** (`SCRIPT_SPIKE`): a frame spikes when its Lua time exceeds `scriptSpikeMillis()` (8× the
  per-frame budget, at least 1 ms). Over when 3 frames of the window spiked, clear when none has; judged from
  frame 30. A mean hides a handler that stalls one frame in twenty.
- **Layout** is over when 3 of the last 16 warm invalidations exceed the budget, and it clears when at most one
  does. The cold first layout is recorded but not judged, and so is the first layout after each reload
  (`UiFrameMonitor` sees the instance's document change and calls `coldStart()`).
- Every metric reports on its rising edge, at most once per window.

Overruns go to a short recent list and a host listener. The first overrun of each metric also becomes a
`BUDGET_SCRIPT_FRAME`, `BUDGET_SCRIPT_SPIKE`, `BUDGET_MEMORY` or `BUDGET_LAYOUT` warning on the instance.

**Leaks.** `ui.script.DocumentLifecycleLeakTest` opens and closes, and reloads, a screen that watches host
data, awaits an action, sleeps and tweens, 40 times each: afterwards no scope stays open on the host, no data
subscription survives, no call or timer is pending, every Lua state and native layout tree is freed, and the
heap of the reloaded screen stays flat.

Where the numbers show up:

- **Game:** `GameUiDocuments.monitor(view, name)` registers the document in `GameUiDiagnostics`. The F3
  overlay shows a "UI Documents" card under VRAM, and overruns are logged as `[ui-budget]`. The dev overlay
  (`-Dstonebreak.uidoc`) is monitored, and migrated screens will be.
- **Open Mason preview:** the budget lines appear under Scripts, red when over. Preview frames include
  graph-debug tracing, so script time reads slightly high there.

### Re-measuring on the slowest machine

The #283 numbers were measured on the dev machine. Run this on the slowest supported machine:

```
mvn test -pl openmason-engine -Dtest=UiBudgetBenchTest -Dui.bench=true
```

It prints relayout time for a 100-element menu and Lua time per frame for the scripted pause and the
1k-sprite minigame samples, all through `UiFrameMonitor`. It fails when a p95 is over budget. Dev machine,
2026-10-06 (28 cores, JDK 25.0.3):

| Measure | p50 ms | p95 ms | Budget ms |
| --- | --- | --- | --- |
| relayout, 100-element menu | 0.018 | 0.050 | 0.25 |
| Lua per frame, scripted pause (event-driven, no `update`) | 0 | 0 | 0.50 |
| Lua per frame, minigame | 0.127 | 0.132 | 2.00 |

The slowest supported machine has **not** been measured yet. Record its table here when it has.
