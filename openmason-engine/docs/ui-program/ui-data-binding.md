# UI data bindings and host contracts (#289)

Authored UI reads live game data and asks the game to do things. Game rules stay in code. Inspector bindings, Lua
code-behind (#292) and compiled graphs (#291) all go through one contract, so they see the same values, the same
validation and the same lifetime rules.

| Part | Where |
| --- | --- |
| Host contract (data, actions, scopes, edits, fixtures) | `openmason-engine/src/main/java/com/openmason/engine/ui/data` |
| Bindings, list views, activation gate | `openmason-engine/src/main/java/com/openmason/engine/ui/runtime/binding` |
| Game host | `com.stonebreak.ui.runtime.GameUiHost` (+ `GameUiDocuments.openBound/bind/activationGate`) |
| Editor host | `openmason-tool` `systems/uiPreview/UiDocumentPreviewPanel` on `FixtureHost` |

## 1. The host

A `UiHost` holds three things:

- a `DataRegistry` of **data roots**: the first segment of an absolute path (`session` in `session.online`);
- an `ActionRegistry` of **actions**;
- a `UiThreadQueue`, drained once per frame on the UI thread.

Each root and each action belongs to a versioned `HostContract` (`stonebreak:session` v1). `UiHost.profile()` builds
the `UiHostProfile` that the format's requirement check uses. Nothing is reached by reflection: a document can only
touch what was registered.

### Data

| Type | Use |
| --- | --- |
| `DataType` | Schema: `Scalar` (format `ValueType`), `Obj` (named fields), `ListOf` (items + identity field), `ANY`; `orNull()`. `problem(value)` names the failing member (`players[0].ping: expects int, got number`). |
| `DataPath` | Parsed wire path: `session.players[1].name`, `.count`, `.`. `evaluate`, `typeFrom`, `with`. |
| `DataState` | `Ready(value)`, `LOADING`, `MISSING`, `Failed(message)`. A ready `null` is a value that says "nothing". |
| `DataCell` | The standard source: one typed snapshot. `set` validates (a mismatch is a host bug and throws) and notifies only on real change. `post` is the thread-safe form; posts coalesce, so a producer that changes every tick costs one UI update per frame. |
| `DataCollection` | A list with item identity. Edits notify incremental `ListChange`s (`Inserted`, `Removed`, `Updated`, `Moved`, `Reset`). `setAll` diffs the new snapshot by identity (`ListDiff`). `post(items)` is the thread-safe form: the snapshot is copied, coalesced and diffed at the drain like `setAll`. |

Sources are confined to the UI thread. Nothing polls a source every frame: UI reads the state once, then reacts to
notifications.

**Confinement is checked.** The thread that drains the queue is the UI thread. From the first drain on, `set`,
`setState` and collection edits from any other thread throw `IllegalStateException` (naming `post()`), instead of
silently racing the listener lists. Setup before the first frame is not checked.

**Posts and sets.** A UI-thread `set` (or collection edit) supersedes a `post` still waiting for the drain, so an older
cross-thread value never lands on top of a newer one (a furnace closed by the server and a new one opened on the UI
thread in the same frame). A posted value is checked against the schema on the UI thread, not in the producer: a
mismatch is logged and turns the source `Failed`, so bound elements show the failure; the next valid post recovers.
A server tick or network handler never gets an exception for a host bug.

**Bounded drain.** Tasks posted by a drained task (an async completion settling on the UI thread) run in the same
drain, for up to `UiThreadQueue.MAX_ROUNDS` (8) rounds; a task that keeps re-posting itself continues next frame
instead of livelocking it.

### Actions

`ActionSpec`:

| Field | Meaning |
| --- | --- |
| `id` | `ns:dotted.name` (`stonebreak:network.resync`) |
| `contract`, `since` | the capability it belongs to, and the contract version that introduced it |
| `params` | object schema; an unknown or mistyped argument fails the call |
| `result` | schema, checked when the call completes |
| `reentrancy` | duplicate-click policy: `REJECT_WHILE_PENDING` (default), `CANCEL_PREVIOUS`, `PARALLEL` |
| `cancellable` | whether the handler is told about cancellation |

**Threading.** A handler is called on the UI thread and must not block. It returns a `CompletionStage`: quick work
completes it before returning; slow work completes it later, from any thread. The result is marshalled back to the
UI thread and checked there.

**Cancellation.** A `cancellable` handler sees `ActionContext.isCancelled()` and runs its `onCancel` hooks. Any other
handler is not told (its hooks never run, `isCancelled()` stays false): it runs to completion, and the UI drops its
result.

**Versions.** A document declares each contract at a version (`hostApis`). An action whose `since` is newer than the
declared version is `CAPABILITY_MISSING`: the document was authored against a contract that did not have it, and
must declare the newer version (`UiHost.openScopeAt(documentId, versions, problems)`; `UiBinder.open` passes the
manifest's versions; `openScope(documentId, ids, problems)` leaves versions unchecked).

**Data roots are gated too (#327).** A document observes only the roots whose contract it lists in `hostApis`,
whoever asks: in its scope an undeclared root reads as `FAILED` (`CAPABILITY_MISSING: ...`), `typeOf` is `null`,
`watch`/`watchList` return `Subscription.NONE` without subscribing the source, and `EditSession.stage` rejects it.
Callers that can fail loudly check first: Lua `ui.read`/`ui.watch` (and graphs, which compile to them) call
`UiScope.requireDeclared(path, site)`, which throws `UiActionException` `CAPABILITY_MISSING` naming the root's contract
and the call site, so the script errors at its line; the binder reports a `CAPABILITY_MISSING` element diagnostic and
the target keeps its authored value. A root the host lacks is not a capability problem; it reads as `MISSING`. A
`null` declaration (`openScope(id, null, ...)`, host-side code and tests) is unchecked. Without the gate, an untrusted
SBUI could bind an undeclared root and read the bound element's text back from Lua.

**Authority.** The handler is where the game validates its rules, exactly as the legacy screen code did. The pause
buttons and the `stonebreak:screen.pause.*` actions both call `PauseMenuActions`.

### States of a call

`ActionCall` is `PENDING`, then exactly one of:

- `SUCCEEDED` (with a result that fits the schema)
- `FAILED` (the handler threw or failed, or the result broke its schema: `RESULT_MISMATCH`)
- `CANCELLED`
- `REJECTED` (a duplicate under `REJECT_WHILE_PENDING`, or a closed scope; the handler never ran)

`whenSettled` callbacks run on the UI thread, in order. One that throws is reported (`ACTION_CALLBACK_FAILED`) and
never stops the others, the scope or the screen.

**Mismatches fail with context.** A problem with the call itself throws `UiActionException`:

- `UNKNOWN_ACTION`
- `CAPABILITY_MISSING`: the action's contract is not in the document's `hostApis`, or the action is newer than the
  declared contract version; for `requireDeclared`, the data root's contract is not in `hostApis` (`actionId()` is
  then the path)
- `PARAM_MISMATCH`

The message always names the call site, for example
`PARAM_MISMATCH stonebreak:network.resync at document stonebreak:ui/pause_menu, node panel/resync (script):
parameters {full: bool}: full: expects bool, got string`.

## 2. Scopes, generations and epochs

`UiScope` is everything one open document holds on the host:

- its watches;
- its pending calls;
- its edit draft.

`CallSite(documentId, elementKey, origin)` records who called. The origin is `BINDING`, `SCRIPT`, `GRAPH` or `HOST`.

| Event | What happens |
| --- | --- |
| Screen close: `UiScope.close()` | Every watch is released, every pending call is cancelled, the draft is dropped. Idempotent; never throws, even when hooks or callbacks do. |
| Document reload: `renew()`, called by the binder on `UiDocumentInstance.reload` | Pending calls are cancelled and the **generation** moves on. |
| World leave or disconnect: `UiHost.advanceEpoch()`, called by `GameUiHost` when the session returns to `MENU` | The **epoch** moves on, and pending calls in every open scope are cancelled. |

A completion that arrives for a closed scope, an older generation or an earlier epoch is rejected. It is reported as
`STALE_COMPLETION` (info), and no callback sees it as a success.

**A handler that closes its own screen.** Pause "resume" and "quit" close the screen (or leave the world) inside their
handler. The call whose handler is running is exempt from the cancellation it causes: when the handler returns a
finished stage, the call settles with that result (`SUCCEEDED`), so a script awaiting it sees success. Every other
pending call is cancelled as usual. A handler that closed its screen and returned an unfinished stage is cancelled as
soon as it returns, and its late completion is stale. (The Lua runtime defers its own close until the outermost call
returns, and the game host closes screens at frame end, so no host frees a document underneath a running handler.)

**Epoch hooks.** `UiHost.onEpoch(reason -> ...)` runs after every `advanceEpoch`, once pending calls are cancelled.
Hosts reset per-world data there (an open furnace, the inventory), so the next world's screens never show the last
world's values.

`scope.read(path)` and `scope.watch(path, listener)` are draft-aware. A scope opens one source subscription per root
and fans it out to its watches. `subscriptionCount()` is 0 after close.

## 3. Bindings

`UiBinder.open(instance, host, converters)` opens the document's scope and binds every element. Its declared
contracts are the manifest's `hostApis`, and scope problems become element diagnostics.
`GameUiDocuments.bind(view, host, converters)` attaches the binder to a `UiDocumentView`:

- `render` calls `binder.sync()` after layout;
- `close` closes the binder before the document.

### Inherited data sources

These follow Unity UI Toolkit:

- A node's `dataSource` applies to its whole subtree. `.x` is relative to the nearest ancestor's source; `root.x` is
  absolute.
- A **component's** subtree reads its instance's parameters. A `prop:<param>` binding on the `Instance` node keeps a
  parameter live: an instance's props are its parameters.
- **Slot content** reads the source of the document that authored it.
- A **list row** reads its item.

### Pipeline

source state → converter → target type check → the element's binding layer.

| Source state | Target | `BindingStatus` |
| --- | --- | --- |
| `Ready(v)` | `v` after conversion | `ACTIVE` |
| `Ready(null)` | authored/default value | `NULL` |
| `LOADING` | authored/default | `LOADING` |
| `MISSING` (no root, absent member) | authored/default | `MISSING` (+ `UNKNOWN_DATA_SOURCE` for a root the host lacks) |
| `Failed(msg)` | authored/default | `FAILED` (+ `BINDING_SOURCE_FAILED`) |
| value, converter or target does not fit | authored/default | `INVALID` (+ `BINDING_TYPE`, `MISSING_CONVERTER`, `CONVERTER_FAILED`) |

- `class:` targets need a bool.
- `prop:` targets are checked against the widget descriptor.
- `style:` values are checked by the cascade (`STYLE_VALUE`).

### Converters and computed values

A `converter` names a pure code-behind function: a `UiConverter(result type, to, back?)` that `UiConverters`
supplies. Lua code-behind declares them with `ui.converter(name, {result, to, back})` and the script runtime supplies
them to the binder (`UiScripts.open`, [ui-scripting.md](ui-scripting.md) §5). Compiled graphs (#291) will provide
them through the same interface. A converter call is pure: actions and writes are refused.

**Localized formatting** needs no converter. Bind `prop:textArgs` to an object and let the label's `textKey` format
it. For example, `furnace.progress` = `Smelting {progress, number, percent}`.

### Modes

| Mode | To the target | Back to the source |
| --- | --- | --- |
| `to-target` | every change | never; local writes are refused (`BOUND_PROPERTY_WRITE`) |
| `once` | the first ready value, then unsubscribes | never |
| `two-way` | every change | local edits are staged into the draft |
| `to-source` | never | local edits are staged into the draft |

**Two-way and to-source writes go to the scope's draft, never to the source** (§4):

- A `TextField` stages on `COMMIT` (Enter, or blur with `commitOnBlur`), never per keystroke.
- Other widgets stage each local write.
- A rejected edit sets `:invalid` and keeps the user's input on screen.

Only host data is writable (`BINDING_NOT_WRITABLE` otherwise). A converter needs `back` to be written through.

### Property ownership

The full layer order, lowest first:

- **Props:** descriptor default → authored → instance overrides → binding value → local write.
- **Styles:** sheet cascade → inline → instance overrides → binding → local write → animation channel.
- **Classes:** authored → override add/remove → binding toggles → local add/remove.

Under `to-target` or `once`, the binding owns its target. Under `two-way` or `to-source`, the local layer holds the
edit in progress above the binding's base value. The binder clears that local value once the draft or committed value
shows through the binding again (after a stage, or on `revertEdits`).

Animation channels always win on screen and never write a source. A bound `TextField` is read-only only under
`to-target` and `once`.

Only one binder may hold an instance's binding layer at a time (`UiDocumentInstance.claimBindings()`). `BindingAccess`
is the only writer of that layer.

## 4. Two-way edits

`EditPolicy(commitAction, validator)` makes a root editable (`DataRegistry.registerEditable`).
`scope.edits()` is an `EditSession`:

1. **`stage(path, value)`**: checks the schema, then the root's validator, which sees the whole draft for cross-field
   rules. The edit lands in the draft. The source is untouched; everything bound in this scope reads the draft.
   Another scope still sees the committed value.
2. **`apply(root)`** / `binder.applyEdits()`: sends `{value: <drafted root>}` to the commit action. When the action's
   parameter schema declares `changed`, it also receives the staged member paths (`["settings.fov"]`), so a host can
   save only those. The handler validates again and saves. On success the applied members leave the draft (edits
   staged while the commit ran stay), and the source now holds the value. On failure the draft stays, with
   `lastError`. A double Apply is refused by the reentrancy policy.
3. **`cancel()`** / `binder.revertEdits()`: rollback. Drafts and local edits go (on `two-way` and `to-source`
   targets alike), and bound elements show the committed values again. `cancel(root, path)` drops one member.

**The draft follows the source.** The session keeps the staged *members*, not a copy of the root: the drafted value
is always the source's current value with the staged members laid over it. A change made elsewhere while the screen
edits (a volume hotkey, another screen's apply, a server echo) shows through every member this screen did not touch,
and `apply` never writes an old value back over it. A member staged here that has since changed in the source is a
**conflict** (`conflicts(root)`): the staged value still wins on apply, and the screen can show it or drop it. Staging
a member back to the committed value, or the source catching up with the draft, leaves nothing to apply.

A converter whose `back` throws (or returns nothing) is reported as `CONVERTER_FAILED`; the field turns `:invalid`
and keeps the user's input, exactly like a validator rejection. It never throws into input dispatch.

Closing the screen drops unapplied edits.

## 5. Collections: `ListView`

`ListView` is a built-in widget (version 1) that needs the **`ui-data`** format feature. It is a vertical scroll
container. Its **single authored child is the row template**, which the builder never instantiates directly. Bind its
items with `prop:items`.

| Prop | Default | Meaning |
| --- | --- | --- |
| `items` | null | the collection |
| `itemKey` | null | identity field for sources that are not host collections (a host `DataCollection` declares its own) |
| `itemHeight` | 0 | > 0 virtualizes, with fixed row height in logical px |
| `columns` | 1 | > 1 lays the rows out as a wrapping grid of that many items per line |
| `selectionMode` | `single` | `none` disables click selection |

- **Rows** are built from the template with key `<list>/r<n>` (virtualized: `<list>/v<n>`). Descendants are keyed
  `<row>/<nodeId>`, like a component instance, so rows never collide. A row's data source is its item. A template
  that is an `Instance` with `prop:<param>` bindings is a slot template: the component reads the item through its
  parameters.
- **Identity and incremental changes.** A host collection's `ListChange`s are replayed directly. Any other list is
  diffed by identity. A row follows its item through moves and updates, keeping its element state; only removed
  items lose their rows.
- **Selection** is kept by identity across every change and cleared when its item goes. The selected row matches
  `:checked`, and `onSelectionChanged` reports changes.
- **Virtualization.** Only visible rows exist, plus one line of overscan beyond each edge (view height ÷ item height
  + 2 lines; the window starts a line above the view), so keyboard/controller navigation can step past either edge
  and the list scrolls a line at a time (#326). Two spacers keep the scroll extent at
  `items × itemHeight`. Scrolling **recycles** rows: their item feed is swapped, which rebinds the whole row subtree
  without new elements or leaked listeners. A recycled row starts clean: the local (script / edit-in-progress)
  values of its subtree belonged to its old item and are dropped (the list's own row sizing stays), `:invalid`
  clears, and so does its animation state (`UiDocumentInstance.recycled`, #325): held clip/tween values and running
  transitions go, a clip or tween left animating nothing is interrupted (listener sees `stopped`), state machines of
  component instances in the row restart in their initial state, their code-behind closes and starts over in a
  fresh environment ([ui-scripting.md](ui-scripting.md) §3), and the new item's look settles without a
  transition from the old one. `UiBinder.onRecycled((rows, replacements) -> ...)` reports each window refresh as one batch:
  every recycled row with the row now showing its old item (or null when it scrolled out), all as they were before the
  refresh; `UiDocumentView.bind` wires it to `FocusManager.recycled`, so **focus follows the
  item**, never the recycled element.
- **Grid** (`columns > 1`, for inventories and hotbars). The view lays out as a wrapping row (`flex-direction: row`,
  `flex-wrap: wrap`, set on its local layer) and every row is `100% / columns` wide. Spacing between cells belongs
  inside the row template (padding), since margins would break the wrap. With `itemHeight > 0` virtualization works
  by **lines**: the window is whole lines of `columns` items, `itemHeight` is the line height and the spacers span
  full lines. Spatial navigation moves across the realized cells by geometry.
- **Robustness.** A row (or spacer) a script removed behind the binding's back, or a change list that no longer
  replays, makes the list rebuild its rows from the items (with a `LIST_TEMPLATE` warning for the latter) instead of
  throwing.

## 6. Activation gate

`UiActivation.check(doc | sbui, components, host)` runs before a screen activates. `require` throws
`UiActivationException`. Each diagnostic is a format `UiDiagnostic` with entry and JSON pointer.

| Finding | Severity |
| --- | --- |
| manifest needs an `uiApi`, layout semantics, feature, host API or provider the host lacks (`UiHostProfile.check`) | error; warning for an `optional` host API |
| an absolute path (dataSource or binding, in the document or any component) names a root the host lacks: `UNKNOWN_DATA_SOURCE` | error |
| a root's contract is missing from the manifest's `hostApis`: `UNDECLARED_HOST_API` | error: the scope would refuse the document that root (#327) |

An export is checked against its manifest's requirement union. `GameUiDocuments.openBound(sbui, ...)` refuses before
instantiating anything, so the legacy screen stays. It also refuses (C9) when a required dependency does not resolve
or the export does not fit the host profile (`HostCompatibility`, `ResolvedUiAssets.check()`), and when the
document's input gate fails (`requireInputGate`). Shipped screens open through it via the game's document screen host
(`ui.runtime.screens.DocumentScreenHost`; see [ui-runtime.md](ui-runtime.md) §11).

The dev overlay and the editor preview check too. They show the document anyway, and log or list the findings.

## 7. Hosts

**Game: `GameUiHost`.** `GameUiHost.get()` is created on the first frame, and `FrameRenderer` drains it every frame.

| Contract | Offers |
| --- | --- |
| `stonebreak:session` 1 | `session {mode, online, hosting}`, posted from `MultiplayerSession` mode changes; `MENU` advances the epoch and resets the per-world roots below to empty |
| `stonebreak:furnace` 2 | `furnace {open, lit, progress, fuel}`; since 2 also `ingredientSlot`, `fuelSlot`, `outputSlot` (stack records). Ticks and server echoes (any thread) only *request* a refresh; the open furnace is read and published on the UI thread at the drain, so a refresh queued by a furnace that has since closed or been replaced can never land |
| `stonebreak:inventory` 1 | collection `inventory` (`main:0..26`) and root `carried` (the stack on the cursor of the open container screen). Slot actions act on the open furnace, workbench or inventory screen: `stonebreak:inventory.slot-click {slot, button?, shift?}`, `.slot-press`, `.slot-drag {slot, button?}` (pointer entered a slot with the button held: right-drag distribution), `.slot-release`, `.sort`, `.craft-all`, `.close` |
| `stonebreak:hotbar` 1 | collection `hotbar` (`hotbar:0..8`, the selected one has `selected: true`); `stonebreak:hotbar.select {index}` |
| `stonebreak:player.vitals` 1 | `vitals {present, health, maxHealth, stamina, maxStamina, mana, maxMana, level, xp, xpNext, dead}` (hundredths) |
| `stonebreak:settings` 2 | editable `settings` with every player setting (`ui.runtime.contracts.SettingsContract`: display, audio, player model, crosshair, graphics, world, UI). `stonebreak:settings.apply {value}` takes the whole draft or only the staged fields, validates the ranges the setters clamp to, writes only changed fields, pushes them to the game systems and saves, holding a UI-scale change for confirmation (`{uiScaleChanged, previousUiScale}`). Since 2: `.set-live {field, value}` for the fields the legacy menu applies immediately (music, LOD, VSync, max FPS, graphics toggles; refused for the rest), `.keep-ui-scale`, `.revert-ui-scale`. Saves from any screen republish it. |
| `stonebreak:screen.pause` 1 | `stonebreak:screen.pause.{resume, statistics, glossary, settings, quit}` |
| `stonebreak:network.resync` 1 | `stonebreak:network.resync` → `{audited}` |

**Item slots.** One record shape for every slot and stack (`ui.runtime.contracts.SlotRecords`):
`{slot, item, objectId, name, count, state, maxStack, selected}` (`slot`/`selected` only in grids; empty = `item: 0`).
Stacks change in place all over the game with no event to hook, so grids, `carried` and `vitals` are compared against a
primitive snapshot at each drain and republished only when they changed (an unchanged frame allocates nothing); a grid
change arrives as single-row `Updated` changes.

**Slot rules stay in the legacy screens.** A slot action synthesizes a `PointerFrame` at the slot's centre in the open
screen's own layout and runs that screen's pointer handler (`inventoryScreen.handlers.ContainerSlotInput`, implemented
by `InventoryInputManager`, `WorkbenchInputManager` and `FurnaceInputManager`). Pick-up, place, stack, swap, split,
right-drag distribution, double-click gather, shift transfer, crafting and the furnace's slot restrictions are the same
code the mouse runs, and the furnace's slot snapshots reach the server through `FurnaceController`'s per-frame sync
(#320 pre-echo rules) as before. Settings live effects are shared the same way (`settingsMenu.managers.SettingsEffects`).

**Declared profile.** `GameUiHost.declaredProfile()` (and `declaration()`) builds the host with every contract, action,
data type and draw provider (`GameUiHost.PROVIDERS`) and no game behind it, so the editor checks exports and fixtures
against the real game host headless. The canonical preview fixture `ui/fixtures/game-host.fixture.json`
(`GameUiHost.FIXTURE_RESOURCE`) covers every root and action; `GameHostFixtureParityTest` keeps it compatible.

The game services behind it are an interface (`GameUiHost.Services`), so tests run the host without game singletons.

**Editor: `FixtureHost`.** Deterministic, with no game services. It offers every contract the document declares, and
records every call. `FixtureHost.compatibility(realHost)` lists where the fixture disagrees with the host the document
really runs against: roots or actions the host lacks, a root or action under another contract, fixture values or
results the real schema rejects, editable vs read-only, undeclared or retyped parameters. A fixture may leave fields
out, but everything it does say must be true of the real host, so the preview never runs on data the game can never
produce. Game parity tests run it against `GameUiHost`.

The fixture comes from `<file>.fixture.json` next to the document, else the archive's own `editor/fixtures.json`. Its
members are:

- `data`
- `collections`
- `contracts` (root → contract id). A root it leaves out takes the declared contract that names it: local id (after
  `ns:`) equal to the root, else ending in `.<root>` (`session` → `stonebreak:session`, `vitals` →
  `stonebreak:player.vitals`; shortest wins). With none it is filed under `fixture:<root>`, which no document
  declares, so the preview refuses it exactly as the game would: a fixture can never widen what a document sees.
- `editable` (root → commit action)
- `actions` (`result`, `error`, or `pending` held until `release`)

Any other top-level member is shorthand for a data root, so `{"session": {"online": true}}` works as it is.

The preview drains the fixture queue once per frame.

## Tests

| Test | Covers |
| --- | --- |
| `DataContractTest` | schemas, paths, cells (notify on change, coalesced posts), collections, randomized identity-diff replay |
| `DataRootGateTest`, `ScriptDataGateTest` | #327: undeclared roots read as failed, never subscribe, cannot be staged, leak no schema; `requireDeclared` names contract and call site; Lua `ui.read`/`ui.watch` refused on a game-like host and on `FixtureHost` (`GameUiHostTest`, `GameHostFixtureParityTest` cover the game host and its fixture) |
| `UiScopeTest` | parameter mismatch with document/node, unknown action, undeclared capability, async results on the UI thread, result mismatch, reentrancy, completion after close / reload / epoch change, failing callbacks and cancel hooks during close, watch release |
| `EditSessionTest` | validation, per-scope drafts, apply, failed apply, cancel, read-only roots |
| `FixtureHostTest` | shorthand fixtures, deterministic action responses and held calls, editable roots, collections |
| `UiBinderTest` | pause online state from notifications; preview fixture vs game-style host give identical trees; furnace progress (width + localized percent) from a posted cell; inheritance (nested sources, absolute paths, live component params, slot content); null/loading/failed/missing fallbacks; type and converter diagnostics; `once`; `to-target` ownership; scripts/graphs/bindings on one contract; close releases everything and rejects late results; reload rebinding; runtime insert/remove; single binder |
| `ListBindingTest` | slot-template rows (component instances) through insert/move/update/remove/reset with row identity and selection retention; plain lists diffed by `itemKey`; click selection; virtualized recycling (same elements, rebound items, stable listeners, selection by identity); missing template |
| `TwoWayBindingTest` | keystrokes stay local, commit stages, nothing saves before apply; invalid commit (`:invalid`), cancel restores; close drops edits |
| `UiDataHardeningTest` | a UI-thread set supersedes a pending post; invalid posts fail the source instead of throwing into producers; coalesced, identity-diffed collection posts; confinement after the first drain; bounded drains; a handler closing its own screen or leaving the world succeeds; async handler that closed its screen is cancelled; only cancellable handlers are told; actions newer than the declared contract version; epoch hooks |
| `EditSessionRebaseTest` | source changes show through untouched members and survive apply; `changed` paths; conflicts and per-member cancel; staging back to committed; edits staged during a commit stay |
| `BindingHardeningTest` | `to-source` rollback; throwing back-converters; rows and virtual rows a script removed; recycled rows drop local edits, animation state (transitions, held/interrupted tweens, component state machines; #325) and report focus replacements; grid layout and line virtualization |
| `UiActivationTest` | pause menu on complete/partial hosts (optional vs required), SBUI union, unknown roots and undeclared contracts with pointers, fixture host stands in |
| `GameUiHostTest` (game) | pause and furnace pilots on `GameUiHost`, preview/game equivalence, epoch on leaving the world, pause/resync actions and their contract checks, settings validation before apply |
| `GameUiHostContractsTest` (game) | slot actions through the real inventory/workbench/furnace rules (click, stack, right-drag sweep, shift transfer, crafting cells, every slot address hit-tests to its slot); refusals; furnace refreshes never stale; single-row grid updates for in-place changes; hotbar selection; world leave resets per-world roots; declared profile without a game |
| `SettingsContractTest` (game) | every setting read and typed, shortest-decimal floats (no dirty drafts), partial commits write only changed fields, ranges refused up front, live vs on-apply semantics |
| `GameHostFixtureParityTest` (game) | the canonical fixture is compatible with the game host and covers every root and action |
