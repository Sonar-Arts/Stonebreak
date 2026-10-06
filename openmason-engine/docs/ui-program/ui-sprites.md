# UI textures, sprite sheets and nine-slice (#294)

Texture Editor files skin UI: a texture is painted once (OMT/SBT, with its layers) and shared by every
document that lists it; a **sprite sheet** adds named regions and their UI metadata without duplicating any
pixels or the pixel/layer editor.

| Part | Where |
| --- | --- |
| Wire shape | `format/omui/UiSpriteSheet`, `UiSpriteRef`, `UiSpriteSheets` (geometry checks), `io/SpriteSheetCodec` |
| Resolution and export | `ui/assets/SpriteBinding`, `TextureSizes`, `export/SpriteChecks` |
| Runtime | `ui/runtime/paint/UiImage`, `SpriteSlices` (geometry), `SpriteFrames` (timing), `SpritePainter`, `ResolvedUiAssets.image` |
| Editor | `openmason-tool` `systems/uiEditor/service/{UiImageAssets,SpriteSheetDraft,TextureEditBridge}`, `view/{SpritesPanel,ImagePicker,SheetTexture}` |

## 1. Sheets on the wire

A sheet is a dependency of kind `sprites` (`<namespace>/<path>.sprites.json` by convention), canonical JSON
like every other UI entry (§3 of the [wire contract](omui-sbui-wire-contract.md)): schema field order,
defaults omitted, unknown fields preserved. Sprites and skins are written in name order.

| Field | Default | Notes |
| --- | --- | --- |
| `format` | required | `"omui-sprites"` |
| `version` | required | `1`; a newer version is refused (`UNSUPPORTED_SCHEMA_VERSION`) |
| `texture` | required | the texture id the regions were authored against (see §2 for what binds at runtime) |
| `width`, `height` | required | the texture size the regions were authored against, 1–16384 |
| `sprites[]` | `[]` | below |
| `skins[]` | `[]` | below |

**Sprite** (names are local ids, unique across sprites and skins):

| Field | Default | Meaning |
| --- | --- | --- |
| `name`, `x`, `y`, `w`, `h` | required | the region in texels; `w`, `h` ≥ 1 |
| `logicalWidth`, `logicalHeight` | 0 = `w`/`h` | intrinsic layout size in logical px (an `Image` measures as this × scale) |
| `pivotX`, `pivotY` | 0.5 | normalized anchor; places `integer`-mode drawings, reserved for transforms (#295) |
| `slice{left,top,right,bottom}` | none | nine-slice insets in texels |
| `edges` | `stretch` | `stretch` or `tile` (along the edge, at the corner scale) |
| `center` | `stretch` | `stretch`, `tile` or `hidden` (a frame) |
| `scale` | auto | `stretch`, `nine-slice`, `tile`, `integer`; auto = nine-slice when sliced, else stretch |
| `sampling` | inherit | `nearest` or `linear`; absent = the element's `-sb-sampling` |
| `tint` | white | `#RRGGBB[AA]`, multiplied into the sprite |
| `opacity` | 1 | multiplied into the sprite's alpha |
| `frames[]` | `[]` | `{x, y, duration}` regions the size of the sprite, in play order; durations in (0, 3600] s |
| `loop` | `loop` | `once` (holds the last frame), `loop`, `ping-pong` (0 1 2 1 0 …) |

**Skin**: `name`, `normal` (required), `hover`, `pressed`, `disabled`, `focused` — sprite names of the same
sheet. A state without a region uses `normal`. Precedence: disabled > pressed (`:active`) > hover >
keyboard focus (`:focus-visible`) > normal — the stone button's own order.

Structural errors (duplicate or invalid names, a skin naming no sprite, a bad colour, `edges: hidden`, a
zero-length frame) refuse the sheet. Conformance fixture: `src/test/resources/ui/omui/stone.sprites.json`
(`UiSpriteSheetTest`, regenerate with `-Dui.fixtures.write=true`).

## 2. References and binding

An element references a region or skin as **`<sheet id>#<name>`** (`UiSpriteRef`) wherever an asset value is
authored: `background-image` (inline, sheet rules, overrides), `Image.source`, a Lua canvas sprite. The sheet
part is a dependency id of kind `sprites`; the name is the sheet's own identity, so repacking a texture never
breaks a document. Using the form needs the **`ui-sprites`** feature in `requires` (editors add it; the writer
refuses it undeclared). The format validator refuses `#` on a non-sheet row and a sheet without a name.
Renaming a sheet (`RelinkOperations.rename`) rewrites the sheet part of every reference.

**Binding (`SpriteBinding`)**: a sheet draws from the one `texture`/`image` row its own row `requires`. Ids
therefore live only in rows, so a rename or an import remap (`-imported`) keeps the sheet bound without
editing sheet bytes, and embedding a sheet snapshots its texture (the `requires` closure). A sheet whose row
requires no texture falls back to its authored `texture` id when the table lists it (with a warning: embedding
would leave the texture behind); requiring several textures, or none and no fallback, is an error.

## 3. Revalidation

`UiSpriteSheets.check(sheet, texW, texH)` compares every region with the texture it actually resolves to:

| Finding | Severity | Runtime effect |
| --- | --- | --- |
| `TEXTURE_SIZE_CHANGED` — the texture is no longer `width`×`height` | warning | none by itself |
| `SPRITE_REGION_INVALID` — the rect or a frame leaves the texture | error | the sprite is **not drawn** |
| `SPRITE_SLICE_INVALID` — left + right > w or top + bottom > h | error | drawn **stretched**, never with overlapping or negative patches |
| `UNKNOWN_SPRITE` — a reference names nothing in the sheet | error | not drawn |

The editor shows them per sheet (Sprites panel) and per document (Diagnostics), with "Use W×H" to record the
new size once the regions are fixed.

## 4. Painting

`ResolvedUiAssets.image(ref)` returns a `UiImage`: a whole texture, a `Region` or a `Skinned` set; the painter
draws `background-image` and `Image` through `SpritePainter`, the measurer sizes `Image` by the region's
logical size (a skin: its normal region).

**Geometry** (`SpriteSlices`, pure and unit-tested). `k` = device px per texel = logical size × scale ÷ texels.

- **Nine-slice**: corners keep their texels at every size; edges stretch or tile along their length and match
  the corners across; the centre stretches, tiles or is hidden.
- **Pixel-art snapping** (nearest sampling): corner scales round to a whole number of device px per texel
  (≥ 1), so each texel covers the same pixels at fractional DPI (1.25× → 1, 1.5× → 2), and every patch edge
  lands on a whole device pixel (no seams, no overlap). Linear sampling keeps exact geometry.
- **Below the minimum size** (narrower than left + right, shorter than top + bottom): all four corners shrink
  by one common factor until they fit (CSS border-image rule); edges and centre get no space.
- **Integer**: the largest whole multiple of the region that fits (≥ 1), placed by the pivot. **Tile**:
  repeats from the top-left at `k` (snapped with nearest). **Stretch**: fills the rect.

**Precedence**: the element's `-sb-image-scale` beats the sprite's `scale`; the sprite's `sampling` beats the
element's `-sb-sampling` (the sheet author knows whether the art is pixel art). Element `-sb-tint` and
`opacity` still apply on top of the sprite's tint and opacity.

**No bleeding, no gutters**: every source rect is drawn with Skia's strict constraint, and tiled pieces repeat
their own sub-image (`MTexture.region`, cut once and owned by the texture), so neighbouring regions never leak
in under linear filtering. Atlas packing is not built: it would be derived output, and named references would
survive it.

**Animation** follows the document's UI clock (`UiDocumentInstance.clock()`, advanced by
`UiDocumentView.frame(dt)`; the editor's design mode advances it too). The painter records each animated
area and its next frame boundary (`noteAnimation`); `advanceClock` marks only that area dirty when the
boundary passes, so hosts repaint exactly on frame changes. Reduced motion holds the still region.

**Compositing is one rule everywhere**: `MTexture` flattens OMT layers with the engine's `OmtCompositor`
(straight-alpha source-over, layer opacity and visibility, bottom to top — the rule the 3D viewport uses) and
decodes PNG layers without colour management, so the UI preview, the game and every export draw the same
pixels from the same bytes. A layer whose PNG is not canvas-sized is scaled to the canvas (nearest), as the
Skia path before it did; nothing visible gives a transparent canvas-sized texture. `MTexture.decode` and the
headless `TextureSizes` recognise SBT, OMT and PNG through one probe (`format.omt.TextureBytes`).

**Lifetime**: decoded textures are shared by content hash (`MTextureCache`), parsed sheets and their checks
are cached per content hash, and every reference resolves once (`image(ref)` is a map lookup, no per-frame
allocation); a frame never re-reads or re-decodes a source. Resolved bytes are remembered per id.
`ResolvedUiAssets.refresh()` re-resolves them and replaces only what changed, returning the superseded texture
keys; `forget(keys)` drops those from the shared cache without closing them (anything still drawing keeps its
image until the GC collects it). The editor refreshes every open document on a Texture Editor save or sheet
Apply, rebuilds views whose bytes changed (layout re-measures), evicts revisions no open document draws, and
notices edits made outside the editor by polling the modification time and size of the project files a view
resolved, once a second, without reading them.

Dynamic item icons and model thumbnails stay `DrawProvider`s (typed host providers); they are never
flattened into sheets.

## 5. Export

`ExportPlanner` runs `SpriteChecks` before writing anything. **Blocking**: an unreadable sheet, an unbound or
unresolvable texture, a referenced name the sheet lacks, a referenced region outside its texture or whose
slice no longer fits — in the document and in every component the export carries. **Warnings**: a resized
texture, problems in regions nothing references, a sheet bound only through its authored id. Collect-all
carries sheets and their textures like any other rows; SBUI round trips and portable or project imports keep
references, sheets and bindings intact (`SpriteExportTest`).

## 6. Editor

- **Details** (`ImagePicker`): `background-image` and `Image.source` pick from the document's textures and
  every sprite/skin of its sheets (grouped by sheet), or add a project `.omt`/`.sbt`/`.png`/`.sprites.json`
  (a sheet brings its texture row and `requires` it). Buttons: **Edit Texture**, **Edit Sprites**, **Slice
  into Sprites** (creates `UI/<ns>/<texture path>_sprites.sprites.json` for a texture).
- **Sprites panel**: sheet picker, New Sheet, Apply/Revert, local Undo/Redo, Edit Texture; a zoomable texel
  canvas (drag empty texels to draw a region, drag a region to move it with its frames, its edges to resize,
  the dashed slice guides to set insets; wheel zooms, middle/right drag pans); sprite list and skins
  (rename keeps skins pointing at the right region, delete clears skin states); properties for every field of
  §1; a live preview through the runtime's own `SpriteSlices` at any size and scale, frames playing; the
  sheet's findings. Edits collect in a `SpriteSheetDraft`; **Apply** is one document undo step
  (`UiImageAssets.saveSheet`): a shared sheet's project file is rewritten (undo restores it), an embedded one
  replaces its snapshot, and the row records the new hash.
- **Edit Texture** (`TextureEditBridge`): opens the project OMT in the Texture Editor (refused with a reason
  for embedded snapshots, packaged assets and flat PNGs, or when the Texture Editor holds unsaved work). An SBT
  opens through its layered source `<name>.omt` beside it (written from the SBT once, never overwritten); every
  Texture Editor save of that OMT re-wraps the SBT with its own identity and metadata.
- **Propagation**: `TextureCreatorController.addSaveListener` → `UiEditorWorkspace.textureSaved` →
  every open document's runtime invalidates and repaints, so every shared reference updates; embedded
  snapshots stay as they are until **Refresh Snapshot**. UI Assets marks a shared row whose file changed since
  the document recorded it (`changed`) and offers **Accept Current Version** (records the new hash).

## 7. Tests

`UiSpriteSheetTest` (wire shape, pinned fixture, structural errors, resize revalidation, references, feature
gate, rename), `SpriteGeometryTest` (nine-slice coverage, snapping, minimum size, modes, frame timing),
`SpritePaintTest` (corners at 1/1.25/2× and three sizes, below-minimum, preview vs exported game pixel for
pixel, straight-alpha layer compositing, skins, animation + dirty regions + reduced motion, invalid regions,
logical size, invalidate after a save, no re-read/re-decode, sub-image ownership), `SpriteVisualFixtureTest`
(golden PNGs at 1× and 1.25×, `-Dui.visual.write=true`), `SpriteExportTest` (round trip into a fresh project,
blocking findings, binding rules, import remap, embedded snapshots vs shared propagation), tool
`UiImageAssetsTest` (project ids, sheets bring textures, a Texture Editor save repaints two documents, sheet
apply/undo writes the file, new sheets, Edit Texture resolution, SBT re-wrap, draft undo).

## 8. Not yet

- Inline nine-slice handles on the designer canvas (insets are edited in the Sprites panel).
- Atlas packing (optional derived output).
- (done in #295: `rotate`/`scale` turn about the sprite's pivot unless `transform-origin-*` is set.)
- The Texture Editor's own on-screen compositing truncates where `OmtCompositor` rounds (≤ 1 level per
  channel on translucent overlaps); the saved layers are identical, only its live view differs.
