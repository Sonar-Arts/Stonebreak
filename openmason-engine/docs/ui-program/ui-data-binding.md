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
| `DataCollection` | A list with item identity. Edits notify incremental `ListChange`s (`Inserted`, `Removed`, `Updated`, `Moved`, `Reset`). `setAll` diffs the new snapshot by identity (`ListDiff`). |

Sources are confined to the UI thread. Nothing polls a source every frame: UI reads the state once, then reacts to
notifications.

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
handler runs to completion, and the UI drops its result.

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
- `CAPABILITY_MISSING`: the action's contract is not in the document's `hostApis`
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
2. **`apply(root)`** / `binder.applyEdits()`: sends `{value: <drafted root>}` to the commit action. The handler
   validates again and saves. On success the draft is dropped, and the source now holds the value. On failure the
   draft stays, with `lastError`. A double Apply is refused by the reentrancy policy.
3. **`cancel()`** / `binder.revertEdits()`: rollback. Drafts and local edits go, and bound elements show the
   committed values again.

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
- **Virtualization.** Only visible rows exist (view height ÷ item height + 1). Two spacers keep the scroll extent at
  `items × itemHeight`. Scrolling **recycles** rows: their item feed is swapped, which rebinds the whole row subtree
  without new elements or leaked listeners.

## 6. Activation gate

`UiActivation.check(doc | sbui, components, host)` runs before a screen activates. `require` throws
`UiActivationException`. Each diagnostic is a format `UiDiagnostic` with entry and JSON pointer.

| Finding | Severity |
| --- | --- |
| manifest needs an `uiApi`, layout semantics, feature, host API or provider the host lacks (`UiHostProfile.check`) | error; warning for an `optional` host API |
| an absolute path (dataSource or binding, in the document or any component) names a root the host lacks: `UNKNOWN_DATA_SOURCE` | error |
| a root's contract is missing from the manifest's `hostApis`: `UNDECLARED_HOST_API` | warning: works here, but would not tell a host that lacks it |

An export is checked against its manifest's requirement union. `GameUiDocuments.openBound(sbui, ...)` refuses before
instantiating anything, so the legacy screen stays.

The dev overlay and the editor preview check too. They show the document anyway, and log or list the findings.

## 7. Hosts

**Game: `GameUiHost`.** `GameUiHost.get()` is created on the first frame, and `FrameRenderer` drains it every frame.

| Contract | Offers |
| --- | --- |
| `stonebreak:session` 1 | `session {mode, online, hosting}`, posted from `MultiplayerSession` mode changes; `MENU` advances the epoch |
| `stonebreak:furnace` 1 | `furnace {open, lit, progress, fuel}`, mirrored from the open `FurnaceState`'s change listener (ticks and server echoes, any thread) |
| `stonebreak:settings` 1 | editable `settings {uiScale, uiTextScale, reducedMotion, renderDistance, maxFps}`; `stonebreak:settings.apply` validates the same ranges the setters clamp to, then saves. Saves from any screen republish it. |
| `stonebreak:screen.pause` 1 | `stonebreak:screen.pause.{resume, statistics, glossary, settings, quit}` |
| `stonebreak:network.resync` 1 | `stonebreak:network.resync` → `{audited}` |

The game services behind it are an interface (`GameUiHost.Services`), so tests run the host without game singletons.

**Editor: `FixtureHost`.** Deterministic, with no game services. It offers every contract the document declares, and
records every call.

The fixture comes from `<file>.fixture.json` next to the document, else the archive's own `editor/fixtures.json`. Its
members are:

- `data`
- `collections`
- `contracts` (root → contract id)
- `editable` (root → commit action)
- `actions` (`result`, `error`, or `pending` held until `release`)

Any other top-level member is shorthand for a data root, so `{"session": {"online": true}}` works as it is.

The preview drains the fixture queue once per frame.

## Tests

| Test | Covers |
| --- | --- |
| `DataContractTest` | schemas, paths, cells (notify on change, coalesced posts), collections, randomized identity-diff replay |
| `UiScopeTest` | parameter mismatch with document/node, unknown action, undeclared capability, async results on the UI thread, result mismatch, reentrancy, completion after close / reload / epoch change, failing callbacks and cancel hooks during close, watch release |
| `EditSessionTest` | validation, per-scope drafts, apply, failed apply, cancel, read-only roots |
| `FixtureHostTest` | shorthand fixtures, deterministic action responses and held calls, editable roots, collections |
| `UiBinderTest` | pause online state from notifications; preview fixture vs game-style host give identical trees; furnace progress (width + localized percent) from a posted cell; inheritance (nested sources, absolute paths, live component params, slot content); null/loading/failed/missing fallbacks; type and converter diagnostics; `once`; `to-target` ownership; scripts/graphs/bindings on one contract; close releases everything and rejects late results; reload rebinding; runtime insert/remove; single binder |
| `ListBindingTest` | slot-template rows (component instances) through insert/move/update/remove/reset with row identity and selection retention; plain lists diffed by `itemKey`; click selection; virtualized recycling (same elements, rebound items, stable listeners, selection by identity); missing template |
| `TwoWayBindingTest` | keystrokes stay local, commit stages, nothing saves before apply; invalid commit (`:invalid`), cancel restores; close drops edits |
| `UiActivationTest` | pause menu on complete/partial hosts (optional vs required), SBUI union, unknown roots and undeclared contracts with pointers, fixture host stands in |
| `GameUiHostTest` (game) | pause and furnace pilots on `GameUiHost`, preview/game equivalence, epoch on leaving the world, pause/resync actions and their contract checks, settings validation before apply |
