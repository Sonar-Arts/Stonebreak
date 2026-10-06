# Shared Masonry rendering (#286)

Masonry, Stonebreak's Skia widget library, is now engine code, and one widget drawing path serves:
- the game window,
- an offscreen editor preview framebuffer,
- the CPU raster reference path.

The engine never imports `com.stonebreak` or tool classes. Host-specific pieces are injected.

| Package | Contents |
| --- | --- |
| `com.openmason.engine.ui.masonry` | widgets (`MButton`, `MDropdown`, ...), `MPainter`, `MStyle`, `MFonts`, `MasonryUI`, `MasonryEnvironment`, `MKeys`, `MClipboard` |
| `com.openmason.engine.ui.masonry.textures` | `MTexture` (SBT/OMT/PNG → one image composited with the engine's `OmtCompositor`, straight alpha; owns cached sprite sub-images, #294), `MTextureCache` (shared owner) |
| `com.openmason.engine.ui.rendering` | `UiRenderTarget`, `GlStatePolicy`, `GlStateSnapshot`, `GlBaseline`, `SkiaGlRenderer`, `MasonryBackend`, `GpuMasonryBackend`, `RasterMasonryBackend`, `OffscreenFramebuffer`, `RasterTextureUpload`, `PreviewMapping` |
| `com.openmason.engine.util.easing` | `EasingFunctions`, `EasingType` (moved from the game's intro tweens) |

These game classes stay as facades, so the 136 game files that import Masonry kept their behaviour:
- `SkijaUIBackend` is now a `GpuMasonryBackend` that adds the menu assets.
- `MTextureRegistry` reads game classpath SBTs and SBO item icons into the engine cache.

Tests moved with the code: 332 widget tests now run in the engine (`ui-program` system) with a pinned font,
`openmason-engine/src/test/resources/fonts/Minecraft.ttf`.

## 1. Host services

| Seam | Game | Tool / headless default |
| --- | --- | --- |
| UI scale `MasonryEnvironment.installUiScale` | `Settings.getInstance()` binds itself on creation | 1, or the preview panel's slider |
| Clipboard `MasonryEnvironment.installClipboard` | `GlfwClipboard` (game window), installed by `Renderer` | process-local |
| Key codes `MKeys` | GLFW values, passed through unchanged | same |
| Resources `MTexture.loadFromResource(path, opener)` | `MTextureRegistry` passes `Class::getResourceAsStream` (JPMS: only the owning module can read its resources) | `GameUiResources` exported from `com.stonebreak.ui.runtime` |
| Typeface `MasonryBackend.typeface()` | game font, owned by `SkijaUIBackend` | `GameUiResources.loadTypeface()` (caller-owned) |

## 2. Render target contract (`UiRenderTarget`)

- **Units.** Canvas units are framebuffer pixels: (0, 0) is the top-left and `width x height` is the target size.
  Masonry applies its own UI scale. `pixelRatio` is informational, for hosts that map pointer input.
- **Color.** RGBA8, sRGB, **premultiplied** alpha.
- **Origin.** The origin describes how rows are stored:
  - `BOTTOM_LEFT` is the default framebuffer, and a sampler must flip V.
  - `TOP_LEFT` stores rows top-down, exactly like the raster path. Previews therefore use UVs
    `(0,0)`–`(uvMax)` on both paths.
- **Ownership.** A target describes a framebuffer; it does not own it. Whoever creates the framebuffer deletes it,
  after calling the renderer's `releaseTargetSurface()`.
- **Constructors.**
  - `gameWindow(w, h, ratio)`: framebuffer 0, `BOTTOM_LEFT`, `RESET_TO_BASELINE`.
  - `offscreen(fbo, w, h, ratio)`: `TOP_LEFT`, `RESTORE`.

## 3. Frames, flush and GL state (`SkiaGlRenderer`)

Each frame runs in this order:
1. `begin(target)`:
   - captures GL state under the `RESTORE` policy,
   - rebuilds the Skia surface only if the framebuffer, size or origin changed,
   - calls `resetAll()`,
   - returns the canvas.
2. The draws run. The host clears first; the renderer never clears.
3. Widgets register overlays (open dropdowns, tooltips). `MasonryUI.renderOverlays()` draws them after the other
   widgets, inside the same clip stack.
4. `end()` calls `flushAndSubmit` and `resetAll()`, then applies the target's policy:
   - `RESTORE` puts back everything `GlStateSnapshot` holds:
     - program, VAO and buffers,
     - draw and read framebuffers,
     - viewport and scissor,
     - capabilities,
     - blend, depth and stencil configuration,
     - write masks and front face,
     - the 2D texture and sampler of units 0–15,
     - pixel-store state and pixel-pack/unpack buffers.

     It never binds framebuffer 0 unless the caller had it bound. Use this policy when ImGui or a viewport pass
     owns the frame.
   - `RESET_TO_BASELINE` is the historical game behaviour that NanoVG and the world pass after the UI rely on
     (`GlBaseline`). It binds the target's framebuffer and clears samplers and 2D bindings on 16 units (the
     terrain-smear fix), with blend `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` and depth, scissor, stencil and cull off.
- **Nesting.** `beginFrame`/`endFrame` nest; only the outermost pair opens and flushes. A resize applies only at
  the outermost frame, and a non-positive size (a minimized window) keeps the last size.
- **Exceptions.** Hosts call `endFrame()` in `finally`, so state is restored even when a widget throws. `begin`
  throws before touching GL when uninitialized or already painting.
- **Skia context lifetime.** Skia changes GL state while it creates and destroys its context: in testing it turned
  off a caller's depth test. `init()`, `releaseSurface()` and `close()` are therefore bracketed by a snapshot and
  restore.
- **Context loss.** When a GL context is destroyed, call `abandonContext()` first (Skia must not free through a
  dead context), then `initialize(target)` on the new context.
- **Threading.** Everything runs on the thread that owns the GL context. `MTextureCache` lookups are thread-safe.

## 4. Resource lifetime

- **Typeface.** One per backend, shared by every screen. The backend that loaded it closes it on `dispose()`.
- **Fonts.** `MFonts` is per `MasonryUI` (per screen). `MasonryUI.dispose()` closes only that screen's `Font`
  objects, never the typeface.
- **Textures.** `MTextureCache` is the single owner: one decode per key, failures remembered and not retried per
  frame, `forget(key)` for reloads. Screens borrow textures and never close them. `disposeAll()` runs on the
  render thread at shutdown.
- **Previews.** `OffscreenFramebuffer` and `RasterTextureUpload` allocate in 32 px steps, so dragging a dock
  splitter does not reallocate every frame. `close()` deletes every GL name; the GL test asserts
  `glIsFramebuffer`/`glIsTexture` are false afterwards.
- **Stone noise.** `MPainter.stoneSurface` speckles are placed on whole pixels (#287). A non-antialiased rect on a
  half-pixel edge rounds one way on a bottom-left target (the game window) and the other way on a top-left one (the
  preview framebuffer). Before the fix, a document's buttons differed by 16 px between the two targets.
  `UiDocumentGlTest` now checks 0 px.
- **SBT/OMT semantics.** These are unchanged: visible layers are composited bottom-up at their opacity, and nearest
  sampling is kept for pixel art. Use integer asset scales, or pixel-snapped destination rects, so the output does
  not depend on the target. At non-integer scales, framebuffers of different heights round texel-boundary ties
  differently (#283).

## 5. Editor preview (`openmason-tool` `systems/uiPreview`)

`MasonryPreview` paints a frame and shows it as an ImGui image with an invisible button over it. It has two paths:
- **GPU**: `GpuMasonryBackend` draws into an `OffscreenFramebuffer`. The output is pixel-identical to the game
  window, and state is restored around the paint.
- **Raster** (the panel default): `RasterMasonryBackend` plus `RasterTextureUpload` via `glTexSubImage2D`.
  Use it when the window may be popped out: Mesa/XWayland flickers FBO-rendered textures sampled from a shared
  context, but not uploaded ones.

The painter fills an opaque backdrop first, so ImGui's straight-alpha blend shows the premultiplied frame
unchanged. A transparent preview would need `(ONE, ONE_MINUS_SRC_ALPHA)`; #283 measured the default blend
darkening translucent edges by up to 40 levels.

**Input.** Input is converted separately from drawing: `PreviewMapping.canvasX/Y = (pointer − imageOrigin) /
zoom`, with no Y inversion. `MasonryPreviewPanel` (`-Dopenmason.masonry.preview=true`) is the developer proof
surface. It has a path switch, zoom, UI scale, hover routed through the mapping, and the game font and SBT
texture. The UI Editor (#293) will host real documents with the same component.

## 6. Evidence

| Check | Result |
| --- | --- |
| Raster gallery, pre- vs post-extraction (10 widgets, a tooltip at 1.5× UI scale, an SBT texture; 320×240) | **0 of 76,800 pixels differ** |
| `MasonryRenderTargetGlTest` (game tests, `-Dstonebreak.ui.gl=true`, NVIDIA, GL 3.3 core): game window vs preview FBO | **0 px** differ |
| Same test: raster reference vs GPU | 3,282 of 40,960 px differ by ≥ 1 level (anti-aliased edges and glyphs), **12 px** by > 24 levels |
| Orientation, premultiplied alpha, clip, overlay order | pass |
| `RESTORE` policy: caller's framebuffer, program, viewport, scissor, samplers, textures, blend, pixel-store | unchanged |
| `RESET_TO_BASELINE` | framebuffer 0, samplers cleared, unit 0 active, depth off, blend on |
| 7 preview sizes × 3 rounds of resize, then close | no GL errors, every framebuffer and texture deleted |
| Two screens on one backend, close one | the other still renders text and textures |
| GL context destroyed and recreated | same frame, 0 px differ |
| In-game HUD (autoworld screenshot, same seed) before and after | identical hearts, item icons, slots and text |

## 7. Not done here

- Moving the remaining game screens onto documents (#287 onward). Their Masonry calls now go through the engine.
- A GPU-path toggle for the game: the game keeps `RESET_TO_BASELINE`. Switching it to `RESTORE` should wait for a
  capture comparison of every screen.
- AMD/Intel driver coverage and the multi-viewport pop-out on Mesa (#283 handed these over). The raster path
  exists for that case, but it has not been verified on those drivers here.
