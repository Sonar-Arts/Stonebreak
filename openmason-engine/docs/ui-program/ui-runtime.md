# UI runtime: element tree, cascade, layout and painting (#287)

The runtime turns an OMUI/SBUI document ([wire contract](omui-sbui-wire-contract.md)) into a live, styled,
laid-out and painted element tree.

| Part | Where |
| --- | --- |
| Java reference | `openmason-engine/src/main/java/com/openmason/engine/ui/runtime`, plus `style/`, `widget/`, `layout/`, `paint/` |
| Native layout host | `cenda/native/kernels/{include/cenda/flex.h,src/flex_host.cpp}` |
| Game host | `com.stonebreak.ui.runtime.GameUiDocuments` |
| Editor host | `openmason-tool` `systems/uiPreview/UiDocumentPreviewPanel` |

## 1. Three layers

| Layer | Type | Lifetime | Written by |
| --- | --- | --- | --- |
| Definition | `OmuiArchive` / `UiNode` (format) | immutable, shared by every instance | the editor, through the format |
| Instance | `UiDocumentInstance` / `UiElement` | one per open screen; `close()` frees the native tree | runtime, scripts, bindings, animation |
| Editor session | tool state (selection, zoom, fixtures) | per tool session | the tool, never SBUI |

A per-instance change (a class toggle, a local style or prop, a state, a scroll offset, an inserted element) lands
on the instance. Nothing in the runtime writes a definition. Two instances of one document, or two instances of one
component, share their definition nodes and nothing else.

## 2. Identity and references

`UiElement.key()` is built from stable node ids only:
- the node id, for the instantiated document's own nodes;
- `instanceKey/nodeId` for nodes authored inside a component, recursively (`quit/label`, `x/inner/frame`). This is
  exactly the override-target syntax.

Slot content keeps the key of the document that authored it.

**Reference rule** (`UiReferences`):

- **Identity references use keys.** These are:
  - clip tracks (`target`)
  - graph nodes (`props.target`)
  - instance overrides and slot hosts
  - binding owners
  - script lookups by key (`ui.get(...)`, [ui-scripting.md](ui-scripting.md))

  Renaming or reparenting a node never breaks them. `UiReferences.unresolved(instance)` lists any that do not resolve.
- **`#name` is a style handle.** Selectors and `ui.q("#name")` match it, so a rename changes what they select.
  - `UiReferences.renameImpact(doc, nodeId, newName)` returns the sheet rules it can rewrite (`#old` → `#new`), for
    the editor to apply.
  - It also returns every `"#old"` string in Lua as `script:line`. Lua is never rewritten (#285), so the author fixes
    those.

## 3. Widget descriptors

`WidgetRegistry` holds a `WidgetDescriptor` per type: version, typed `PropertyDescriptor`s in inspector order,
`acceptsChildren` and `measured`.

The built-ins (`BuiltInWidgets`) are the format's set:
- `Box`
- `Label(text)`
- `Button`
- `Image(source)`
- `ItemSlot(provider, slot)`
- `DrawProvider(provider)`
- `ScrollView(vertical = true, horizontal = false)`, which needs the `ui-scroll` feature
- `ListView(items, itemKey, itemHeight = 0, selectionMode = single)`, which needs the `ui-data` feature; its single
  child is a row template that is never built directly ([ui-data-binding.md](ui-data-binding.md) §5)
- `Canvas(capacity = 32768)`, which needs the `ui-canvas` feature; its content is the draw-command buffer its Lua
  code-behind fills each frame ([ui-scripting.md](ui-scripting.md) §8)
- `Instance`

Their versions must equal the format's `UiWidgets` table. Host types (`stonebreak:CrucibleView`) register their own
descriptors.

Validation reports problems and never coerces values:

| Problem | Code | Result |
| --- | --- | --- |
| unknown type | `UNKNOWN_WIDGET` | a placeholder container, so the screen still lays out |
| `typeVersion` newer than the descriptor | `UNSUPPORTED_WIDGET_VERSION` | placeholder |
| unknown property | `UNKNOWN_PROPERTY` (warning) | dropped |
| mistyped property | `PROPERTY_TYPE` | dropped; the descriptor default applies |
| children on a leaf | `CHILDREN_NOT_ALLOWED` | children not built |

## 4. Components

An `Instance` node becomes an **`Instance` element** (Unity's TemplateContainer) whose single child is the
component's root. So `Instance .danger` selects into instances, and `display: none` on the instance collapses the
whole component.

- **Parameters** are validated against the contract (`UNKNOWN_PARAM`, `PARAM_TYPE`) and merged over the defaults.
  - The resulting object is the component subtree's **data source**: `{"target": "prop:text", "path": ".label"}`
    inside the component reads the parameter.
  - Static parameter bindings are applied at build time. Live host data, live parameters (`prop:<param>` bindings
    on the `Instance` node) and converters are the binder's ([ui-data-binding.md](ui-data-binding.md), #289).
- **Overrides** apply after a node's authored values.
  - The instance's own overrides go first, then overrides from enclosing instances (`inner/leaf`), so the outermost
    author wins.
  - An override whose target no longer exists in the component's current revision is reported once, at the instance
    that wrote it (`OVERRIDE_TARGET_MISSING`).
  - Removing an override is "reset to source".
- **Slots**: content goes to the slot's host node, after the host's own children. Unknown slot names and missing
  hosts are reported (`UNKNOWN_SLOT`).
- **Errors never abort a build.** A missing component (`MISSING_COMPONENT`), a non-component document
  (`NOT_A_COMPONENT`) or a recursive one (`RECURSIVE_COMPONENT`) leaves the `Instance` element empty.

## 5. Cascade

`StyleResolver.compute` resolves one element, lowest precedence first:

1. **Sheet rules**, ordered by sheet **rank**, then specificity, then sheet order, then rule order.
   - Ranks: the theme (rank 0) < components nested *n* deep (rank 1000 − *n*) < the instantiated document
     (rank 1000).
   - The rank wins over specificity, so an outer document can restyle a component's internals.
   - A component's sheets are scoped to its subtree, slot content included.
   - Interaction states are ordinary pseudo-class selectors inside this step.
2. **Element layers**: inline `style` → instance overrides → binding values → local (script) writes → animation
   channels.

**Decision:** inline and instance overrides beat `:hover`-style sheet rules (USS/CSS semantics). The #283 table
put pseudo-classes after inline; that reading would make an instance override un-hoverable.

**Selectors**: type, `.class`, `#name`, `:state`, `*`, the descendant and `>` child combinators, matched right to left
with backtracking. Namespaced host types never match a type selector.

**Specificity** is (`#name` count, `.class` + `:state` count, type count).

**Variables**: custom properties inherit. At each element they resolve as the parent's customs, overlaid with the
`variables` of sheets attached at that element, overlaid with the element's own `--name` declarations.

- An unresolved or cyclic `var()` is reported (`UNRESOLVED_VARIABLE`, `VARIABLE_CYCLE`), and the property is dropped.
- So is a value that does not fit its property (`STYLE_VALUE`).

**Inherited properties**: `color`, `font`, `font-size`, `text-align`, `visibility`.

`ComputedStyle.transition(property)` carries the winning rule's transition for #295.

**Ownership:** a target with a `to-target` or `once` binding is owned by it. A local write to it is reported
(`BOUND_PROPERTY_WRITE`) and ignored. On a `two-way` or `to-source` target the local write is an edit the binder
stages into the draft ([ui-data-binding.md](ui-data-binding.md) §3). Setting an undeclared custom state is reported (`UNKNOWN_STATE`).

## 6. Layout

**Engine:** Yoga v3.2.1 in the Cenda library, mandatory (`CendaFlex` throws `CendaFlexUnavailableException`; no
Java fallback). `flex.h` ABI **2** adds a retained tree:

- `cf_tree_new(point_scale, measure, baseline)`
- `cf_node_new`/`free`/`insert`/`detach`
- `cf_nodes_set_style` (batched)
- `cf_node_mark_dirty`
- `cf_tree_layout`, which returns how many parent-relative rects actually moved
- `cf_nodes_read`

The native side skips identical records, and Yoga compares before it dirties, so a restyle relayouts only what
changed. The stateless `cf_layout` of the #283 spike shares the same 48-float record. `build-kernels.sh` warns on a
`CF_ABI_VERSION` / `CendaFlex.EXPECTED_ABI` mismatch.

**Record** (`FlexRecord`):
- NaN means unset, which resolves to the `flex-1` defaults (column, shrink 0, align-items stretch, align-content
  flex-start, border-box, auto sizes).
- `PCT_MASK` and `AUTO_MASK` mark percentage and `auto` lengths.
- `OVERFLOW` carries `overflow`: `scroll` and `ScrollView` map to Yoga's scroll overflow.

**Units and scales** (`UiMetrics`): documents author logical pixels; layout runs in device pixels.

| Input | Effect |
| --- | --- |
| viewport | the root's available size |
| `uiScale` | multiplies every logical length |
| `pixelRatio` (DPI) | multiplies every logical length, independently of `uiScale` |
| editor zoom | not an input: a view transform after layout (`PreviewMapping`) |

A scale change re-pushes every record. A viewport change only relayouts.

**Pixel grid** (`UiRuntimeContext.pixelGrid`):
- `1` (default, `flex-1`) snaps layout edges, translations and scroll offsets to device pixels, so painting never
  anti-aliases an edge that hit testing treats as sharp.
- `0` keeps fractional geometry. Today's pause menu matches the legacy oracle exactly with the grid off, and to
  ≤ 1 px with it on (1921×1081 × 1.25).
- **Open for #297:** which grid the pause pilot ships with.

**Text**: measured widgets are Yoga leaves sized through `ContentMeasurer`. `paint/MasonryContentMeasurer` is the
shared Masonry implementation; layout and painting use the same instance.

- A `Label` is one line. Its intrinsic box is the text's advance by the font's line height (descent − ascent) at
  `font-size × scale`. The default size is `MStyle.FONT_BUTTON`.
- **Baseline rule:** when the box is taller than a line, the line is centred, and the baseline sits −ascent below
  the line's top. This reproduces the legacy `y + h/2 + k·s` label placement as a rule.
- The Yoga baseline upcall feeds `align-items: baseline`.
- An `Image` measures as its texture size × scale.
- A content change (`setProp("text")`) marks only that leaf dirty.

**Diagnostics** (`LayoutChecks`):
- `CYCLIC_PERCENTAGE`: a percentage size along an axis where the parent is content-sized.
- `CONFLICTING_CONSTRAINTS`: `min > max`, or an absolute element with both insets plus a size.

## 7. Scrolling and overlays

**Scroll containers** are `ScrollView` elements and any element with `overflow: scroll`. Both need the `ui-scroll`
feature in the manifest's `requires`; the format reports `UNDECLARED_FEATURE` otherwise.

- After layout, the runtime computes the content extent: children plus padding and borders. That gives
  `maxScrollX/Y`.
- `scrollTo`/`scrollBy` clamp to it, and `scrollIntoView` reveals an element in its nearest scroll ancestor. A
  content change re-clamps.
- Scrolling is **visual**: it moves `rect()` (where an element paints and is hit), never `layoutRect()`, and never
  reaches Yoga.
- Scroll containers clip their content.
- Wheel, scrollbar dragging and focus-driven scrolling are the input router's ([ui-input.md](ui-input.md) §3–4).

**Overlays**: `-sb-layer: <n>` (a number, default 0) lifts an element and its subtree into layer *n*.
- Higher layers paint after, and are hit-tested before, everything lower. Equal layers keep tree order.
- Overlays keep their layout position but escape ancestor clips, so a popup is not cut off by the scroll view that
  opened it.
- `PaintOrder` is the single ordering both the painter and `HitTester` walk.

**Translation**: `translate-x/y` (logical px) move the element and its subtree in both painting and hit testing,
like a CSS transform. `scale` and `rotate` are not applied yet (#295).

## 8. Changing a running tree

- **Structural edits:**
  - `element.insertChild(index, UiNode)` builds the subtree in the parent's authoring scope. Keys, descriptors,
    component expansion and sheet matching all work as for authored nodes. Duplicate keys and leaf parents throw.
  - `element.remove()` frees the subtree's native nodes and keys. A removed key can be reused.
  - Yoga relayouts what the edit dirtied.
- **Live reload:** `instance.reload(newDocument)`, or `reload()` to pick up changed components and sheets from the
  source, rebuilds the tree against the new revision.
  - Every element whose key survives keeps its instance state: local props, classes and styles, animation channels,
    pseudo-states, enabled, scroll.
  - Authored values, explicit overrides and component sources come from the new revision.
  - `ReloadReport` lists the kept, dropped and added keys.
  - Element objects are replaced, so hold keys, not elements.

## 9. Frame update and invalidation

`UiDocumentInstance.update()` runs in three steps:

1. Dirty styles resolve in pre-order. Children are re-resolved only when inherited values or custom properties
   changed. Class and state changes dirty the subtree.
2. Changed layout properties re-push their records in one batch. Changed content re-measures. Yoga relayouts.
   Structural edits force a rect read, so scroll extents follow.
3. Visual placement: scroll offsets and translations are applied.

Moved rects and paint-only restyles fold into `consumeDirtyRegion()`, clipped by their ancestors' clips. A scroll
dirties only the view's content area.

`UpdateStats` reports the work done:
- An unchanged frame resolves 0 styles and never calls Yoga.
- A paint-only restyle never reaches Yoga.
- A scroll never relayouts.

## 10. Visibility and hits

`HitTester.pick` uses the same rects and `PaintOrder` as painting. Edges are inclusive (today's input).

| State | Takes space | Paints | Hit |
| --- | --- | --- | --- |
| `display: none` (collapsed, subtree) | no | no | no |
| `visibility: hidden` (inherited; a visible descendant still paints and hits) | yes | no | no |
| `picking-mode: ignore` (children stay pickable) | yes | yes | passes through |
| disabled (`setEnabled(false)`, inherited, matches `:disabled`) | yes | yes | yes: blocks what is below, receives no events ([ui-input.md](ui-input.md) §3) |

`overflow: hidden` and scroll containers clip descendants' hits. Overlays escape those clips.

## 11. Painting and hosts

`paint/UiPainter` paints a laid-out instance into an open Masonry frame, in `PaintOrder`.

Per element:
1. `background-color` (rounded by `border-radius`)
2. `background-image`
3. the widget's look
4. borders
5. clipped children
6. a scroll container's scrollbar

`opacity` fades the subtree, and `-sb-tint` multiplies the element's own drawing.

| Widget | Look |
| --- | --- |
| `Button` | the Masonry stone surface: highlight fill on `:hover`/`:active`, disabled fill when disabled, unless a background is styled |
| `Label` | house-style shadowed text on the shared baseline, aligned by `text-align`, colour `color` (default `MStyle.TEXT_PRIMARY`) |
| `Image` | its `source` |
| `ItemSlot` | the Masonry slot frame plus its host provider |
| `DrawProvider` | its host provider |
| `Canvas` | the draw commands its script wrote this frame (`paint/CanvasPainter`), clipped to the element |

Images honour `-sb-image-scale`:
- `stretch`
- `integer` (largest whole scale, centred)
- `tile`
- `nine-slice`, which draws as stretch until slice insets exist

Sampling follows `-sb-sampling`; nearest is the default, for pixel art.

`paint/UiDocumentView` is a document on screen. It wraps the instance, the painter and the document's
`UiInputRouter` (#288, [ui-input.md](ui-input.md)), which owns `:hover`, `:active`, `:focus`, `:focus-visible`,
event dispatch, focus order, text editing and drag and drop. `pointerDown`/`pointerUp`/`pointerMove`/`focus` remain
as primary-button shortcuts; `pointerUp` returns the clicked element (pressed and released on the same enabled
element). `render` reconciles input state with each new layout before painting, and the painter draws carets,
selections, focus rings, status symbols and the tooltip from the router.

**Hosts.** `paint/ResolvedUiAssets` serves components, shared sheets and textures through #285 asset resolution:
- textures decode once per content hash in the shared `MTextureCache`;
- PNG dependencies use `MTexture.fromImage`.

`com.stonebreak.ui.runtime.GameUiDocuments` builds the one runtime context every host uses: built-in widgets,
`ResolvedUiAssets` over the game's packaged root and packs, the game texture cache, and `MasonryContentMeasurer`.

| Host | How it hosts documents |
| --- | --- |
| Game window | `GameUiDocuments.render(view, masonry, w, h, uiScale)` on the game's Skija backend. Screens migrate in #297 onward. Dev overlay: `-Dstonebreak.uidoc=<file.omui\|file.sbui>` draws a document over every game state, with hover and press routed |
| Open Mason | `UiDocumentPreviewPanel` (`-Dopenmason.uidoc.preview=<file>`) on `MasonryPreview`, on the GPU framebuffer or the raster upload path. "Reload" calls `instance.reload` (§8) |

`UiDocumentGlTest` (`-Dstonebreak.ui.gl=true`) renders one document through both targets after hover, focus and
disabled changes: **0 px differ**.

The stone surface's noise speckles are now placed on whole pixels. A non-antialiased speckle on a half-pixel edge
used to round differently on a bottom-left and a top-left target; see masonry-rendering §4.

## 12. Not yet in the runtime

These are outside #287 or tracked elsewhere:
- grid layout (an optional extension)
- `scale`/`rotate` transforms and transition sampling (#295; script tweens and clips already sample through
  `anim/UiAnimator`, [ui-scripting.md](ui-scripting.md) §9)
- nine-slice insets
- multi-line or wrapped labels
- migrating real screens (#297/#298)

Input routing, focus, text editing, localization and accessibility metadata are specified in
[ui-input.md](ui-input.md) (#288). Data bindings, host actions, `ListView` and the activation gate are specified in
[ui-data-binding.md](ui-data-binding.md) (#289). Lua code-behind, the `ui` API, `Canvas` and the animation sampler are
specified in [ui-scripting.md](ui-scripting.md) (#292).

## Tests

| Test | Covers |
| --- | --- |
| `cenda/tests/flex_host_test.cpp` | retained = stateless geometry, no-op restyles, reset to defaults, masks, tree-edit validation, measure dirtying, baseline upcall |
| `CendaFlexTest` | the same through FFM, plus load diagnostics |
| `SelectorMatcherTest` | parsing, specificity, combinators, namespaced types |
| `StyleCascadeTest` | rank/specificity/order, layers, states, variables, inheritance, transitions, ownership, undeclared states |
| `ComponentInstanceTest` | isolation, golden pause expansion, overrides, slots, recursion, source edits, rename/reparent, sheet scope, descriptors |
| `UiLayoutTest` | legacy pause geometry through documents and components (both grids, all cases); wrap/grow/shrink/justify/align/absolute/percent; scale and DPI; narrow and wide windows; render/hit agreement; visibility states; clipping; collapse reflow; invalidation; layout diagnostics |
| `UiDynamicTreeTest` | scroll extents, clamping, visual-only scrolling, `scrollIntoView`, the `ui-scroll` feature, overlays, insert/remove, live reload with state carry-over, identity references after rename/reparent, rename impact |
| `UiPaintTest` | every painted pixel equals the hit element's colour at 1×, 1.5× and 2× (overlays, scroll, clip, translate); label width = advance; glyphs inside their rect; baseline alignment; pseudo-state visuals; two hosts give identical pixels; click semantics; textures, tint, scale modes |
| `UiVisualFixtureTest` | committed PNGs (`src/test/resources/ui/runtime/visual/`): a menu at 1× and 1.5×, and text metrics; regenerate with `-Dui.visual.write=true` |
| `UiDocumentGlTest` (game, GL-gated) | game window vs preview FBO, 0 px, across state changes |
