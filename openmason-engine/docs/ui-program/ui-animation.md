# UI animation: transitions, timelines, tweens and state machines (#295)

One sampler animates Stonebreak UI documents. Sheet transitions, timeline clips, script tweens, graph
nodes and state machines all write through it. Presentation never waits on a script: Lua and graphs request
and configure animations and receive completions, but sampling runs in the host's frame.

| Part | Where |
| --- | --- |
| Clip format | `format/omui/UiAnimationClip` (`animations/<id>.anim.json`, wire contract §5.5) |
| State machine format | `format/omui/UiStateMachine`, `io/StateMachineCodec` (`animations/<id>.states.json`, §5.7, feature `ui-states`) |
| Sampler | `ui/runtime/anim/UiAnimator` (+ package-private `Channel`, `Playback`, `ClipPlayback`, `TweenPlayback`, `Transition`) |
| Property types | `ui/runtime/anim/AnimProperty` |
| Time sources | `ui/runtime/anim/UiClocks` |
| State machines | `ui/runtime/anim/UiStateMachines` |
| Transforms | `UiDocumentInstance.place` (bounds), `UiPainter` (canvas), `HitTester`, `UiCoordinates` |
| Lua / graphs | `ui/script/ScriptOps`, `prelude.lua`, `UiApiCatalog`; `ui/graph/ElementKinds` (`ui:anim.*`) |
| Game host | `com.stonebreak.ui.runtime.GameUiDocuments.frame(view, dt, gameRunning)` |
| Editor | `openmason-tool` `systems/uiEditor/timeline/` (`ClipEdits`, `TimelineSession`, `TimelineScrubber`, `TimelineViewState`), `command/AnimationCommands`, `view/{TimelinePanel,TimelineKeyFields,StateMachineSection}` |

## 1. Ownership and the frame

`UiDocumentInstance` owns the sampler (`animator()`), the clocks (`clocks()`) and the state machines
(`stateMachines()`). Nothing animates unless a host advances it:

1. The host advances `game` and external clocks it owns (`clocks().advance("game", dt)`,
   `clocks().set("battle", t)`).
2. `UiDocumentView.frame(dt)` → `instance.advanceClock(dt)`: the UI clock advances, interaction state
   machines poll their element, then `animator.sample()` samples every running animation once.
3. View extensions run (Lua `update(dt)`; completions and clip events queued during sampling are delivered).
4. `render` resolves styles. Style transitions start here, when the cascade notices a change.

The game's helper `GameUiDocuments.frame(view, dt, gameRunning)` does steps 1–2: the UI clock always moves
by the unscaled frame time; the `game` clock only while gameplay runs (`GameUiDocuments.gameRunning()`:
in a world and not paused). The Open Mason design view calls `advanceClock(dt)` too, so transitions and
state machines preview without scripts; Preview mode runs the whole view.

## 2. Time sources

| Clock | Advances | Use |
| --- | --- | --- |
| `ui` | every frame, also while gameplay is paused | transitions (always), menus, HUD chrome |
| `game` | only while gameplay runs | feedback that should freeze with the game |
| external (`battle`, `intro`, …) | `clocks().set(name, seconds)` by the host | deterministic or encounter time, e.g. the Focus battle's C1/C2 clocks; may rewind |

Every animation samples at an **absolute** elapsed time: `elapsed = base + (clock − since) × speed`. The same
clock reading gives the same frame, whatever frame steps led there (`UiClipSamplingTest`,
`UiAnimationVisualFixtureTest.differentlySteppedHostsPaintIdenticalFrames`). Speed changes and seeks rebase
without a jump. Battle rule from the migration ledger still holds: timed-input visuals must be sampled from
the encounter's own clock (an external clock), never interpolated from the render clock.

## 3. Property types

`AnimProperty` gives each channel (`style:<name>` or `prop:<name>`) a logical type:

| Type | Properties | Interpolation |
| --- | --- | --- |
| NUMBER | `scale`, `rotate` (degrees), `flex-grow`, `aspect-ratio`; number props | lerp (binary64) |
| INTEGER | int props | lerp, floored |
| LENGTH | px lengths, `translate-x/y`, `width`, padding…; `N%` | lerp px; `N%`↔`N%` lerps percent (fills); `auto`/mixed units switch |
| OPACITY | `opacity` | lerp |
| COLOR | `color`, `background-color`, `border-color`, `-sb-tint`; colour props | straight-alpha ARGB per byte, rounded |
| VISIBILITY | `visibility` | visible for the whole segment if either end is visible |
| DISCRETE | keywords, `background-image` (sprite frames), `font`, `-sb-layer`, text and other props | switches at the end of its segment |

An unset end animates from or to a neutral value: opacity 1, scale 1, other numbers 0, `0`/`0%`, the
transparent version of the other colour, `visible`. Key easing (`linear`, `ease-in`, `ease-out`,
`ease-in-out`, `step`) shapes the segment from a key to the next, with the engine's `format.oma.Easing`
curves, or a custom `cubic-bezier` curve (`UiBezier`: a key's or transition's `bezier`, or the easing string
`"cubic-bezier(x1, y1, x2, y2)"` of a Lua tween) that overrides the name; y may overshoot (back/bounce-like).
`var(--token)` key values resolve against the element when a clip or tween starts.

**Motion** properties (translate, scale, rotate, insets, sizes, margins, `flex-basis/grow`) are what reduced
motion removes (§6). **Relayout** properties (`StyleValues.LAYOUT`) can be animated explicitly, but relayout
every frame; prefer `translate-*`, `scale`, `rotate` and `opacity`.

**Transforms.** `scale` and `rotate` now apply about the element's transform origin (after `translate-*`) to the
element and its subtree. The origin is `transform-origin-x/y` (px or % of the rect) when set, else the pivot of the
sprite the element shows (an `Image`'s `source` or a `background-image` `<sheet>#<name>`, via
`UiDocumentSource.spritePivot`), else the centre. Transforms apply in painting (`canvas.concat`), hit testing (points map through the inverse;
`scale: 0` collapses hits), `UiCoordinates.elementTransform/toLocal` and dirty regions (`paintBounds()`, the
transformed bounding box). Lifted overlays (`-sb-layer`) still turn and scale with their ancestors. The
sprite pivot (`pivotX/Y`, #294) is the default origin for a sprite.

## 4. Precedence per channel

A channel is one (element key, property). Lowest first:

1. **The cascade** (#287): sheet rules (pseudo-states such as `:hover` are ordinary rules here), inline,
   instance overrides, binding values, local (script) writes. This is the element's `baseStyle()`.
2. **Transition layer**: a style transition easing the cascade's own change.
3. **Explicit layer**: the value of the clip, tween, state-machine clip or Timeline scrub that most recently
   claimed the channel.

The element shows (`computedStyle()`) the explicit value if any, else the transition's, else the cascade's.
The animation layers overlay the resolved cascade (`ComputedStyle.overlay`), so a running animation costs no
selector matching per frame; inherited properties (`color`, `visibility`, …) of children follow the shown
value.

| Rule | Behaviour |
| --- | --- |
| Interruption | A new clip or tween takes over exactly the channels it animates; the older one keeps the rest. An animation left with no channel is interrupted (`finished(stopped = true)`). |
| Blending | `blend` seconds (clips, state transitions) cross-fade each channel from what it showed when the animation started (ease-in-out); tweens always start from what is shown (or `from`). |
| Retrigger | Playing a clip again restarts it (interrupting the old playback); `restart = false` returns the running playback of the same clip by the same owner instead. |
| Cancellation | `stop(h, "hold")` freezes on the shown value (default), `"end"` jumps to the final values and holds (a loop holds its current frame), `"release"` hands back. |
| Final value | `fill = "hold"` (default) keeps showing the last values until released; `"release"` hands back at the end. |
| Reset / release | `release(el, prop)` and fill/stop release hand the channel back **through the property's declared transition**, if any; otherwise the cascade shows at once. |
| Bindings | The binding keeps updating the cascade underneath any animation; after release its **latest** value shows (`UiAnimationPrecedenceTest`). |
| Lifecycle | Closing the instance stops everything; a script reload or close stops only that script's animations (owner = its context) and leaves held values; transitions outlive script reloads. An element removed (or dropped by a reload) takes its channels with it (`UiAnimator.forget`): held values and transitions go, and a clip or tween left animating nothing is interrupted, so faded-out chat lines and list rows never accumulate channels and a reused key starts clean. A recycled virtualized list row (#325) does the same for its whole subtree while its elements stay: it shows its cascade at once, the new item's cascade change settles without a transition, and state machines of component instances inside restart. Component instances have their own element keys, so their animations never collide. |
| Events | A clip event at clip time `t` occurs at `t` (once), `t + k·d` (loop) or `t + 2k·d` and `2d − t + 2k·d` (ping-pong: once per forward and once per backward pass). Each sample fires every occurrence crossed since the previous one, in time order, so a frame step longer than the clip fires each crossed event (at most 64 per event per sample). A one-shot that finishes fires events at or past its end; a clock that went backwards fires nothing; seeks skip what lies between. |

## 5. Style transitions

Declared per rule in sheets (`transitions[]`: property or `all`, duration, easing, delay; #287). When a
resolve changes an element's cascade value of a property and the **new** style declares a transition for it,
a transition starts on the UI clock from the value the transition layer showed (the running transition's
value, else the old cascade value) to the new one. The first resolve of an element never transitions.
Anything that changes the cascade triggers one: pseudo-states from input (`:hover` with no script), class
toggles, inline writes from scripts, binding updates, reloads of the sheet.

- A change to the same target as a running transition keeps it; a new target restarts from the current value.
- `all` covers every interpolating property except relayout ones (they change at once).
- A change to a property with no declared transition cancels a running one.
- Delays hold the old value; a removed declaration transitions to the neutral value and then clears.

## 6. Reduced motion

Decided when an animation starts (`UiPreferences.reducedMotion`, #288):

| Source | Under reduced motion |
| --- | --- |
| Transitions and tweens | motion properties jump; others keep a fade of at most `UiAnimator.REDUCED_FADE_SECONDS` (0.15 s) with no delay |
| One-shot clips | jump to their end (events still fire; completion is delivered) |
| Looping clips | hold their first frame |
| `ui.play(clip, {reduced = "alt"})` | plays the declared alternate clip instead |
| State-machine transitions | play their `reduced` clip; without one, go straight to the state |
| Sheets | the root element gets the class `sb-reduced-motion`, so authors can write alternates (`.sb-reduced-motion .panel { … }`) |

## 7. State machines

`animations/<id>.states.json` (wire contract §5.7). One machine runs per authoring scope: the screen and each
component instance (`UiDocumentInstance.authoringScopes()`), so every button instance has its own.

- **Interaction**: each frame the `element`'s state is the first declared of disabled (not enabled in
  hierarchy), pressed (`:active`), hover, focused, normal. Hover/pressed/disabled looks need no script.
- **Manual**: `ui.setState(machine, state)` (Lua), `ui:anim.set-state` (graphs), `stateMachines().set(...)`.
- Entering a state plays the transition's clip (exact `from → to` beats `* → to`) with its `blend`, then the
  state's own clip. Channels the new clips do not pose go back to the cascade (`UiAnimator.releaseHeld`). The
  initial state's clip poses at once, without a transition. A state with no clip is the cascade.
- They choose which clip plays; they never replace gameplay state machines.
- **Reload**: a machine whose definition did not change keeps its state but reads clips from the new revision; if
  the clip of its current state changed (a Timeline edit), that clip restarts. A machine whose definition changed,
  or that disappeared, hands everything it held back to the cascade (`UiAnimator.clearAndRelease`) before the new
  one starts.

## 8. Lua and graphs

`uiApi` 1, additive (no version bump: nothing shipped against it yet):

| Call | Meaning |
| --- | --- |
| `ui.tween(el, props, dur, easing, {delay, clock, fill, from})` | props are style names or `prop:<name>`; unknown or unfit values are errors |
| `ui.play(clip, {speed, loop, on_event, clock, blend, fill, at, restart, reduced})` | handle completes at the end (one-shot) |
| `ui.stop(handle or clipId, "hold"/"end"/"release")` | by clip id: this script context's playbacks of that clip |
| `ui.seek(target, seconds)`, `ui.speed(target, rate)` | configure a running animation (0 pauses); seeks skip events |
| `ui.release(el, prop)` | back to the cascade, through its transition |
| `ui.setState(machine, state)` → handle, `ui.machineState(machine)` | state machines of this script's scope |
| `ui.clock(name)` | seconds on `ui` (default), `game` or a host clock |

Animation tokens live above 2^40 so they never collide with action and timer tokens in a script's handle
table (this was a latent #292 collision). Completions and clip events of a closed or reloaded screen are
dropped (generation check); a reloaded script's tasks never resume from an old playback
(`ScriptAnimationTest`).

Graph kinds (all compile to the calls above): `ui:anim.tween`, `ui:anim.play` (new optional `clock`, `fill`
props and `blend` port; omitted at defaults so older graphs compile to the same Lua), `ui:anim.stop`,
`ui:anim.set-speed`, `ui:anim.seek`, `ui:anim.set-state` (optional wait), `ui:anim.machine-state` (pure),
`ui:anim.release`. Prop kinds `STATE_MACHINE` and `MACHINE_STATE` validate and offer pickers.

## 9. Timeline (Open Mason)

The UI Editor's **Timeline** tab (bottom dock) authors the document's clips and state machines.

- Clip bar: pick / New / Rename (state machines and `ui:anim.*` graph nodes follow; Lua is never rewritten) /
  Delete (refused while a state machine plays the clip); duration (cannot cut off keys), loop mode.
- Transport: to start, play/pause, loop preview, time readout, snap (off/24/30/60 fps; Alt bypasses).
- Tracks: one row per (element, property); clicking a label selects the element and frames it (target
  navigation; missing targets show `?`). Diamonds: click/Ctrl+click select, drag on empty track area box-selects
  (Ctrl adds), drag a key to move the selection (one undo step, refuses to land on another key), double-click keys the value the track shows there; ruler click/drag
  scrubs; Ctrl+wheel zooms at the mouse, Shift+wheel pans; events row (double-click adds, drag moves,
  right-click deletes).
- Keys: Delete, Ctrl+C / Ctrl+V (pasted at the playhead into the selected track; keys of several properties
  go to the same element's tracks), K keys the selected track, Space plays. While the Timeline has focus these
  keys never reach the element shortcuts.
- Side bar: the selected key's time, typed value field and easing or custom curve (`CurveField`: four numbers
  and a drawn preview; easing for a multi-selection); the
  state machines with their states, clips, transitions (blend, reduced alternate) and preview buttons.
  Interaction machines preview through the forced pseudo-states of the Details panel.
- Add track: the selected element's animatable style properties and widget properties.
- **Preview safety**: scrubbing and playing pose only the design runtime (`TimelineScrubber`): no command, no
  dirty flag, clip events go to `Listener.NONE` (no script or host action can run), and leaving the Timeline
  (tab hidden, workspace switched, Preview mode) releases every posed channel.
- **Undo**: every edit is an `AnimationCommands` command (drags and typing merge into one step).
- **Display metadata** (open clip, per-clip zoom and scroll, snap) is stamped into `editor/timeline.json` at
  save, apart from the clips; clips' bytes never change because of the view.
- Style Sheets panel: every rule card lists its transitions (property, duration, delay, easing or curve) with
  "+ transition" (`DocumentCommands.setRuleTransition`).
- Dev hook: `-Dopenmason.uieditor.timeline=<clip>[@seconds]` (with `-Dopenmason.uieditor=<omp>,<doc>`).

## 10. Tests

| Test | Covers |
| --- | --- |
| `UiClipSamplingTest` | keys by property type at fixed times, frame-step independence, every easing, loop/ping-pong, events across wraps, speed/seek, invalid tracks, reduced motion, `var()` keys |
| `UiAnimationPrecedenceTest` | binding resumes after hold/release (through a transition), fill release, partial takeover and interruption, blend, stop modes, retrigger, transitions under held values, owner clearing, `prop:` tracks |
| `UiStyleTransitionTest` | `:hover` with no script, reversal mid-way, class/inline triggers, `all` vs layout, delays, neutral ends, reduced motion, `sb-reduced-motion` alternates |
| `UiTimeSourcesTest` | paused game vs UI clips, external clocks incl. rewind, refused clocks |
| `UiStateMachineTest` | interaction machines per component instance, precedence, release to cascade, manual transition then state, arrival, reduced alternate, close |
| `UiAnimationLifecycleTest` | channels released on remove and reload (no accumulation, reused keys start clean), ping-pong and long-step event crossing, backwards clocks, reduced-motion loops, state machines following edited clips and releasing on removal, colour hex formatting |
| `UiTransformTest` | scale/rotate bounds, hits, overlays, local coordinates, scale 0 |
| `UiAnimationVisualFixtureTest` | fixed-timestamp screenshots (`anim_t000/025/050/100.png`, `-Dui.visual.write=true`) and identical pixels for differently stepped hosts |
| `UiStateMachineFormatTest` | round trip, `UNDECLARED_FEATURE`, broken machines, clip value checks |
| `ScriptAnimationTest` | Lua start/configure/cancel, tween options, `setState` awaited, the same behaviour as a graph, completions after reload/close, transitions surviving script reloads |
| `UiBezierFormatTest`, `UiCurveAndOriginTest` | curve maths vs CSS, round trip with a named fallback, validation; curves in keys, transitions and tweens; transform origin and sprite pivots |
| `TimelineEditingTest` (tool) | validated key edits, one undo step per drag, copy/paste, key-the-shown-value, curves surviving moves and pastes, transition editing, scrubbing never edits, rename carrying references, save/reopen/export keeping targets, easing and the view |

## 11. Not done (owned elsewhere)

- **#301**: wiring the battle C1/C2 and intro clocks to external UI clocks; stacked/additive animation layers
  per channel (today last-writer-wins) for HUD shakes over slides.
- **#302**: a state-machine graph view, a value/curve graph editor with in-place bezier handles, a multi-clip
  view, and Python/MCP automation routed through `AnimationCommands`.
- Legacy screens keep their own animators until they migrate (#297 onward).
