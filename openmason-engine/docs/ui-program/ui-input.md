# UI input: routing, focus, text, localization and accessibility (#288)

One interaction model for every Stonebreak UI document. It is built on the runtime of #287
([ui-runtime.md](ui-runtime.md)) and serialized through the format of #284
([omui-sbui-wire-contract.md](omui-sbui-wire-contract.md)).

| Part | Where |
| --- | --- |
| Router, events, focus, drag, text fields | `openmason-engine/.../ui/runtime/input` |
| Text editing model (graphemes, selection, IME) | `openmason-engine/.../ui/text` |
| Localization (messages, plurals, pseudo-locale) | `openmason-engine/.../ui/l10n` |
| Semantic tree | `openmason-engine/.../ui/runtime/access` |
| Game host | `com.stonebreak.ui.runtime.GameUiInput` (+ `GamepadUiSource`), called first by `input/MenuInputRouter` |
| Editor host | `openmason-tool` `systems/uiPreview/UiDocumentPreviewPanel` via `PreviewInput` and `platform/ToolInputTap` |

`UiDocumentView.input()` is the document's `UiInputRouter`. Hosts feed it raw input and fall through to gameplay
or editor shortcuts only when it reports the input **unconsumed**.

## 1. Coordinates

| Space | Unit | Used by |
| --- | --- | --- |
| pixel | device (framebuffer) pixels of the document's viewport | layout, painting, hit tests, the router |
| UI | logical pixels, `pixel / (uiScale × pixelRatio)` | documents, scripts (`PointerEvent.logicalX`) |
| editor | screen points of the preview image | the tool; `PreviewMapping` converts (zoom is a view transform) |

An element's local space is the inverse of `UiCoordinates.elementTransform` (today: its placed rect origin, which
already includes ancestor scroll offsets and `translate-x/y`). `UiTransform` is a full 2D affine with an exact
inverse, so `scale`/`rotate` (#295) compose there without changing any handler. `PointerEvent.localX/Y` is relative
to the element whose handler runs.

A point outside `[0, width) × [0, height)` is **not on the document**: it reaches only a live pointer capture or
drag. The preview additionally treats "not over the image" (covered by another editor window) the same way, so
preview input stays within the displayed canvas.

## 2. Events

`UiEventType` lists every event. Bubbling types travel root → target (trickle-down), target, then target → root
(bubble-up); non-bubbling types stop at the target (Unity UI Toolkit).

| Group | Types | Bubbles |
| --- | --- | --- |
| pointer | `POINTER_DOWN`, `POINTER_UP`, `POINTER_MOVE`, `POINTER_CANCEL`, `CLICK`, `WHEEL` | yes |
| hover | `POINTER_ENTER`, `POINTER_LEAVE` (one per element of the entered/left chain) | no |
| keyboard | `KEY_DOWN`, `KEY_UP`, `TEXT_INPUT`, `COMPOSITION` | yes |
| actions | `NAVIGATE`, `SUBMIT`, `CANCEL` | yes |
| focus | `FOCUS_IN`, `FOCUS_OUT` / `FOCUS`, `BLUR` | yes / no |
| text field | `CHANGE`, `COMMIT` | yes |
| drag | `DRAG_START`, `DRAG_OVER`, `DRAG_DROP` / `DRAG_ENTER`, `DRAG_LEAVE`, `DRAG_END` | yes / no |
| popup | `DISMISS` | no |

- **Registration**: `element.on(type, handler)` (bubble-up and at-target) or `on(type, handler, TRICKLE_DOWN)`.
  Handlers survive a live reload with their element key. Lua code-behind (#292) registers through the same call and
  receives the same event objects: there is one dispatch for Java, Lua and compiled graphs.
- **Path**: fixed when dispatch starts. Elements removed by a handler are skipped; handlers added during a dispatch
  apply to the next event.
- **`stopPropagation`** finishes the current element's handlers and skips the rest of the path;
  **`stopImmediatePropagation`** also skips the current element's remaining handlers; **`preventDefault`** skips the
  router's default action.
- **Handled = consumed**: an event a handler stopped or prevented is consumed.
- **A throwing handler** is logged and reported (`EVENT_HANDLER_FAILED`); dispatch continues, so router state never
  ends half-updated.

## 3. Routing and consumption

### Pointer

- Hit testing is #287's `HitTester` over the same `PaintOrder` painting uses, so nested clips, scroll offsets,
  overlays and translation hit exactly what is drawn (`PointerRoutingTest`).
- **Collapsed, `visibility: hidden` and `picking-mode: ignore`** elements never receive pointer events.
- **Disabled** elements (and their subtrees) receive nothing, but a hit on one is still consumed: it blocks what lies
  below it, like a disabled button in a menu blocks the world.
- **Consumed**: a press when the hit test found an element, a modal is open, or a popup was dismissed; a release
  exactly when its press was (or it ends a capture or drag), so a button pressed in the world is always released
  to the world. Full-screen containers that should let clicks reach the world set `picking-mode: ignore`.
- **Hover**: `:hover` is set on the hit element and every ancestor. `POINTER_LEAVE` is sent deepest first and
  `POINTER_ENTER` outermost first, so moving between siblings never leaves their shared parent. A modal hides
  hover outside itself. Hover is re-evaluated after every layout under a still pointer.
- **Click**: press and release over the same enabled element. `clickCount` grows for presses on the same element
  within `doubleClickTime` and `doubleClickDistance` (0.4 s, 4 logical px).
- **Capture**: `capturePointer(el)` sends every pointer event to `el`, also outside the canvas, until the primary
  button is released, the element can no longer receive input (`POINTER_CANCEL`), or interactions are cancelled.
  Text fields capture for drag selection, scrollbars for thumb dragging.
- **Wheel**: `WHEEL` bubbles from the hit element; the default action scrolls the innermost scroll container that
  can still move that way, then the next one out. Shift turns a vertical wheel horizontal. One notch is
  `wheelStep` logical px (40).
- **Scrollbars**: the track is grabbable; `ScrollbarGeometry` is shared with the painter, so the thumb dragged is the
  thumb drawn.

### Keyboard

- `KEY_DOWN`/`KEY_UP` go to the focused element, or the active scope's root when nothing is focused.
- Default actions, in order, unless a handler prevented them:
  1. the focused text field's editing keys (§6);
  2. the player's bindings (`UiActionMap`) map the key to a `UiAction` (`NAVIGATE`/`SUBMIT`/`CANCEL` event to the
     focused element, then the default: move focus, click, dismiss).
- **Consumed** when a handler handled it, a default action ran, or a modal is open (a modal owns every key).
  Keys that do nothing in the UI fall through: with no focusable element, arrows remain gameplay's; with no popup,
  drag or edit to undo, Escape remains the screen's (it opens the pause menu).
- **Release and repeat** are consumed exactly when their press was, even if a modal opened in between. A repeat or
  release of a key whose press this document never saw (or did not consume) is not the document's: a key held across a screen switch never acts in the new screen (the Focus battle
  rule, `MenuInputRouter.routeBattleKey`).
- **Repeat**: keyboard repeats come from the platform at the player's OS rate (`MKeys.REPEAT`). Navigation and paging
  repeat; **submit and cancel never repeat**.
- **Text**: committed text goes to the focused text field (consumed even when its filter drops it). Whole code points
  arrive: the game routes supplementary-plane characters (emoji) to documents, while legacy screens keep their BMP
  filter. Lone surrogates are dropped.

### Controller

- Standard-layout gamepads (GLFW's SDL mapping, `GamepadButtons`) map to the same `UiAction`s: the D-pad (and the
  left stick, with 0.6/0.35 hysteresis) navigates, bumpers move through the tab order, A submits, and B cancels.
- Held navigation repeats on the router clock (`controllerRepeatDelay` 0.4 s, `controllerRepeatInterval` 0.08 s);
  the clock only advances through `tick`, so replays are deterministic.

### Actions and bindings

`UiAction` ids (`ui.navigate.up` … `ui.next`, `ui.previous`, `ui.submit`, `ui.cancel`, `ui.page.up`/`down`) are the
vocabulary of bindings and hints.

| Action | Keys (default) | Controller |
| --- | --- | --- |
| navigate | arrows (any modifiers) | D-pad, left stick |
| next / previous | Tab / Shift+Tab (exact) | RB / LB |
| submit | Enter, keypad Enter, Space (no modifiers) | A |
| cancel | Escape | B |
| page up / down | Page Up / Page Down | — |

A chord with modifiers matches exactly; an any-modifier chord matches whatever is held; exact chords win.
Caps/Num Lock are ignored. `UiActionMap.toWire`/`fromWire` round-trip remaps through game settings
(`uiBindings`). Actions missing from an older file keep their defaults.

**Hints**: a document declares what an action does (`"actionHints": {"ui.submit": "Select"}`); the glyph
(`Enter`, `Shift+Tab`, `A`, `LB`) always comes from the current bindings and the last-used device
(`ActionHints`). Remapping or picking up a controller changes every hint without touching documents.

## 4. Focus

- **Focusable**: the `focusable` prop, else the widget default (`Button`, `TextField`). The element must also be
  enabled through its ancestors, not collapsed or hidden, and inside the active scope.
- **Tab order**: positive `tabIndex` first (ascending, tree order within equal values), then `tabIndex: 0` in tree
  order. A negative `tabIndex` can be focused by pointer, script or direction, but never by Tab. Tab wraps inside the
  scope.
- **Directional navigation**:
  1. The explicit `navUp/Down/Left/Right` key (resolved inside the element's component first, then absolute). A
     missing or unfocusable target reports `NAV_TARGET_MISSING` and falls back to the search.
  2. The spatial search (`SpatialNavigator`). A candidate qualifies when its near edge is past the current centre
     and its far edge past the current far edge. Its score is the gap along the direction plus twice the gap across
     it (0 when "in the beam"); the lowest score wins; ties go to tree order.
  3. Elements inside the nearest `focusScope: group` are tried first.
- **No wrap-around** for directions. With nothing focused, any navigation focuses the scope's `autofocus` element,
  else its first in tab order.
- An element scrolled out of its scroll container's view is a directional candidate only while focus is already in
  that container: players walk through a list (which scrolls, via `scrollIntoView`) but never jump onto a hidden row.
- **Indication**: keyboard and controller focus sets `:focus-visible`; pointer focus sets only `:focus`. The
  painter highlights a focused `Button` like a hovered one and draws an accent ring on other focus-visible elements.
  A key press on pointer focus reveals it instead of moving it.
- **Pointer focus**: a press focuses the nearest focusable ancestor of the hit element, else clears focus. A press
  on nothing in the document clears it too.

**When the focused element stops being focusable** (disabled, collapsed, hidden, removed, or outside a newly
opened scope):
1. Focus moves to the element that followed it in navigation order, then the one before it, then the scope's first.
2. Focus indication is kept, so a controller player is never left without a focus.
3. Focus clears only when nothing in the scope can take it.

**Virtualized items**: a container that recycles an element for another item calls
`FocusManager.recycled(element, replacement)`. Focus follows the *item* to `replacement` (the element now showing
it), or, when the item is no longer realized, moves as if the element were removed.

**Live reload**: focus moves to the rebuilt twin with the same key, which carries `:focus`.

## 5. Scopes: modals, popups, tooltips

A visible element with `focusScope: modal` or `popup` opens a scope; code can also open one
(`FocusManager.openModal`, `UiInputRouter.openPopup(popup, anchor, onDismiss)`). Scopes stack in opening order.

- **Opening** remembers the focused element and focuses the scope's `autofocus` element, else its first in tab
  order.
- **Closing** (hidden, collapsed, removed, or closed from code) restores the remembered element, with its
  indication, if it can still take focus; otherwise focus goes to the first element of the scope below.
- **Modal**:
  - Tab, directions and focus are confined to it.
  - Pointer input outside it (and outside popups opened above it) is blocked and consumed, empty space included.
  - Every key is consumed.
  - `CANCEL` bubbles to it, so a dialog closes itself on Escape or controller B.
- **Popup**:
  - It confines focus like a modal, but does not block hover outside.
  - A press outside every open popup dismisses them top-down; that press is consumed and activates nothing. A press
    on a popup's anchor closes it rather than reopening it.
  - `CANCEL` dismisses the topmost popup.
  - Window focus loss and screen close dismiss all popups.
  - Dismissal sends `DISMISS` with a `DismissReason`; preventing it keeps the popup open.
  - The default for a declarative popup writes a local `display: none`; `showPopup` shows it again.
  - A popup opened from code runs its callback instead.

**Tooltips**: the hovered element, or its nearest ancestor with `tooltip`/`tooltipKey`, shows after `tooltipDelay`
(0.5 s; a migrating screen sets its legacy timing). Keyboard and controller focus shows the
focused element's tooltip below it; it follows focus and is not hidden by the keys that move it. The hover tooltip
hides on a press, wheel, key, drag, target loss or cancellation, and stays hidden after a press until the pointer
moves to another element. Painted last, above every layer, with
`MTooltip`.

## 6. Drag and drop

1. **Start**: a press on (or inside) a `draggable` element that moves past `dragThreshold` (4 logical px) sends
   `DRAG_START` (bubbling). A handler calls `setPayload`; without a payload the press stays an ordinary press. Code
   can start a drag for controller pick-up (`DragDropController.start`).
2. **Over**: `DRAG_ENTER`/`DRAG_LEAVE` per element as the pointer moves; `DRAG_OVER` bubbles from the hit element,
   and the element whose handler calls `acceptDrop()` becomes the target. Leaving withdraws acceptance.
3. **Drop**: releasing the drag button sends `DRAG_DROP` to the accepting element; preventing it rejects the drop.
4. **End**: exactly one `DRAG_END` per drag, to the source, or to the document root if the source was removed.

**Guarantees** (`DragDropTest`):
- A cancelled drag never drops, including when a handler cancels it mid-negotiation.
- A drop handler that throws counts as a rejected drop: the source keeps its payload.
- `DRAG_DROP` reaches at most one element.
- A `DragSession` id is never reused, and `isLive()` turns false the moment the drag ends. Asynchronous work started
  from a drop (a server request to move an item) checks it before acting on a late reply.
- The source owns its payload until it sees `DROPPED`, so no cancellation can lose an item.

| Ends with | When |
| --- | --- |
| `DROPPED` | an accepting element took the payload |
| `REJECTED` | released where nothing accepts, or the drop handler prevented it |
| `ESCAPE` | the cancel action (Escape, B) |
| `SOURCE_REMOVED` / `TARGET_REMOVED` | the source or the accepting element was removed, disabled or collapsed |
| `DISCONNECT` | multiplayer disconnect or world unload (`MultiplayerSession`, `WorldLifecycle.resetWorld`, before the inventory is saved) |
| `WINDOW_FOCUS_LOST` | the game window lost focus (its releases will never arrive) |
| `SCREEN_CLOSED` | the hosting document closed |

**Cancelling interactions** (`cancelInteractions(reason)`):
- Always: the drag ends; a capture or press gets `POINTER_CANCEL`; held keys and buttons are forgotten (their later
  releases are ignored); controller repeat stops; composition is cancelled; the tooltip hides.
- Window focus loss and screen close also dismiss popups and clear hover.
- Screen close also drops focus.

## 7. Text editing

`TextField` (widget v1, `ui-input` feature):

| Prop | Default | Meaning |
| --- | --- | --- |
| `text` | `""` | the value; every edit writes it and sends `CHANGE` |
| `placeholder` / `placeholderKey` | `""` | shown while empty |
| `multiline` | false | Enter inserts a line break; Ctrl+Enter commits |
| `maxLength` | -1 | user-perceived characters (grapheme clusters) |
| `readOnly` | false | selectable and copyable |
| `password` | false | displayed and exposed as `*`; copy and cut refused |
| `inputFilter` | `any` | `ascii`, `digits`, `integer`, `decimal`, `identifier`: applied to typing and pasting |
| `pattern` | none | full-match regex required to commit |
| `commitOnBlur` | true | losing focus commits a changed, valid value |

**Model** (`TextEditModel`, pure JDK, `TextEditModelTest`):
- Indices are UTF-16 offsets that are always extended-grapheme-cluster boundaries. JDK 25's `BreakIterator` handles
  ZWJ sequences, flags, combining marks, Hangul syllables and CRLF, so a caret, deletion or truncation never splits
  a character.
- Words come from the word `BreakIterator`.
- Typing coalesces into one undo step; paste, cut, delete and newline are their own steps.

**Keys**:
- Arrows move the caret (Ctrl moves by word, Shift extends the selection). Home/End go to the line (Ctrl: the
  whole text). Up/Down move between lines of a multi-line field, keeping the goal x.
- Backspace/Delete remove one character (Ctrl: one word). Ctrl+A/C/X/V edit, Ctrl+Z undoes, and Ctrl+Y or
  Ctrl+Shift+Z redoes.
- Printable keys are consumed so their text arrives as text input; Space never submits from a field.
- Tab and, in a single-line field, Up/Down navigate away.

**Pointer**: a click places the caret by measured glyph advances (not the legacy per-character estimate); a double
click selects a word, a triple click selects all, and a drag selects.

**Validation and commit**:
- Enter commits a valid value (`COMMIT`).
- A value failing `pattern` shows `:invalid` and the painter's error symbol, and nothing is committed.
- Escape reverts to the value at focus-in; with nothing to revert it falls through to the screen.
- Keyboard and controller focus selects the whole value.

**IME composition**: `CompositionEvent` START/UPDATE/COMMIT/CANCEL.
- The preedit is shown underlined at the caret and is not part of the value until committed.
- Starting a composition replaces the selection.
- While composing, editing keys belong to the IME.
- Platform source: none in GLFW 3.4, so this is reachable from code and tests only, and the input gate (§10) blocks
  IME locales.

**Ownership**: a field whose `text` is owned by a binding is read-only until two-way bindings (#289).

**Chat** keeps its behaviour through migration: `ChatTextRules.RULES` (printable ASCII, 256 characters, one line;
emoji travel as `[name]` tokens) reproduces `ChatInputHandler` exactly over randomized sessions
(`ChatTextRulesTest`).

**Caret**: blinks at 600 ms (the legacy `MTextField` period) on the router clock; steady with reduced motion.

## 8. Text decisions settled before freezing APIs

| Topic | Decision |
| --- | --- |
| Shaping | Text paints glyph by glyph through `MPainter.drawString` with one Latin font. The editing model works on grapheme clusters and is shaping-agnostic. Shaped runs (Skija `Shaper`) are a painter change behind `ContentMeasurer.textLine`; until then `COMPLEX_SHAPING` is a missing capability. |
| Fallback fonts | The game font (`Minecraft.ttf`) has no em-dash, bullet, CJK or emoji glyphs. There is no fallback font yet (`FONT_FALLBACK` missing). Password masks and hint glyphs use ASCII. |
| RTL | Caret indices are logical. In a paragraph whose first strong character is RTL (`java.text.Bidi`), Left/Right move visually (Left = forward). Painting mixed runs needs shaping, so RTL locales are gated (`RTL_TEXT`). |
| Localization | Message keys (`textKey`, `tooltipKey`, `placeholderKey`, with `textArgs`) resolve through `UiLocalizer`, which falls back exact locale → language-country → language → fallback locale. Messages use ICU MessageFormat syntax: `{n, number}`, `{n, plural, =0 {…} one {# …} other {…}}` with `offset`, `{g, select, …}`, apostrophe quoting. Plurals use CLDR cardinal rules: explicit rules for 30+ languages; the rest (ja, zh, ko, …) only have `other`. A missing key shows the plain prop, else the key, and reports `MISSING_TEXT_KEY` once. Changing the locale or catalogs bumps the localizer revision; every measured element re-measures on the next update. |
| Long strings | Pseudo-localization (`UiLocalizer.setPseudo`) accents letters and grows every string by about 40 %, exposing truncation. Labels are single-line until wrapping lands (#287 §12). |
| Unicode | Whole code points reach documents. Lengths count grapheme clusters. Filters operate per code point. |

## 9. Accessibility metadata

These props are on every widget (`ui-input` feature). They are ordinary props, so overrides and bindings reach them,
and they survive OMUI save and SBUI export byte for byte (`AccessibilityMetadataTest`).

| Prop | Meaning |
| --- | --- |
| `role` | ARIA-shaped role (`button`, `text-field`, `dialog`, `list-item`, …); unset = from the widget type |
| `accessibleName` | the name; unset = a label's text, else a button's label texts, else a field's placeholder, else the tooltip |
| `accessibleDescription` | the description; unset = the tooltip when a name is set |
| `accessibleValue` | the value; unset = a field's text (masked for passwords) |
| `status` | `info`, `success`, `warning`, `error`, `busy`: painted as a symbol (check, cross, warning, info, gear) and exposed as `status:<s>`. Never colour alone. |
| `actionHints` | §3 |
| `tooltip` / `tooltipKey` | §5 |

`AccessibilityTree.snapshot` builds the semantic tree: role, name, description, value, states (`focused`,
`focusable`, `disabled`, `checked`, `invalid`, `read-only`, `modal`, `status:*`), bounds and hint strings.
Elements with role `none` and no name are transparent. This prepares for a platform bridge; it is **not** a screen
reader.

**Player preferences** (`UiPreferences`, game `Settings`, persisted with the other settings):
- `reducedMotion`: steady caret, and #295 transitions jump to their end.
- `uiTextScale` (0.5–3): multiplies font sizes on top of the UI scale, so text grows without growing the layout.
- `uiBindings`: remapped actions, in wire form.

## 10. Capabilities and the migration gate

`UiInputGate` derives what a document needs and blocks it when the host lacks any of it, so a missing feature
**blocks the screen's migration instead of silently degrading it**. `GameUiDocuments.requireInputGate` throws,
reports `INPUT_GATE_BLOCKED`, and the legacy screen stays. A document needs:
- for a `TextField`: keyboard, text, clipboard, and supplementary-plane text unless its filter is ASCII-range;
- for a `TextField` in zh/ja/ko: IME composition;
- for any text in an RTL locale: RTL and shaping.

| Capability | Legacy screens today | Documents, game (`GameUiInput.CAPABILITIES`) | Documents, editor preview |
| --- | --- | --- | --- |
| pointer, wheel | yes (per screen) | yes | yes (over the image) |
| keyboard | yes, hard-coded GLFW keys | yes, remappable `UiActionMap` | yes while the window is focused |
| text input (BMP) | yes | yes | yes |
| supplementary-plane text (emoji) | **no**: `MIR.onCharacter` drops it | **new** | yes |
| clipboard | yes (GLFW) | yes | yes (process-local unless installed) |
| controller | **none**: no gamepad code anywhere | **new**: GLFW gamepads, D-pad, stick, bumpers, A/B | no |
| tab order, spatial navigation, focus indication | ad hoc per screen (battle menu, result card) | **new**, uniform | **new** |
| modal trapping, popup dismissal, tooltips | ad hoc | **new**, uniform | **new** |
| drag and drop | inventory/workbench/furnace only, not cancellable on focus loss | **new**, cancels on Escape, removal, disconnect, focus loss, close | **new** |
| grapheme-safe editing | **no**: `MTextField` splits surrogate pairs and estimates click positions | **new** | **new** |
| IME composition | no | **missing** (GLFW 3.4 has no preedit) | missing |
| RTL text, complex shaping, font fallback | no | **missing** | missing |
| platform accessibility bridge | no | **missing** (semantic tree only) | missing |

**Hosts**:
- **Game**: `MenuInputRouter` offers every callback to `GameUiInput` first. Pointer input goes only while the cursor
  is free; while it is captured, new key presses and text reach only a document with an open modal (a button focused
  earlier never takes Space from jumping), and releases are always offered. Character input arrives before the BMP
  filter. Gamepads are polled every frame, so a button held when a document opens is not a new press. The focus callback cancels interactions on loss.
  `pollActiveScreen` runs the clock and the controller poll. The dev overlay (`-Dstonebreak.uidoc`) is a full input
  host now and consumes what its document consumes.
- **Editor**:
  - `ToolInputTap` taps GLFW keys and chars ahead of ImGui, which chains to it.
  - The preview forwards keys and text only while its window has keyboard focus and no ImGui text field is active;
    losing that focus cancels interactions like a game window losing focus. Pointer moves are sent only when the
    pointer actually moves.
  - While the document holds the keyboard, it sets `WantCaptureKeyboard` (editor shortcuts stand down) and
    `NoNavInputs` (ImGui navigation stands down).
  - Keyboard routing covers the preview docked in the main window. A preview dragged into its own OS window receives
    pointer input only.

## 11. Format additions

| Addition | Feature |
| --- | --- |
| `TextField` widget v1 | `ui-input` |
| Input/accessibility props on any widget (`UiFeatures.INPUT_PROPS`) | `ui-input` |
| `:focus-visible`, `:invalid` pseudo-states (built in; a declared custom state of the same name is refused) | `ui-input` |
| `textKey`, `textArgs`, `placeholderKey`, `tooltipKey` (`UiFeatures.L10N_PROPS`) | `ui-l10n` |

The common props are features, not widget-version bumps, because they apply to every type, host widgets included.
A reader that predates them refuses the document instead of dropping focus order or an accessible name.

## 12. Not yet

- A platform screen-reader bridge.
- IME and RTL sources and shaped painting (gated, §10).
- Wrapped and multi-line labels.
- Two-way text bindings (#289).
- Lua registration sugar (#292).
- Transition sampling for reduced motion (#295).
- Virtualized list containers: the recycle contract is defined, but there is no container yet.
- An editor UI for remapping bindings.

## Tests

| Test | Covers |
| --- | --- |
| `EventDispatchTest` | phases and order, non-bubbling, stop/immediate/prevent, throwing handlers, handlers added or elements removed mid-dispatch |
| `PointerRoutingTest` | enter/leave chains, consumption, disabled blocking, hidden/collapsed/ignore pass-through, clip and scroll hit regions, nested wheel scrolling, scrollbar drag, capture, off-canvas input, click counts, local coordinates through scroll + translate |
| `FocusNavigationTest` | tab order and `tabIndex`, spatial and explicit navigation, groups, focus-visible, submit, focus loss on disable/collapse/hide/remove, recycle, live reload, scroll-into-view, focusable defaults |
| `ModalPopupTest` | modal trapping, blocking and restoration (nested, removed), cancel bubbling, popup outside-press/Escape/anchor dismissal, prevent, show again, focus loss, popups above modals |
| `KeyRoutingTest` | fall-through, release/repeat pairing, stale keys across a screen switch, navigation repeats and confirm does not (keyboard and controller), a modal prompt outranks a command window and consumes every key (the Focus battle precedence fixtures), handler precedence, remapping, focus-loss forgetting |
| `DragDropTest` | drop, wiggle-is-click, reject, every cancellation (exactly one end, no stale drop, no item lost), Escape consumption, enter/leave, controller-started drags |
| `TextFieldRoutingTest` | typing, Space, word deletion, emoji/ZWJ/combining marks, commit/invalid/revert, blur commit, filters and lengths on paste, password clipboard refusal, IME, multi-line, navigation out, click/double-click/drag selection, read-only, external writes, reduced motion |
| `TextEditModelTest`, `TextBoundariesTest`, `TextDirectionTest` | the editing model and Unicode boundaries |
| `PluralRulesTest`, `MessageFormatterTest`, `UiLocalizerTest`, `PseudoLocalizerTest`, `LocalizedTextTest` | localization, and labels re-measuring on a locale or text-scale change |
| `AccessibilityMetadataTest` | feature gating, OMUI/SBUI round trips, the semantic tree |
| `ReviewRegressionTest` | release pairing with world presses and pre-modal keys, focus and hover tooltips, prevented submenu dismissal, drag cancelled by its own handler, throwing drop handlers, caret stability, live reload of a focused field and mid-press, binding typos |
| `ActionMapTest`, `CoordinatesTest`, `UiInputGateTest` | bindings and hints, coordinate spaces and inversion, the gate |
| `InputReplayTest` | headless replay (`ui/input/replay/settings.replay`) through the game path and the preview path at UI scale 1.25: identical traces, equal to the committed `settings.trace` (regenerate with `-Dui.replay.write=true`) |
| `GameUiInputTest`, `ChatTextRulesTest`, `SettingsPersistenceTest` (game) | document stacking, captured cursor, cancellation, settings, the game's gate; chat parity; persisted preferences |
