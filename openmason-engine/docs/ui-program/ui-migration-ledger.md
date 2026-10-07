# Stonebreak UI migration ledger (#283)

Pinned to commit 2b4bcc91

Scope: every Stonebreak game screen, HUD element, dialog, tooltip, overlay, screen effect and reusable custom
renderer. Open Mason's own tool UI is out of scope (#282). **Nothing is migrated yet**; the pause menu (#297) ships as a
document with the legacy renderer kept as its rollback.

**Status** (summary table, checked by `UiMigrationLedgerCoverageTest`, #296):
- `not started`: inventoried only.
- `baselined`: committed pixel baselines of the legacy rendering exist. The row's `**Fidelity:**` line names them
  (`<screen>/<case id>` under `stonebreak-game/src/test/resources/ui/fidelity/`), and the test fails if one is missing.
- `in progress`: a document exists behind the per-screen switch; the baselines still hold.
- `migrated`: the legacy path is gone. Also needs a `**Gate:**` line naming the test class that runs
  `ui.fidelity.MigrationGate` for it (document vs legacy capture); the test checks that the class really uses
  `MigrationGate`. See [ui-fidelity.md](ui-fidelity.md).
- `blocked`: cannot proceed until something else lands. Needs a `**Blocked:**` line naming what (an issue `#nnn` or a
  missing capability).
- `retained-bridge`: decided to stay host code, drawn into documents through a host draw provider (or kept outside
  documents). Needs a `**Decision:**` line saying why and how it is bridged.
- `n/a`: not a legacy surface.

The coverage guard counts a class only when the summary table or a `### row` names it: a mention in the cross-cutting
notes, hard visuals or known gaps is not a row.

Paths are relative to `stonebreak-game/src/main/java/com/stonebreak/` unless they start with a module name.
Abbreviations:
- **FR**: `core/render/FrameRenderer.java`
- **MIR**: `input/MenuInputRouter.java`
- **IH**: `input/InputHandler.java`
- **UMR**: `input/UiMouseRouter.java`
- **UTK**: `input/UiToggleKeyHandler.java`
- **GSC**: `core/state/GameStateController.java`
- **GL**: `core/loop/GameLoop.java`
- **GS**: `core/screens/GameScreens.java`
- **OR**: `rendering/UI/components/OverlayRenderer.java`
- **SUB**: `rendering/UI/backend/skija/SkijaUIBackend.java`
- **SC**: `rendering/UI/backend/skija/SkiaContext.java`
- **C1/C2**: the Focus battle clocks, defined in [Cross-cutting facts](#cross-cutting-facts).

## Cross-cutting facts

These apply to every row unless the row says otherwise.

**Frame loop**
- Order per frame (`core/Main.java:170-189`):
  1. `glfwPollEvents`, which fires every input callback
  2. `Game.update()`, the tick
  3. `MIR.pollActiveScreen()`
  4. `FR.renderFrame()`
  5. dev hooks
  6. swap
- `Game` delta time is a `System.nanoTime` difference clamped to 0.1 s (`core/Game.java:274-284`).
- `getTotalTimeElapsed` accumulates that delta in every state, paused or not.

**Render order per state (FR)**
- Each shell state draws exactly one screen (FR:91-131).
- FOCUS_BATTLE goes to `renderFocusBattle` (FR:132, 174-196).
- Every other state uses `renderInGame` (FR:141-167):
  1. world
  2. underwater, dodge and droplet effects (FR:158)
  3. `renderGameUI` (FR:290-314): crosshair, hotbar/inventory/character, chat in `HUD_STATES` (FR:64-66); world-space HUD only in PLAYING (FR:301-303); recipe book; pause menu
  4. `renderFullscreenMenus`: workbench, furnace (FR:408-421)
  5. `OR.renderOverlay`: tooltips, dragged items (FR:162)
  6. `renderModalMenus`: pause menu again plus depth curtain, statistics, glossary, death (FR:424-451)
  7. `FocusBattle.afterFieldFrame`
- `renderDebugOverlay` (F3) runs last in **every** state (FR:136, 453-464).
- PAUSED, STATISTICS and GLOSSARY with a live battle draw the battle frame instead (FR:144-147).

**Backend**
- One shared `SkijaUIBackend` (`rendering/Renderer.java:137`).
- `SkiaContext` wraps the default framebuffer 0 with 8 stencil bits, BOTTOM_LEFT origin and sRGB colour space (SC:47-60).
- Every outermost `beginFrame`/`endFrame` pair does `resetAll` + `flushAndSubmit` + `restoreGLDefaults` (SC:65-135). `restoreGLDefaults` unbinds samplers and 2D textures on 16 units, disables depth/scissor/stencil/cull and enables SRC_ALPHA blending.
- Brackets nest by depth counter (SUB:74-95). `pixelRatio` is ignored and every caller passes 1.0.

**Text**
- One typeface, `/fonts/Minecraft.ttf` (SUB:40), served through per-MasonryUI `MFonts` caches (`rendering/UI/masonryUI/MFonts.java`).
- The font has no em-dash or U+2022 bullet glyph (they render as tofu).
- `MGlyphs` folds some glyphs to ASCII.

**Scaling**
- The only global scale is `Settings.uiScale`, clamped 0.5–2.0 (`config/Settings.java:90,475-476`). Each row says whether it honours it.
- There is no DPI or content-scale handling.
- The cursor is converted from window coordinates to framebuffer pixels (`core/window/GameWindow.java:237-264`). All layout is in framebuffer pixels.

**Input**
- **Keyboard and mouse only. There is no controller or gamepad support**: no `glfwGetGamepad`, `glfwJoystick*` or `GLFW_GAMEPAD` anywhere in game or engine main. (At the pinned commit. Since #288, UI *documents* get controllers, remappable bindings and one routing model through `ui.runtime.GameUiInput`, which MIR consults before every legacy screen; legacy screens are unchanged. See [ui-input.md](ui-input.md) §10.)
- Keys are hard-coded GLFW constants. There is no keybinding system.
- Callbacks route through MIR (`onKey`:39, `onCharacter`:58, `onMouseButton`:83, `onMouseMove`:127, `onScroll`:166). Unconsumed events fall through to IH.
- In-game states are polled through `MIR.pollInGame` (:222) → `IH.handleInput` (IH:73-133).
- STATISTICS and GLOSSARY are **not polled** (MIR:194-220 has no case for them).

**Cursor capture**
- `input/MouseCaptureManager.java:55-89`: the cursor is captured only in PLAYING with chat closed and no death menu. Every other state shows the cursor.

**Sound**
- No UI element plays a click or SFX.
- Exceptions:
  - the intro sonar ping (row `startup-intro`)
  - `MusicManager` scenes: MENU in the menu states, BATTLE while a Focus battle is active (GL:33-42, 87-100)

**Lifecycle**
- GS builds the shell screens once (GS:75-94) and the per-world screens on world entry (GS:100-129).
- `GameShutdown` disposes only pause, statistics, glossary and focus battle (`core/lifecycle/GameShutdown.java:44-53`). Every other screen and HUD MasonryUI/MFonts instance is never disposed. The per-world ones leak on each world load.

**Shared animation library**
- `ui/startupIntro/tween/*` (EasingType, EasingFunctions, TweenEngine) lives inside a screen package.
- It is also used by MainMenuStage, `masonryUI` (MBanner, MGauge, MPipRow, MPromptStrip, MResultCard), focusBattle and the battle camera.

**Item sprites in slots (decided, #330)**
- An SBO item sprite in any slot (hotbar, inventory, workbench, furnace, recipe book, dragged item, document
  `ItemSlot`/`ItemIcon`) is drawn **with its alpha**, from the one shared image `MTextureRegistry.getForSboItem(type, state)`
  (straight alpha, composited by `OmtCompositor`). The slot shows through transparent pixels.
- Until #330 the legacy screens drew it through `ItemIconRenderer`'s own copy flagged `ColorAlphaType.OPAQUE`, so
  transparent pixels came out black. That was a porting accident, not a design: the NanoVG version uploaded the sprite with
  `nvgCreateImageRGBA` (alpha), and the Skia port (3da7c4a8) switched it to OPAQUE. #330 points `ItemIconRenderer` at the
  shared image, so the legacy screens already follow the rule.
- Gates (#298 furnace, #300 inventory/hotbar): SBO sprite rects are compared like any other pixel, legacy vs document, since
  both draw the same image. Do not copy the opaque look. 3D block icons are still GL (Hard visuals #2) and stay out of the
  CPU-raster baselines.
- Pinned by `rendering/UI/menus/ItemSlotSpriteAlphaTest` (legacy path draws the hotbar's image; it is never OPAQUE; every
  transparent pixel shows the backdrop).

**Focus battle clocks**
- **C1** is real battle time: dt clamped to 1/15 s in `FocusBattle.step` (`battle/stage/FocusBattle.java:51,442`). It drives the swirl, camera and `screen.update(dt)` (:446, 458, 463).
- **C2** is simulated time: `sim.update(dt*camera.timeScale())` (:456-457). Slow-motion beats run at 0.4–0.5×.
- The HUD never advances state in `render()` (enforced by `TimedInputLayersTest.paintingNeverAdvancesAnything`).

## Summary table

| id | kind | owner | lifecycle | status |
|---|---|---|---|---|
| startup-intro | screen | `ui.startupIntro.SonarArtsIntroScreen` | shell-persistent | not started |
| main-menu | screen | `ui.MainMenu` | shell-persistent | not started |
| world-select | screen | `ui.worldSelect.WorldSelectScreen` | shell-persistent | not started |
| world-select-info-card | tooltip | `ui.worldSelect.WorldSelectScreen` (card state in `managers.WorldStateManager`) | shell-persistent | not started |
| world-select-delete-confirm | dialog | `ui.worldSelect.WorldSelectScreen` | shell-persistent | not started |
| world-select-create-dialog | dialog | `ui.worldSelect.WorldSelectScreen` | shell-persistent (unreachable) | not started |
| character-creation | screen | `ui.characterCreation.CharacterCreationScreen` | shell-persistent | not started |
| terrain-mapper | screen | `ui.terrainMapper.TerrainMapperScreen` | shell-persistent | not started |
| loading-screen | screen | `ui.LoadingScreen` + `ui.SkijaLoadingScreenRenderer` | shell-persistent | not started |
| settings | screen | `ui.settingsMenu.SettingsMenu` | shell-persistent | not started |
| settings-ui-scale-confirm | dialog | `ui.settingsMenu.SettingsMenu` | shell-persistent | not started |
| settings-dropdown-overlay | overlay | `rendering.UI.masonryUI.MDropdown` via `SettingsMenu` | shell-persistent | not started |
| multiplayer-menu | screen | `ui.multiplayerMenu.MultiplayerMenu` | shell-persistent | not started |
| host-world | screen | `ui.multiplayerMenu.HostWorldScreen` | shell-persistent | not started |
| join-world | screen | `ui.multiplayerMenu.JoinWorldScreen` | shell-persistent | not started |
| pause-menu-offline | dialog | `ui.PauseMenu` | shell-persistent | in progress |
| pause-menu-online | dialog | `ui.PauseMenu` (Resync variant) | shell-persistent | in progress |
| pause-menu-over-battle | dialog | `ui.PauseMenu` drawn by `FR.renderFocusBattle` | shell-persistent | in progress |
| death-menu | dialog | `ui.DeathMenu` | shell-persistent | not started |
| statistics | screen | `ui.statisticsScreen.StatisticsScreen` | shell-persistent | not started |
| glossary | screen | `ui.glossaryScreen.GlossaryScreen` | shell-persistent | not started |
| inventory | screen | `ui.inventoryScreen.InventoryScreen` | per-world | not started |
| inventory-tooltip | tooltip | `ui.inventoryScreen.InventoryScreen` via OR | per-world, per-frame | not started |
| dragged-item-layer | overlay | OR + inventory/workbench/furnace screens | per-frame immediate | in progress |
| workbench | screen | `ui.workbench.WorkbenchScreen` | per-world | not started |
| furnace | screen | `ui.furnace.FurnaceScreen` | per-world | in progress |
| recipe-book | overlay | `ui.recipeScreen.RecipeScreen` | per-world | not started |
| recipe-book-tooltip | tooltip | `ui.recipeScreen.RecipeScreen` via OR | per-world | not started |
| character-sheet | screen | `ui.characterScreen.CharacterScreen` | per-world | not started |
| hotbar | hud | `rendering.UI.components.MHotbarRenderer` + `ui.HotbarScreen` | per-world (via InventoryScreen) | not started |
| hud-health-hearts | hud | `rendering.UI.components.hotbar.HealthHeartsRenderer` | per-frame immediate | not started |
| hud-stamina-mana | hud | `rendering.UI.components.hotbar.StaminaBarRenderer` | per-frame immediate | not started |
| hud-class-gauge | hud | `rendering.UI.components.hotbar.{Rage,Quarry,Resonance,Doubt,Momentum}Gauge` + `GaugePanel` | per-frame immediate | not started |
| hud-dodge-indicator | hud | `rendering.UI.components.hotbar.DodgeIndicator` | per-frame immediate | not started |
| hotbar-item-tooltip | tooltip | `rendering.UI.components.hotbar.HotbarTooltipRenderer` + `ui.HotbarScreen` | per-world | not started |
| crosshair | hud | `rendering.UI.components.MCrosshairRenderer` | process-persistent | not started |
| chat | hud | `ui.chat.ChatSystem` + `ui.chat.SkijaChatRenderer` | process-persistent | not started |
| chat-emoji-picker | dialog | `ui.chat.emoji.EmojiPickerRenderer` + `ChatEmojiSystem` | process-persistent | not started |
| debug-overlay | overlay | `ui.DebugOverlay` + `ui.debug.*` | process-persistent | not started |
| dev-document-overlay | overlay | `ui.runtime.DevDocumentOverlay` (dev only, `-Dstonebreak.uidoc`; a document host, not a legacy screen, #287) | process-persistent | n/a |
| debug-world-wireframes | custom-renderer | `ui.debug.MobPathWireframeDrawer`, `rendering.UI.rendering.DebugRenderer`, `rendering.emitters.SoundEmitterRenderer` | process-persistent | not started |
| world-damage-numbers | hud | `rendering.UI.components.DamageNumberRenderer` | singleton | not started |
| world-quarry-markers | hud | `rendering.UI.components.QuarryMarkerRenderer` | singleton | not started |
| world-doubt-markers | hud | `rendering.UI.components.DoubtMarkerRenderer` | singleton | not started |
| world-enemy-awareness | hud | `rendering.UI.components.EnemyAwarenessRenderer` | singleton | not started |
| world-player-nametags | hud | `rendering.UI.components.PlayerNameTagRenderer` | singleton | not started |
| stealth-hud | hud | `rendering.UI.components.StealthHudRenderer` | singleton | not started |
| world-revealed-outline | custom-renderer | `rendering.gameWorld.effects.WorldEffectsRenderer` | process-persistent | not started |
| effect-underwater-tint | effect | `rendering.UI.components.UnderwaterOverlayRenderer` | process-persistent | not started |
| effect-dodge-vignette | effect | `rendering.UI.components.DodgeInvincibilityOverlay` | process-persistent | not started |
| effect-screen-droplets | effect | `rendering.UI.components.ScreenDropletOverlay` | process-persistent | not started |
| effect-pause-depth-curtain | effect | `rendering.pipeline.DepthCurtainRenderer` | process-persistent | not started |
| battle-hud | screen | `ui.focusBattle.FocusBattleScreen` + `SkijaFocusBattleRenderer` | shell-persistent, bound per encounter | not started |
| battle-command-window | hud | `ui.focusBattle.elements.CommandWindow` + `BattleMenuState` | per-encounter | not started |
| battle-party-status | hud | `ui.focusBattle.elements.PartyStatusWindow` + `StatusChips` | per-encounter | not started |
| battle-help-strip | hud | `ui.focusBattle.elements.HelpStrip` + `BattleHelpText` | per-encounter | not started |
| battle-enemy-plate | hud | `ui.focusBattle.elements.EnemyPlate` (MCastBar) | per-encounter | not started |
| battle-mode-tag | hud | `ui.focusBattle.SkijaFocusBattleRenderer` (MBadge) | per-encounter | not started |
| battle-action-banner | overlay | `ui.focusBattle.elements.ActionBanner` | per-encounter | not started |
| battle-target-cursor | overlay | `ui.focusBattle.elements.TargetCursor` | per-encounter | not started |
| battle-floaters | overlay | `ui.focusBattle.elements.BattleFloaters` | per-encounter | not started |
| battle-timing-ring | overlay | `ui.focusBattle.timed.TimingRingOverlay` | per-encounter | not started |
| battle-parry-overlay | overlay | `ui.focusBattle.timed.ParryOverlay` | per-encounter | not started |
| battle-combo-strip | overlay | `ui.focusBattle.timed.ComboStripOverlay` | per-encounter | not started |
| battle-screen-fx | effect | `ui.focusBattle.timed.ScreenFxLayer` (MScreenFx) | per-encounter | not started |
| battle-encounter-card | effect | `ui.focusBattle.elements.EncounterTransition` | per-encounter | not started |
| battle-encounter-swirl | effect | `ui.focusBattle.intro.EncounterSwirl` + `FrameGrab` | per-FocusBattle | not started |
| battle-result-panel | dialog | `ui.focusBattle.elements.ResultPanel` (MResultCard) | per-encounter | not started |
| skija-ui-backend | custom-renderer | `rendering.UI.backend.skija.SkijaUIBackend` + `SkiaContext` | process-persistent | not started |
| masonry-ui-kit | custom-renderer | `rendering.UI.masonryUI.*` (MasonryUI, MPainter, MStyle, M* widgets) | per-screen instances | not started |
| mtexture-registry | custom-renderer | `rendering.UI.masonryUI.textures.MTextureRegistry` / `MTexture` | process-static | not started |
| block-icon-renderer | custom-renderer | `rendering.UI.menus.BlockIconRenderer` | process-persistent | not started |
| item-icon-renderer | custom-renderer | `rendering.UI.menus.ItemIconRenderer` | process-persistent | not started |
| entity-preview-renderer | custom-renderer | `rendering.models.entities.EntityRenderer#renderPlayerPreview/renderEntityPreview` | process-persistent | not started |
| tab-strip-layout | custom-renderer | `ui.TabStripLayout` | static | not started |
| ability-icon-cache | custom-renderer | `rpg.classes.AbilityIconCache` | process-static | not started |
| opengl-quad-renderer | custom-renderer | `rendering.UI.components.OpenGLQuadRenderer` + `UIQuadRenderer` | process-persistent (mostly dead) | not started |
| legacy-stb-font | custom-renderer | `ui.Font` | process-persistent (unused draw path) | not started |

Row count: 77. By kind: screen 17, hud 18, custom-renderer 12, overlay 10, dialog 9, effect 7, tooltip 4.

## Hard visuals

These are never to be dropped. Each needs a host-code draw provider, a native slot, or exact-replica proof.

1. **Raw-GL 3D model previews after the Skija flush**, composited straight onto the default framebuffer with no FBO, so nothing can draw over them:
   - character creation, the player model (`ui/characterCreation/renderers/SkijaCharacterCreationRenderer.java:195-260`)
   - the character sheet Overview tab (`ui/characterScreen/CharacterRenderCoordinator.java:407-466`)
   - glossary mobs with an animated "Idle" clip (`ui/glossaryScreen/SkijaGlossaryRenderer.java:478-534`)

   All three orbit on `getTotalTimeElapsed()*0.6` and restore GL state only partially.
2. **3D block icons in item slots** (`rendering/UI/menus/BlockIconRenderer.java:141-405`): per-slot viewport, scissor and depth clear in the shared world depth buffer, the world shader, and hard-coded blending for leaves. They interleave with Skija in three phases (Skija → GL → Skija for counts) in the hotbar, inventory, workbench, furnace and recipe book.
3. **SBO sprite icons** and SBT/OMT composited images. Since #330 every slot draws the same `MTextureRegistry` image with alpha (see Cross-cutting facts, "Item sprites in slots"); before that the screens used an OPAQUE `ItemIconRenderer` copy built by `SpriteVoxelizer`.
4. **Battle timed-input overlays** (ring, parry brackets, combo timer, cast-bar window). What the player sees is exactly what is graded on the C2 clock. **Never interpolate or extrapolate them from a render clock.**
5. **Battle HUD events are consumed per tick by identity** (`FocusBattleScreen.java:205-210`, `timed/TimedInputState.java:132-140`). Decoupling HUD updates from `sim.update` 1:1 silently drops floaters, flashes, shakes and the end-of-battle hold.
6. **Encounter swirl**: a synchronous back-buffer `glReadPixels` freeze-frame plus a runtime SkSL shader (10 taps per pixel, compiled on its first paint), handing off white-to-white to the name card. See `ui/focusBattle/intro/*`.
7. **Main-menu shockwave reveal**: a DIFFERENCE clip path over a 64-gon, screen shake with overscan, a drop-shadow `ImageFilter`, and a pixel-art space scene baked at 216 px tall and upscaled (`ui/mainMenu/SkijaMainMenuRenderer.java:95-294`, `SpaceBackgroundRenderer.java:81-279`).
8. **Startup intro**: a fully procedural scanline-primitive ocean, sonar rings and submarine, plus a logo reveal tied to the ring radius (`ui/startupIntro/render/*`).
9. **Furnace crucible art**: a heat-ramp disk, lava clipped to a 36-gon, bézier flames, progress arcs, and animation on a `System.nanoTime` epoch (`ui/furnace/renderers/FurnaceRenderCoordinator.java:213-476`).
10. **Legacy immediate-mode GL screen effects** (underwater, dodge vignette, droplets): `glPushAttrib`/`glBegin`/`GL_POINTS` with `POINT_SMOOTH`. These require a compatibility profile.
11. **Terrain mapper live noise preview**: a raster image rebuilt from noise sampling, blocking the render thread (`ui/terrainMapper/managers/TerrainPreviewCache.java:92-130`).
12. **Animated GIF emoji** decoded with ImageIO and animated on the wall clock (`ui/chat/emoji/GifAnimationCache.java`).
13. **Field pause menu drawn twice per frame**. The stacked 0x78 scrims give about 72 % darkness, against about 47 % over a battle. A faithful port must reproduce the darker field pause on purpose.
14. **DOT/PLUS_DOT crosshair with outline renders inverted**: black fill, coloured ring (`rendering/UI/components/MCrosshairRenderer.java:99-107,187-203`).

## Rows

### startup-intro
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.startupIntro.SonarArtsIntroScreen`.
  - Created at GS:91 and never disposed (`dispose` :247-250 is dead).
  - The constructor registers an OpenAL sample, so it must be created after the sound system is up (GS:71-73).
  - The scene is rebuilt on window resize, which drops the active rings (:107-123).
- **Entry:**
  - Initial state is STARTUP_INTRO (GSC:25).
  - Exits to MAIN_MENU through `finish()` (:240-243).
  - Timeline: submarine 1.8 s, hold 1.2 s, fade 1.0 s (`IntroConfig.java:26-28`).
- **Input (existing):**
  - Polled edge-detected Esc, Space and Enter skip (:222-233). Edge flags start false, so a key already held at launch skips at once.
  - Left mouse press skips (MIR:90-91).
  - Nothing else.
- **Assets:**
  - `/ui/startupIntro/SonarArts_Retro.png` (`render/SonarLogoRenderer.java:22`)
  - `/sounds/StartupSonar.wav` (:63-65)
- **Bindings/actions:** `setState(MAIN_MENU)` only.
- **Layout:** proportional to 1920×1080 via `IntroConfig.scale` (:12-16). Ignores uiScale.
- **Time:** frame dt via `GL:110-112` → `update(dt)`, tweens included.
  - Bubble spawn is a per-frame probability (:146,153), so density depends on FPS.
- **Sound:** sonar ping per ring every 0.6 s at volume 0.5, faded during the fade (:99-105, 138-142).
- **Custom draw:** procedural Skija drawn as scanline circles, ellipse slices, segmented outlines, a 60-band ocean and caustics (`render/IntroPainter.java:43-110`, `OceanBackgroundRenderer.java:21-56`). The logo uses an `SRC_IN` tinted shadow.
- **Fixtures:** none.
- **Performance:** a new `Paint` per primitive. Caustics draw roughly `5×(h/4·s)` rects per frame.

### main-menu
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.MainMenu` with `ui.mainMenu.{MainMenuStage, SkijaMainMenuRenderer, SpaceBackgroundRenderer, SplashTextManager}`.
  - Created at GS:82, never disposed.
  - Entering MAIN_MENU runs `refreshSplashText` + `resetTitleAnimation` (GSC:78-82). It also shuts down FocusBattle, the MultiplayerSession, chat and cheats (GSC:60-62, 97-110).
- **Entry:** intro end; Back from WorldSelect, Settings (previous state MAIN_MENU), Multiplayer and Character creation (join mode); client build failure; network disconnect.
- **Exits:**
  - WORLD_SELECT, MULTIPLAYER_MENU
  - SETTINGS via `setPreviousState(MAIN_MENU)`
  - Quit → `System.exit(0)` (`MainMenu.java:109-125`), which bypasses cleanup and the settings save
- **Input (existing):**
  - Polled Up/W, Down/S and Enter, **level-triggered with no edge detection** (:39-60), so holding a key repeats every frame.
  - Mouse move sets the hovered button or −1, which wipes keyboard focus (:62-64).
  - Left click (:66-82): on the logo it drives an easter egg (shake, then slam and reveal the space scene); on a button it executes it.
  - No Esc, scroll or text.
- **Assets:** backend logo, `Dirt.png`, `Minecraft.ttf`, `ui/mainMenu/splash_text.json` (32 entries, four classloader fallbacks, `SplashTextManager.java:37-103`).
- **Bindings:** uiScale, splash list.
- **Layout:**
  - Centered × uiScale: 400×40 buttons at a 50 px pitch; logo 140·s tall, 120·s above centre (`SkijaMainMenuRenderer.java:32-36,69-128`).
  - The hit-test duplicates these constants (`MainMenu.java:88-102`). Only the logo rect is shared.
- **Time:** three clocks.
  - `MainMenuStage` uses `Game.getDeltaTime()` inside render (`MainMenu.java:129`).
  - The splash pulse uses `System.currentTimeMillis` (renderer :271).
  - Sparkles use `getTotalTimeElapsed` (:239-242).
- **Sound:** menu music only.
- **Custom draw:** see Hard visuals #7. The splash text is rotated −15° with four shadow layers. The dirt-tile background is copy-pasted into world-select, settings and multiplayer.
- **Fixtures:** `ui/MainMenuTest` has 4 tests, hit-test maths only.
- **Performance:** the drop-shadow `ImageFilter` and the 64-gon path are allocated every frame. The space bake is cached per size.

### world-select
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.worldSelect.WorldSelectScreen` with `managers.{WorldStateManager, WorldDiscoveryManager, WorldStatsService, WorldBackupService}`, `handlers.{WorldActionHandler, WorldInputHandler, WorldMouseHandler}`, `renderers.SkijaWorldSelectRenderer`, `WorldSelectLayout` and `SectionBounds`.
  - Created at GS:88. The constructor and every WORLD_SELECT entry call `refreshWorlds()` (GSC:84-86).
  - State resets only on Back or after a world load (:243-256).
  - Daemon executors `world-size-scanner` and `world-backup` are never shut down (`dispose` :166-170 is dead).
- **Entry:** main menu Singleplayer; Character creation "World Select"; Terrain mapper Back/Esc.
- **Exits:**
  - Back/Esc → MAIN_MENU
  - Play → `MultiplayerSession.startSingleplayer` (`WorldActionHandler.java:92`)
  - Create → resets character creation and goes to CHARACTER_CREATION (:110-115)
- **Input (existing):**
  - Polled, edge-detected: Up/W, Down/S, Enter/Space (load the selection, or go to creation when there are no worlds) and Esc (`WorldInputHandler.java:35-155`).
  - **N** goes to creation and is **not** edge-detected (:125-127).
  - Key callback: DELETE removes the last character; Ctrl+V pastes (`MClipboard`), dialog only (:201-224).
  - Char callback goes to the dialog fields only. MIR `onCharacter` and IH:224-227 both route it.
  - Mouse: hover on rows, buttons and the card. Left press selects a row (no double-click) or presses Play/Create/Delete/Back (`WorldMouseHandler.java:105-163`).
  - Wheel: one row per tick anywhere (MIR:171).
- **Assets:** `Dirt.png`, font.
- **Bindings:**
  - The worlds root is sorted by `lastPlayed`.
  - `world.json` sits behind a 5 s cache keyed by one global timestamp (`WorldDiscoveryManager.java:29,42-145`).
  - Size and chunk scans and backup status run on executors.
- **Actions:** load, delete (recursive `Files.walk`), open folder, zip backup.
- **Layout:**
  - `WorldSelectLayout` multiplies its constants by uiScale: 8 rows × 56, a 720 px list, 170×44 buttons.
  - The renderer mixes in unscaled offsets for text 18/26/46, a 6 px scrollbar and a +7 baseline (renderer :232-324).
  - `config/WorldSelectConfig` is dead.
- **Time:** caret blink uses `currentTimeMillis` (renderer :569-575).
- **Sound:** menu music.
- **Custom draw:** none beyond Masonry painters.
- **Fixtures:** `WorldStateManagerTest`, `WorldInfoCardStateTest`, `WorldBackupServiceTest`, `WorldStatsServiceTest`, `SectionBoundsTest` (logic only). No render or input tests.
- **Performance:** `getWorldList()` copies the list several times per frame. Per-row `String.format` and `getWorldData` run every frame and hit the disk when the 5 s cache lapses.

### world-select-info-card
- **Kind:** tooltip (hover popover). Owner: as world-select; state in `WorldStateManager.java:179-228`.
- **Entry:**
  - Opens 350 ms after hovering a row and closes 220 ms after leaving.
  - Closes on scroll, refresh or opening the dialog.
- **Input (existing):** has mouse priority over the rows (`WorldMouseHandler.java:40-48`). Buttons "Open Folder" and "Back Up" (the latter disabled while a backup runs).
- **Bindings:** Size, Chunks ("Measuring..."), Seed, Created, Last played, Play time, backup status and progress. Results expire after 12 s (`WorldBackupService.java:37`).
- **Layout:** `layout.cardBounds(row)`, anchored to the row.
- **Time:** `System.currentTimeMillis`, ticked from render (`WorldSelectScreen.java:162`).
- **Fixtures:** `WorldInfoCardStateTest` (timing with injected `nowMs`).

### world-select-delete-confirm
- **Kind:** dialog. Owner: as world-select.
- **Entry:** only the Delete button with a selection (`WorldMouseHandler.java:151-157`). There is no keyboard route in.
- **Input (existing):**
  - **Enter confirms the delete**, Esc cancels (`WorldInputHandler.java:45-57`).
  - A click outside the panel cancels (`WorldMouseHandler.java:166-181`).
- **Draw:** 0xCC scrim, panel, "Delete World?", Delete/Cancel (renderer :366-391).
- **Fixtures:** none.

### world-select-create-dialog
- **Kind:** dialog. **Unreachable in production.**
  - Render (renderer :330-364), keys (`WorldInputHandler.java:76-88,160-196`), mouse (:183-208) and validation all exist.
  - `WorldStateManager.openCreateDialog()` (:301-306) has callers only in tests.
- **Input (existing, if revived):** name and seed fields; Backspace edge-only (no repeat); DELETE removes the last character; Ctrl+V.
- **Notes:** the empty-list hint still says "click 'Create New World'" while the button reads "Create World".
- **Fixtures:** `WorldStateManagerTest` only.
- **Decision for #299:** keep or delete it.

### character-creation
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.characterCreation.CharacterCreationScreen` (own MasonryUI) with StateManager, ActionHandler, InputHandler, MouseHandler, `CharacterCreationLayout`, `CharacterCreationTab` and `renderers.{SkijaCharacterCreationRenderer, BackgroundTabRenderer, AbilityScoreTabRenderer, ClassAbilitiesTabRenderer, SkillsTabRenderer, FeatsTabRenderer, LooksTabRenderer, ClothingTabRenderer, CosmeticOptionsRenderer, CharacterModelPreviewRenderer}`.
  - Created at GS:89, never disposed.
  - `reset()` runs on the WorldSelect create path and on Back. It is **not** run when the server sends `NeedsCharacterCreation` (`network/.../ClientWorldView.java:641-648`).
  - FR:107 calls `updateLabelsForMode()` every frame. In join mode the footer reads "Main Menu" / "Enter World".
- **Entry:** WorldSelect Create/N/Enter-with-no-worlds; Terrain mapper "Character"; server `NeedsCharacterCreation` (join).
- **Exits:**
  - Back → WORLD_SELECT; in join mode, shutdown + MAIN_MENU.
  - Next → TERRAIN_MAPPER; in join mode, `MultiplayerSession.submitCharacterCreation`.
- **Input (existing):**
  - **Keyboard is dead.** `handleInput` is a no-op, and MIR `onKey` has no CHARACTER_CREATION case, so the Tab/Esc handler (`CharacterCreationInputHandler.java:27-34`) never runs.
  - Mouse: hover; any press except release hits footer → tabs → content (`CharacterCreationMouseHandler.java:45-59`).
  - Wheel scrolls the active Class, Skills or Feats tab by 24 px regardless of the cursor.
- **Assets:**
  - `/ui/recipeScreen/Elm_UI.sbt` via MTextureRegistry (wood tiles)
  - class and ability icons via AbilityIconCache
  - the REMOTE_PLAYER SBE model for the preview
- **Bindings:** `CharacterStats`; the class, background, skill and feat registries; `PlayerLooks`.
  - Hair and hat clicks write `Settings` and **call `saveSettings()` on every click** (`PlayerLooks.java:40-50`). `reset()` does not undo them.
  - `Game.initWorldComponents` copies these stats into the player on every world init, including existing worlds (`core/Game.java:186-205`).
- **Layout:** left panel at 35 % of the width, 72 px footer, 40 px tab bar. Everything else is absolute px. Fonts come from unscaled `fonts().get`, so **uiScale is ignored**.
- **Time:** preview orbit uses `getTotalTimeElapsed()`.
- **Sound:** menu music.
- **Custom draw:** 3D player preview (Hard visuals #1, row `entity-preview-renderer`).
- **Fixtures:**
  - `CharacterCreationLayoutTest` (13)
  - `AbilityScoreTabRenderTest`: headless raster pixel probe
  - `AppearanceTabsTest`: raster render plus click
- **Notes:**
  - Scrolled-out Skills/Feats/ClassAbilities buttons stay clickable.
  - ClassAbilities hit-tests five spend slots regardless of the class.
  - `handleClick` recomputes the layout from `Game.getWindowWidth/Height` rather than its parameters.

### terrain-mapper
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.terrainMapper.TerrainMapperScreen` (own MasonryUI and own WorldDiscoveryManager) with `managers.{TerrainMapperStateManager, TerrainPreviewCache}`, `handlers.*`, `renderers.{SkijaTerrainMapperRenderer, TerrainMapRenderer, TerrainSidebarRenderer, TerrainFooterRenderer}`, `components.TerrainMapViewport`, `visualization.*` (7 visualizers) and `TerrainMapperLayout`.
  - Created at GS:90. State persists across visits.
  - `reset()` (which randomizes the seed) runs only on Back or after a successful create.
- **Entry:** Character creation Next.
- **Exits:**
  - Back/Esc → reset + WORLD_SELECT. This skips character creation.
  - "Character" → CHARACTER_CREATION.
  - Create/Enter → writes `world.json` + `metadata.json` (with spawn) → `startSingleplayer` (`TerrainActionHandler.java:57-108`).
- **Input (existing):**
  - Key callback (`TerrainInputHandler.java:38-50`): Backspace with repeat; Tab toggles the field; **Enter creates the world even with no field focused**; Esc goes back; Ctrl+V pastes.
  - Char input goes to the focused field.
  - Mouse: press on the map starts a drag-pan; a release within 4 px of the press sets spawn (`TerrainMouseHandler.java:30-116`). Hover samples the visualizer synchronously for a readout.
  - Wheel zooms ×1.15 (0.25–8) about the cursor, map only.
- **Assets:** none (fonts only).
- **Bindings:** `NoiseRouter` → `HeightMapGenerator` → `BiomeManager`, rebuilt **synchronously on every seed keystroke** (StateManager :236-238).
- **Layout:** absolute px: sidebar 320, footer 88, 180×44 buttons (`TerrainMapperConfig.java:13-30`). Ignores uiScale.
- **Time:** caret uses `currentTimeMillis`; interaction cooldown uses `nanoTime` (6 px sample step while interacting, 2 px when settled).
- **Custom draw:** live noise raster (Hard visuals #11).
- **Fixtures:** none.
- **Performance:** the preview rebuild uses a parallel stream but blocks the render thread (`TerrainPreviewCache.java:92-112`) and allocates a new `byte[]` + `Image` per rebuild (≈400k samples at 1080p).
- **Notes:** a blank seed previews seed 0 but creates a random seed (StateManager :240-241 vs ActionHandler :65-69).

### loading-screen
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.LoadingScreen` + `ui.SkijaLoadingScreenRenderer`, created at GS:87.
  - `show()` resets and calls `setState(LOADING)` (`LoadingScreen.java:74-85`, from `core/world/ClientWorldBuilder.java:90-93`).
  - `hide()` → PLAYING (`ClientWorldBuilder.enterPlay` :195-199).
  - The failure path goes to MAIN_MENU without `hide()`, so `visible` stays true (`ClientWorldBuilder.java:205-218`).
- **Input (existing):**
  - Polled Esc → MAIN_MENU only if `hasError` (:205-213). `hasError` is never set, so it is unreachable.
  - Mouse events fall through to IH and do nothing.
- **Assets:** logo with a drop-shadow `ImageFilter`; font.
- **Bindings:** `updateProgress(stage)` is called from `world/World.java:313`, `ChunkMeshLifecycle.java:83` and `TerrainGenerationSystem.java:975`, possibly off-thread, on non-volatile fields.
  - The bar shows stage index out of 10 fixed strings, not real progress (:27-38,93-105).
- **Layout:** absolute px around centre (400×30 bar, logo 120 px). Ignores uiScale.
- **Time:** none.
- **Custom draw:** 0xD9 scrim over a cleared framebuffer, with no world behind it.
- **Fixtures:** none.
- **Performance:** **`ensureFonts()` allocates 9 native Skija `Font`s every frame and never closes them** (`SkijaLoadingScreenRenderer.java:90-103,112`).
- **Notes:**
  - The error panel, sub-stage line, ETA and recovery actions are dead (renderer :201-311).
  - The severity colours lack an alpha byte.
  - The bullets are glyphs the font does not have.

### settings
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.settingsMenu.SettingsMenu` (own MasonryUI) with `managers.{StateManager, SettingsManager}`, `handlers.{ActionHandler, InputHandler, MouseHandler}`, `components.ScrollableSettingsContainer` (MScrollContainer), `renderers.SkijaSettingsRenderer` and `config.{CategoryState, SettingsConfig}`.
  - Created at GS:83. Widgets are built once from `Settings`. Category and scroll persist; there is no reset on show.
  - Entering SETTINGS hides F3 (GSC:114-119).
- **Entry:**
  - Main menu, with `setPreviousState(MAIN_MENU)`.
  - Pause menu Settings: `setPreviousState(PLAYING)` and the pause menu is hidden (UMR:186-193).
- **Exits:** Back/Esc → `goBack()`, which discards an un-applied UI scale change, then either:
  - PLAYING, i.e. **straight back to gameplay, not to the pause menu**, redirected to FOCUS_BATTLE when a battle is active; or
  - MAIN_MENU (`ActionHandler.java:137-166`).
- **No world behind it:** FR:119-122 draws only this screen.
- **Categories:**
  - General: Resolution dropdown, UI Scale slider (deferred apply).
  - Quality: Leaf Transparency, Water Animation, Clouds, God Rays, Shadows, Shadow Quality dropdown, Shadow Distance, Smooth Lighting.
  - Performance: Render Distance, VSync, Max FPS.
  - Advanced: Arm Model dropdown, LOD toggle, LOD Distance, LOD Quality dropdown.
  - Extras: Crosshair style dropdown (6), Crosshair Size 4–64, Player Name Tags.
  - Audio: Master, Music volume, Music toggle.
  - Footer: Apply, Back.
  - Wiring is in `StateManager.java:282-335`; option arrays in `SettingsConfig.java:13-97`.
- **Input (existing):**
  - Polled (`InputHandler.java:31-109`). These are **level-triggered**, so a tap repeats every frame:
    - Left/A and Right/D change category.
    - Up/W and Down/S move rows, including the virtual Apply/Back rows.
    - Shift+Left/Right adjusts the value (except UI scale).
  - Edge-detected: Enter activates the row (toggles a dropdown, flips a toggle, Apply or Back); Esc goes back.
  - **Keyboard focus is never drawn**: `updateButtonSelectionStates` clears it every frame (StateManager :347-377).
  - Mouse:
    - **Any button** press goes to the open dropdown, then the scrollbar, categories, widgets and Apply/Back (`MouseHandler.java:46-134`).
    - Move drags sliders and the scrollbar.
  - Wheel goes to MScrollContainer.
  - No key callback and no text.
- **Bindings/actions:**
  - Widgets mutate `Settings` immediately.
  - Apply calls `saveSettings()` and pushes audio, crosshair, window resize (`glfwSetWindowSize` + `Main.refreshWindowSize`), world distances and `client.sendViewDistance` (ActionHandler :77-92, `SettingsManager.java:23-151`).
  - Back without Apply leaves the changes live but unsaved.
  - Leaf Transparency and Smooth Lighting rebuild chunks.
- **Layout:** fully × uiScale via `SettingsConfig.getScaled*`.
  - Panel `min(700·s, 0.92w) × min(550·s, 0.92h)`.
  - The category column at centre − 320·s is not clamped.
  - 80·s row pitch, culling with a 50·s buffer.
- **Time:** `scrollMath.update(1f/60f)` per rendered frame, so easing depends on FPS (renderer :66-68).
- **Sound:** menu music, but only when no world exists (GL:98).
- **Custom draw:** none special. There is **no crosshair preview**.
- **Fixtures:** `CategoryStateTest` only.
- **Performance:** `refreshLabels()` rebuilds about 20 strings every frame.
- **Notes:** culled widgets keep stale bounds, so they can be clicked outside the clip.

### settings-ui-scale-confirm
- **Kind:** dialog. Owner: as settings.
- **Entry:** Apply with a changed UI scale. It applies the scale at once and starts a 10 s auto-revert countdown (`ActionHandler.java:99-132`, `SettingsMenu.java:82-86`, StateManager :510-531).
- **Input (existing):** modal for mouse and keyboard; Enter keeps, Esc reverts (`InputHandler.java:34-53`).
- **Draw:** 0xC8 scrim over the dropdown overlays (renderer :84-129).
- **Time:** countdown on `currentTimeMillis`, ticked from `handleInput`.
- **Fixtures:** none.

### settings-dropdown-overlay
- **Kind:** overlay. Owner: `MDropdown` instances for Resolution, Arm Model, Crosshair, Shadow Quality and LOD Quality, drawn through the `MasonryUI.pushOverlay`/`renderOverlays` queue (`rendering/UI/masonryUI/MasonryUI.java:74-84`; renderer :80-81).
- **Input (existing):** an open dropdown consumes clicks first (`MouseHandler.java:136-155`). Shift+arrows move the option while it is open.
- **Fixtures:** `MDropdownInteractionTest` (widget level).

### multiplayer-menu
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.multiplayerMenu.MultiplayerMenu` (+ shared `MultiplayerUIPainter`), created at GS:84. No reset; lazy fonts.
- **Entry:** main menu Multiplayer; Back/Esc from Host or Join.
- **Exits:** HOST_WORLD_SELECT, JOIN_WORLD_SCREEN, MAIN_MENU (`MultiplayerMenu.java:82-89`).
- **Input (existing):**
  - Mouse hover and left press.
  - **Esc is polled level-triggered** (:76-80). One Esc on Host/Join therefore cascades to MAIN_MENU (inferred from code).
  - No keyboard navigation; Enter does nothing.
- **Assets:** `Dirt.png`, font.
- **Layout:** centred 400×40 buttons at a 50 px pitch, fixed font sizes. Ignores uiScale.
- **Fixtures:** none.

### host-world
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.multiplayerMenu.HostWorldScreen` (own WorldDiscoveryManager).
  - `onShow()` on entry (GSC:88-90): rediscover worlds, select index 0, load the port, clear status (`HostWorldScreen.java:58-64`).
- **Input (existing):**
  - Lists **only the first 8 worlds, with no scroll** (:97-101,138-141).
  - Port field: digits only, max 5, via the char callback (:172-177). Backspace and Enter via the key callback when focused.
  - Esc polled level-triggered (:165-170).
- **Actions:** validates the port 1–65535, saves it, calls `MultiplayerSession.startHosting` (:189-212).
- **Layout:** absolute Y (70/100/130…) under a centred X. Ignores uiScale.
- **Time:** caret on `currentTimeMillis`.
- **Notes:** the empty-list text contains an em-dash, which renders as tofu (:94).
- **Fixtures:** none.

### join-world
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.multiplayerMenu.JoinWorldScreen`.
  - `onShow()` loads `lastJoinHost`, port and username from Settings (`JoinWorldScreen.java:43-50`).
- **Input (existing):**
  - Fields: host (64 characters, overflows its 320 px box), port (digits), username (24).
  - Keys: Tab cycles focus, Enter unfocuses, Backspace with repeat. No paste.
  - Esc polled level-triggered (:133-137).
- **Actions:** validate → save to Settings → `MultiplayerSession.joinServer`. The status shows "Connecting..." while the screen stays put. The server then drives CHARACTER_CREATION or LOADING (:165-189).
- **Layout:** absolute px. Ignores uiScale.
- **Fixtures:** none.

### pause-menu-offline
- **Kind:** dialog.
- **Owner:** `com.stonebreak.ui.PauseMenu` + `ui.pauseMenu.SkijaPauseMenuRenderer`.
  - Created at GS:78; `cleanup()` at `GameShutdown.java:44` and `rendering/Renderer.java` cleanup.
  - Visibility is the `visible` flag (`PauseMenu.java:22`).
- **Entry:** Escape, polled in `UTK.pollEscape` (UTK:76-136). The pause menu is the last fallback, after recipe book, workbench, furnace, inventory, character, statistics and glossary.
  - `GSC.togglePauseMenu` (:141-153) toggles PAUSED/PLAYING.
  - Ignored while chat is open. Nothing else opens it: no focus-loss pause.
- **Input (existing):**
  - Left press via MIR default → `IH.processMouseButton` → `UMR.route`. Death menu, polled screens and chat come first (UMR:49-100).
  - `handlePauseMenuClick` (UMR:175-209):
    - Resume → toggle.
    - Statistics / Glossary → `GSC.open*`.
    - Settings → `setPreviousState(PLAYING)` + SETTINGS + hide.
    - Quit → `resetWorld` + MAIN_MENU + hide.
  - Hover via `UMR.onMouseMove` → `updateHover`.
  - Escape toggles it closed. No keyboard navigation.
- **Layout:**
  - 5 buttons: Resume, Statistics, Glossary, Settings, Quit.
  - `buttonOffset(slot,count) = (slot-(count-1)/2)*70` (renderer :52-54), centred.
  - Panel fixed at 520×560 × uiScale. Title "GAME PAUSED" at panelY + 70. Fonts rebuilt when the scale changes.
  - Hit-tests share the formula (`PauseMenu.java:105-116`).
- **Assets:** font only; stone painters (`MPainter.stoneSurface/panel`).
- **Time:** none. In PAUSED, GL:142-154 returns early: no world, chat or tooltip ticks.
- **Sound:** none.
- **Custom draw:**
  - 0x78 full-screen scrim.
  - **Drawn twice per frame in PLAYING/PAUSED**: `renderGameUI` → `renderActivePauseMenu` (FR:313, 398-406) and `renderModalMenus` (FR:431-435). That is two Skia paints, with the scrim stacked to about 72 %.
  - The depth curtain follows (row `effect-pause-depth-curtain`).
- **Fixtures:** `ui/PauseMenuTest`: hit-tests across `Resolutions.ALL`, hidden state, Resync hidden offline, hover = hit-test.
  `ui/fidelity/LegacyPauseBaselineTest` (#296): pixel baselines of the real renderer on a pinned CPU raster, scrim
  darkness (field 2 passes vs battle 1), hover isolation, renderer rects = the geometry oracle. Legacy capture for
  the #297 gate: `ui.fidelity.LegacyPauseCapture` (variants `field|battle`-`offline|online`[-`hover-<button>`]).
- **Fidelity:** `pause/pause-field-offline_1920x1080_s1` `pause/pause-field-offline_1280x720_s0_75` `pause/pause-field-offline_3840x2160_s2` `pause/pause-field-offline_1921x1081_s1_25` `pause/pause-field-offline-hover-quit_1920x1080_s1` `pause/pause-field-offline-hover-resume_1920x1080_s1`
- **Notes:**
  - **The Resume button never showed hover** (a hard-coded `false`) until #297 gave it the same hover as the other
    buttons, in the document and the legacy renderer alike (case `field-offline-hover-resume`).
  - Escape while the death menu is up opens an unclickable pause menu under it.
- **Migration (#297, in progress):** shipped as `ui/documents/pause.sbui` (+ shared `ui/shared/stonebreak/ui/components/stone_button.omui`),
  authored in Open Mason (project "Stonebreak Menus": `UI/stonebreak/ui/screens/pause.omui`, component
  `UI/stonebreak/ui/components/stone_button.omui`). `PauseMenu` keeps the lifecycle and both draw calls; its
  `Presentation` seam (`ui.pauseMenu.PauseDocument`) opens the document as an `ownerPaints` screen, so the field pause is
  still composited twice and the battle pause once. Resume hovers like the other buttons (user decision, 2026-10-07; legacy changed to match). The
  legacy renderer stays as the rollback (`-Dstonebreak.ui.legacy=pause`, or automatically when the document is refused,
  fails a frame, or the death menu is up) until sign-off; then this row becomes `migrated` and the legacy renderer goes.
  New vs legacy: Tab/Enter keyboard focus on the buttons (legacy had none).
- **Gate:** `ui.fidelity.PauseDocumentGateTest` (all 12 committed cases at `FLOAT_EXACT` + `EXACT`: 0 pixels differ;
  hit regions and one action per button; Resync reflow on a live session change; a failing resync action).

### pause-menu-online
- **Kind:** dialog, a variant of pause-menu-offline.
- **Owner:** `PauseMenu.isResyncButtonVisible()` = `MultiplayerSession.isOnline()` = mode HOST or JOIN (`network/MultiplayerSession.java:64`), read live every frame. Singleplayer, even as integrated server + client, counts as offline.
- **Layout:** 6 buttons, with "Resync World" in slot 4 and Quit in slot 5. Offsets ±175 instead of ±140, so **every button moves 35 px**. The panel stays 520×560.
- **Actions:** Resync → `MultiplayerSession.requestFullResync()` (chunk audit + entity snapshot; −1 when not connected) → chat message → resume (UMR:194-202).
- **Fixtures:** since #296 `ui.UiOnlineState.override` is the seam (also feeds documents' `session.online`): `PauseMenuTest` covers the 6-button hit-tests,
  `LegacyPauseBaselineTest` the pixels. Live: `-Dstonebreak.autopause=<s>:online`.
- **Fidelity:** `pause/pause-field-online_1920x1080_s1` `pause/pause-field-online_1280x720_s0_75` `pause/pause-field-online_3840x2160_s2` `pause/pause-field-online_1921x1081_s1_25` `pause/pause-field-online-hover-resync_1921x1081_s1_25`

### pause-menu-over-battle
- **Kind:** dialog, a variant.
- **Entry:**
  - In FOCUS_BATTLE, Escape through the HUD (`FocusBattleScreen.handleKeyInput` → `host.openPauseMenu`), or the MIR fallback for an unhandled Escape (MIR:248-266) → `FocusBattle.openPauseMenu` (`battle/stage/FocusBattle.java:578-589`).
  - That calls `suppressHeldUiToggleKeys` and then `togglePauseMenu` → PAUSED.
  - Battles are singleplayer-only, so this is always the 5-button layout.
- **Exits:** every resume path calls `setState(PLAYING)`, which is redirected to FOCUS_BATTLE (GSC:55-57).
- **Draw:** FR:144-147 → `renderFocusBattle`: world + frozen HUD + swirl/transition + `renderModalMenus`. The pause menu is drawn **once**, at about 47 % darkness, with no hotbar, chat or crosshair.
- **Time:** C1 and C2 freeze (GL:119-123 runs only in FOCUS_BATTLE). BATTLE music keeps playing.
- **Notes:** UTK:68 `battleOwnsToggles` keeps E, C, T and Q blocked under the pause.
- **Fixtures:** only checks that `openPauseMenu` is called (`FocusBattleScreenInputTest`, `ResultPanelTest`).
  The single-scrim menu itself is baselined over the fixture backdrop (the frozen battle frame behind it is not).
- **Fidelity:** `pause/pause-battle-offline_1920x1080_s1`

### death-menu
- **Kind:** dialog.
- **Owner:** `com.stonebreak.ui.DeathMenu` + `ui.deathMenu.SkijaDeathMenuRenderer`. Created at GS:81. **Never cleaned up** (not in GameShutdown).
- **Entry:** `GL.updateGameWorld` checks `player.isDead()` and calls `setVisible(true)` (GL:229-240).
  - The state stays PLAYING; capture is forced off.
  - It never triggers in states that skip `updateGameWorld`.
- **Input (existing):**
  - Left press on Respawn only (UMR:86-92, 159-173) → `player.respawn()`: client-local `DeathHandler.respawn` with no packet, then hide and recapture.
  - Hover via UMR. No keyboard.
- **Layout:** overlay 0xB4500000; "You Died!" at 96·s red with a +4 px shadow at centre − 100; one 360×50·s button at centre + 20·s (`DeathMenu.java:72-82`).
- **Time / sound:** none.
- **Fixtures:** none.

### statistics
- **Kind:** screen (modal over the world).
- **Owner:** `com.stonebreak.ui.statisticsScreen.StatisticsScreen` + `SkijaStatisticsRenderer`. Created at GS:79, cleanup at `GameShutdown.java:47`.
- **Entry:** pause menu → `GSC.openStatisticsScreen` (:269-276): hides the pause menu, sets state STATISTICS (paused).
- **Exits:** only the Back left press (UMR:102-109) → `closeStatisticsScreen` (pause menu back, PAUSED).
- **Input (existing):**
  - **Escape does nothing**: STATISTICS is not polled (MIR:194-220), so the statistics branch of UTK.pollEscape (:123-127) is unreachable.
  - Hover via UMR:148-151.
- **Draw:** world behind (FR default branch) with no HUD.
- **Bindings:** `player.getStats()`:
  - kills total, plus per-type rows for Cows, Sheep and Chickens only (goose kills are counted but have no row)
  - damage dealt
  - distances and air time
- **Layout:** panel 520×580·s, rows at panelY + 115 every 30·s. `String.format` uses the default locale, so separators vary by locale.
- **Fixtures:** none.

### glossary
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.glossaryScreen.GlossaryScreen` + `GlossaryLayout` + `SkijaGlossaryRenderer` (own MasonryUI). Created at GS:80, cleanup at `GameShutdown.java:50`.
- **Entry/exit:** mirrors statistics (GSC:286-301, state GLOSSARY). **Escape is dead here too.**
- **Input (existing):**
  - Left press (UMR:111-125): `handleClick` selects a sidebar row or cycles the variant (wrapping, per type, stored in an EnumMap), then Back.
  - **Hover never updates**: `GlossaryScreen.updateHover` (:116) has no caller.
- **Bindings:**
  - `EntityType.GLOSSARY_TYPES` = cow, sheep, chicken, goose.
  - `player.getEntityDiscoveries()` (seen variants, weakness), `PlayerStats.getKillsByType`.
  - Attributes are revealed after a kill; the weakness via Ranger Quarry study; plus special abilities.
- **Layout:** pure `GlossaryLayout`: panel ≤1100×700·s and ≤92 % of the window, 34 % sidebar, compressing rows, preview ≤250 px or 42 % (`GlossaryLayout.java:25-130`).
- **Time:** preview orbit and Idle clip on `getTotalTimeElapsed()`.
- **Custom draw:** raw-GL mob preview after `endFrame` (Hard visuals #1); hard `canvas.clipRect` panes; MStatRow bars; MBadge; MSymbol.
- **Fixtures:** `GlossaryLayoutTest` (7, geometry).

### inventory
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.inventoryScreen.InventoryScreen`, a facade over `core.{InventoryCraftingManager, InventorySlotManager, InventoryInputManager, InventoryController, InventoryLayoutCalculator}`, `handlers.InventoryDragDropHandler` and `renderers.InventoryRenderCoordinator`.
  - Built per world at GS:104-111 (skipped if the font or block texture array is missing).
  - `Inventory.setInventoryScreen` back-reference.
  - Never disposed: two MasonryUI per world load leak.
  - Closing returns held items (`InventoryController.java:38-43`).
- **Entry:** E (UTK:139-167) → INVENTORY_UI (unpaused). Blocked in battle, with chat open, with the workbench or recipe book visible, and in FURNACE_UI.
- **Exits:** E, Escape, C (to the character sheet), tab clicks. "Recipes" keeps the inventory visible underneath.
- **Input (existing):** polled once per frame in IH:115-118. UMR only swallows the event. Model is click-to-pick, click-to-place.
  - Left click, in priority (`InventoryInputManager.java:127-207`):
    - shift-click: output crafts all; crafting input back to inventory; main ↔ hotbar
    - holding a stack: output crafts one batch; double-click within 350 ms / 40 px (`currentTimeMillis`) gathers matching stacks; otherwise place
    - empty hand: tabs, Recipes, Craft All, Sort, pick up
  - Right click drops one; right-drag distributes one per slot.
  - Middle click balances the grid or sorts.
  - Scroll swallowed (IH:159-162). Q and T blocked. No number-key swap.
- **Assets:** font; item icons via `block-icon-renderer` and `item-icon-renderer` (SBO sprites with alpha, #330).
- **Bindings:** `Inventory`; `CharacterStats` (the left equipment/status/resistance column is placeholder).
- **Actions:** client-side `CraftingManager` craft / craftAll; sort; overflow `DropUtil.dropItemFromPlayer` → `sendDropItem(id,count)` (item state lost).
- **Layout:** `calculateThreeColumnLayout`: 180/10/centre/10/180 columns × uiScale.
  - `calculateLayout` prints a `System.err` warning on every call when the hotbar overflows (`InventoryLayoutCalculator.java:200-204`).
- **Draw (three phases, `InventoryRenderCoordinator.java:132-175`):**
  1. Skija: tabs, 0xBF6B6B6B panel, slots.
  2. GL: about 41 icons. Each Skija item icon opens its own frame and flush.
  3. Skija: counts.
- **Time:** `InventoryScreen.update` runs **twice per frame** in INVENTORY_UI (GL:155-158 and :184-187).
- **Fixtures:** `InventorySlotManagerTest` (33), `InventoryCraftingManagerTest`, `CraftingGridReturnTest`, `InventoryLayoutCalculatorTest`, `InventoryDragDropHandlerTest`. No input-manager, render or pick/place/render-agreement tests.
- **Notes:**
  - Placing a held stack on the side columns or tab strip **drops it into the world**: placement uses centre-column bounds (`InventoryDragDropHandler.java:41-66`).
  - Tab 0 is not hit-tested.
  - `handlers/InventoryMouseHandler.java` is dead.

### inventory-tooltip
- **Kind:** tooltip.
- **Owner:** `InventoryScreen.renderTooltipsOnly`, driven by `OR.renderInventoryTooltips` (OR:66-80). Gated off in RECIPE_BOOK_UI and WORKBENCH_UI.
- **Content:** `item.getName()` offset 15·s from the cursor (MTooltip, uiScale).
- **Fixtures:** none.

### dragged-item-layer
- **Kind:** overlay.
- **Owner:** `OR.renderDraggedItems` (OR:106-131). It calls `renderDraggedItemOnly` on the inventory (INVENTORY_UI only), the workbench and the furnace. The item is drawn at slotSize − 4 at the cursor, above all UI.
- **Custom draw:** 3D block icon with the `isDraggedItem` z-offset, which has no effect because depth is cleared per slot; or a Skija sprite.
- **Fixtures:** none.
- **Fidelity:** `furnace/furnace-lit_1920x1080_s1`
- **Migration (#298, in progress, furnace only):** the furnace document carries its stack itself: a `DrawProvider`
  (`stonebreak:item-icon`, `params {insetPx: 2, countMarginPx: 2}`) on the cursor layer (`-sb-anchor: pointer`), bound
  to `carried` and shown only while it holds something, so it paints above the panel, slots and tooltip in the same
  Masonry paint (legacy order kept). Its icon is `slotSize - 4` centred on the pointer, as the legacy overlay drew it.
  While it shows, hover tooltips stay hidden (engine rule, `UiDocumentInstance.pointerGhostShown`), the legacy
  "no tooltip while dragging". `FurnaceScreen.renderDraggedItemOnly` stands down while the document shows. The
  inventory and workbench keep the legacy overlay until #300.

### workbench
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.workbench.WorkbenchScreen` with `inventoryScreen.core.{WorkbenchController, WorkbenchInputManager}`, `handlers.WorkbenchDragDropHandler` and `renderers.WorkbenchRenderCoordinator`.
  - Per world (GS:115-122), never disposed.
- **Entry:** right-click a WORKBENCH block (`input/WorldMouseHandler.java:166-168`) → `GSC.openWorkbenchScreen` (:171-177). Requires PLAYING and not paused; sets WORKBENCH_UI (paused).
  - Binds `WorkbenchStateRegistry.getOrCreate(pos)` (persistent slots, issue #307).
- **Exits:** Escape → `handleCloseRequest`. Auto-abandons if the block breaks (`WorkbenchController.java:134-156`). Items left in the grid stay in the table.
- **Input (existing):** shift-click; output crafts; place; empty-hand click (inherited); right click single deposit; no right-drag.
  - **Polled twice per frame**: `MIR.pollInGame` (:228-231) whenever it is visible, plus IH:103-107 in WORKBENCH_UI. Safe today only because every handled branch consumes the press.
  - Invisible Sort, Craft All and tab hitboxes are still live. A tab click strands the workbench under CHARACTER_SHEET_UI.
- **Bindings/actions:** per-frame `encodeSlots` dirty check → `MultiplayerSession.sendWorkbenchSlots` with pre-echo reconciliation (`WorkbenchController.java:92-132`). Crafting is client-side.
- **Layout:** `InventoryLayoutCalculator.calculateWorkbenchLayout` (:271-337) × uiScale.
- **Draw:** only via `renderFullscreenMenus` (FR:408-413). Three phases, about 46 icons. **No hotbar or HUD in this state** (FR:372-374). The tooltip is drawn twice: in render, and again via OR:95-99.
- **Time:** update runs twice per frame (GL:134-141 + :189-192).
- **Fixtures:** `WorkbenchDragDropHandlerTest`, `blocks/workbench/*Test`, `PacketRoundTripTest`.
- **Notes:**
  - **Pickup hitboxes sit `slotPadding`·s px left of the drawn slots** (render :252,260 vs `InventorySlotManager` :29-58).
  - Quitting in WORKBENCH_UI loses the cursor stack.

### furnace
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.furnace.FurnaceScreen` with `core.{FurnaceController, FurnaceInputManager, FurnaceLayout}` and `renderers.FurnaceRenderCoordinator`.
  - Per world (GS:118), never disposed. Bound to `game.getFurnaceRegistry().getOrCreate(pos)`.
- **Entry:** right-click a FURNACE block (`WorldMouseHandler.java:168-171`) → `GSC.openFurnaceScreen` (:189-195). FURNACE_UI is **unpaused**, unlike the workbench.
  - Dev hook `-Dstonebreak.autofurnace.ui`.
- **Exits:** Escape only (UTK:105-108). No auto-close when the block breaks, on distance or on death.
- **Input (existing):** polled once (IH:108-113).
  - Left picks or places a whole stack; outside the panel drops it.
  - Shift-click on furnace slots only.
  - Right takes one; right-drag deposits into main inventory only.
  - The fuel slot needs burn time > 0; the output slot rejects drops.
- **Bindings:** server-owned timers via `BlockStateS2C`, shown with no interpolation.
  - Edits → per-frame `encodeSlots` → `sendFurnaceSlots` (`FurnaceController.java:81-114`).
  - The UI's "lit" test is `isCooking() || fuelRatio>0`, while the block's `isLit()` is `burnTimeRemaining>0`.
- **Layout:** radial slots from `FurnaceLayout.compute` (:43-82) × uiScale. Halos and strokes are absolute px.
- **Draw:** only via `renderFullscreenMenus`. Three phases with no outer Skija frame. Crucible art (Hard visuals #9). Tooltip in its own pass. **No hotbar**, but the hotbar-item tooltip leaks in (OR:66-79).
- **Time:** `System.nanoTime` since a static `ANIM_EPOCH` (renderer :40,448-450). Since #296: `LegacyUiClock.seconds()`
  (pinnable).
- **Fixtures:** `FurnaceLayoutTest`, `PacketRoundTripTest`, `BlockLightSourceTest`. `ui/fidelity/LegacyFurnaceBaselineTest`
  (#296): pixel baselines with empty slots (block icons are GL, hard visual 2), lit vs unlit isolation, pinned-clock
  repeatability, renderer rects = the geometry oracle. Legacy capture for the #298 gate:
  `ui.furnace.core.LegacyFurnaceCapture` (variants `unlit`, `lit`, `lit-hover-<slot>`). SBO item sprites in slots follow
  the #330 rule (alpha, shared image): compare them, do not exclude them or reproduce the old black fill.
- **Fidelity:** `furnace/furnace-unlit_1920x1080_s1` `furnace/furnace-unlit_1280x720_s0_75` `furnace/furnace-unlit_3840x2160_s2` `furnace/furnace-unlit_1921x1081_s1_25` `furnace/furnace-unlit_800x600_s2` `furnace/furnace-lit_1920x1080_s1` `furnace/furnace-lit_1280x720_s0_75` `furnace/furnace-lit_3840x2160_s2` `furnace/furnace-lit_1921x1081_s1_25` `furnace/furnace-lit-hover-main0_1920x1080_s1`
- **Performance:** per frame about 40 `new MItemSlot`, Paint/Path objects and `encodeSlots` string building.
- **Notes:**
  - Fixed before the migration: closing while carrying a stack from a furnace slot lost it (#320); shift-click into a
    full inventory deleted the stack (#319).
  - Fixed in #298: closing with a carried stack that only partly fits the inventory added part of it and then dropped
    the **whole** stack (duplication); now only the rest is dropped (`FurnaceInputManager.intoInventoryOrWorld`,
    `FurnaceAbandonTest`). A furnace whose block disappears while its screen is open is abandoned like the workbench:
    the screen detaches from the dead state first (no edit or snapshot can reach it), the carried stack goes to the
    inventory (else the world), and the screen closes (`FurnaceController.abandonBrokenFurnace`). Legacy had no
    auto-close and let the player keep taking items the server had already dropped.
- **Migration (#298, in progress):** shipped as `ui/documents/furnace.sbui` + shared component
  `ui/shared/stonebreak/ui/components/item_slot.omui`, authored over the Open Mason MCP in the project "Stonebreak Menus"
  (`UI/stonebreak/ui/screens/furnace.omui`, `UI/stonebreak/ui/components/item_slot.omui`). `FurnaceScreen` keeps the
  lifecycle (open at a block, Escape, FURNACE_UI, close puts the carried stack back) and every slot rule; its
  `Presentation` seam (`ui.furnace.FurnaceDocument`) opens the document as an `ownerPaints` screen painted where the
  legacy screen drew, with the legacy mouse poll, dragged-item overlay and renderer standing down while it shows.
  Rollback: `-Dstonebreak.ui.legacy=furnace` (or automatically when refused or a frame fails).
  - **Slots:** the `item_slot` component (reusable by inventory/workbench, #300) is an `ItemSlot` bound to a slot
    record (icon/count via `stonebreak:item-icon` with the legacy unscaled 3 px inset and 2 px count margin, tooltip =
    the record's name, selected-hotbar ring from `selected`). Its code-behind sends a press as
    `stonebreak:inventory.slot-press {slot, button, shift}` and a right-button sweep entering it as `slot-drag`; the
    screen's code-behind sends presses on the bare panel (`panel`, new address: a held stack goes back) or beyond it
    (`outside`), and every release (`slot-release`). So every click variant runs `FurnaceInputManager` at the slot.
  - **Crucible (hard visual 9):** host draw providers `stonebreak:furnace-crucible` (chutes + bowl, under the slots) and
    `stonebreak:furnace-progress` (rings, over them), painted by the same `CruciblePainter` as the legacy screen from
    `FurnaceLayout.around` the element's centre; `params` = the `furnace` record (contract 3 adds `cooking`, the legacy
    glow rule is `cooking || fuel > 0`). Skia only, so the editor preview draws them too.
  - **Layout:** constant authored geometry plus a code-behind `place()` that reproduces the legacy integer maths from
    `ui.metrics()` (tokens `round(K * s)`, truncating centring, the int-halved title band, the unscaled −20 px title
    offset), on a `-sb-pixel-grid: none` root; so it lands on the legacy pixels at every UI scale, not only the matrix.
  - Tooltips: the router's (`MTooltip`), shown at once (`FurnaceDocument.SETTINGS`, tooltip delay 0). Differences from
    legacy: the offset is `15 * uiScale` (legacy furnace: unscaled 15, legacy inventory: scaled); after a press the
    tooltip stays hidden until the pointer reaches another slot (router rule; legacy showed it again at once).
- **Gate:** `ui.fidelity.FurnaceDocumentGateTest` (all 10 committed cases at `FLOAT_EXACT` + `EXACT`: 0 pixels differ)
  and `ui.furnace.core.FurnaceDocumentInteractionTest` (a 19-step press/shift/right/middle/right-drag/panel/outside
  script: the same inventory, furnace slots, cursor stack and world drops after every step as the legacy pointer path,
  items conserved, and what the document shows equals the state). Live (`-Dstonebreak.autofurnace=3
  -Dstonebreak.autofurnace.ui=30`, rolled back vs not): the panels agree except icon pixels under count digits
  (atlas-rendered block icons vs direct GL) and the world behind the translucent panel.

### recipe-book
- **Kind:** overlay (full panel over the hotbar).
- **Owner:** `com.stonebreak.ui.recipeScreen.RecipeScreen` with `core.*`, `input.{RecipeBookInputHandler, PopupInputHandler, SearchInputHandler}`, `logic.*`, `state.*` and `renderers.RecipeRenderCoordinator`.
  - Per world (GS:124-128); `dispose` has no callers.
  - `onOpen` reloads recipes. All state resets on open and close.
- **Entry:** only the "Recipes" button in the inventory or workbench → `GSC.openRecipeBookScreen` (:221-235) → RECIPE_BOOK_UI (unpaused). No key opens it.
- **Exits:** Escape (first in the chain, UTK:93-97) → `previousGameState`. No close button.
- **Input (existing):**
  - Mouse **polled twice per frame** (`MIR.pollInGame` :228-229 + IH:98-101). Clicks on the search field or empty space are not consumed and run twice (idempotent today).
  - Click priority: variant Prev/Next → categories → search focus → grid cell. Hit-tests use the **previous frame's** bounds.
  - Scroll: IH stores it; the screen drains it with `getAndResetScrollY`.
  - Text: `IH.handleCharacterInput`/`handleKeyInput` (PRESS/REPEAT). Append, Backspace, Enter unfocuses. No caret movement, paste or limit.
  - **Keys leak while typing** when opened from the workbench: "t" opens chat, "q" drops an item, F3–F8 fire (F6 spawns a cow).
- **Assets:** `/ui/recipeScreen/Elm_UI.sbt` (MTextureRegistry, tiled 4×); block and item icons.
- **Bindings:** `CraftingManager` recipes, de-duplicated by output; categories All/Building/Tools/Food/Decorative. Read-only, no packets. Smelting recipes are not shown.
- **Layout:** **ignores uiScale**. Panel `min(960, sw−80) × min(700, sh−80)` (`core/PositionCalculator.java:25-33`).
- **Draw:**
  1. Phase A, Skija: 0xE6 backdrop, panes.
  2. Phase B, GL: block icons with a raw `glScissor` for the grid (renderer :563-584).
  3. Phase B2: all sprites in **one shared Skija frame** (the anti-aliasing corruption fix, :179-193).
  4. Phase C: counts.
- **Time:** caret on `currentTimeMillis` (`MSearchField.java:77-78`). `update(dt)` ignores dt.
- **Fixtures:** `ItemCategoryMapperTest`, `PositionCalculatorTest`, `RecipeFilterServiceTest`, `RecipeSearchServiceTest`, `RecipeBookStateTest`.
- **Performance:** re-filters and copies the full list every render.

### recipe-book-tooltip
- **Kind:** tooltip. Owner: `RecipeScreen.renderTooltipsOnly` via `OR.renderRecipeBookTooltips` (OR:85-90).
- **Fixtures:** none.

### character-sheet
- **Kind:** screen.
- **Owner:** `com.stonebreak.ui.characterScreen.CharacterScreen` with `CharacterController`, `CharacterRenderCoordinator` and `renderers.{ClassesTabRenderer, SkillsTabRenderer, FeatsTabRenderer}` (+ Overview).
  - Per world (GS:113), never disposed. The active tab survives reopen.
- **Entry:** C (UTK:170-207, from PLAYING or INVENTORY_UI; closes the inventory first); inventory tabs; `GSC.openCharacterTab`. CHARACTER_SHEET_UI is unpaused.
- **Exits:** C, Escape (UTK:117-120), the "Inventory" tab.
- **Input (existing):**
  - Clicks polled once (IH:119-124). Right click is used only by the Feats level filter.
  - Scroll via IH:167-171: `offset += y×20`, which is the inverse of the recipe book and not scaled.
- **Assets:** ability icons via AbilityIconCache (`/ui/abilities/...`); REMOTE_PLAYER SBE.
- **Bindings/actions:** local `CharacterStats` mutators: ability scores, `spendCpOnAbility`, `investSkillPoint`, `acquireFeat`. No network.
- **Layout:** `TabStripLayout` tabs + a 600·s px panel. Tab content assumes about 480 px, but the panel is 504.
- **Draw:** one Skija frame, then the Overview 3D player preview in raw GL (Hard visuals #1). The hotbar is drawn after it (FR:376-381).
- **Time:** orbit on `getTotalTimeElapsed()`.
- **Fixtures:** none.
- **Notes:** `ClassesTabRenderer` uses a fixed `MButton[5]`.
  - More than 5 abilities throws (:291).
  - Stale hitboxes stay live.
  - "Spend N CP" spends 1.
  - `acquireFeat` ignores the level requirement.

### hotbar
- **Kind:** hud.
- **Owner:** `com.stonebreak.rendering.UI.components.MHotbarRenderer` (own MasonryUI) + state holder `com.stonebreak.ui.HotbarScreen`.
  - The live instance is in `InventoryRenderCoordinator.java:115`; the state lives in `InventoryController.java:33`.
  - The workbench and furnace instances, and `FurnaceController`'s HotbarScreen, are dead.
  - `rendering/UI/components/HotbarRenderer.java` is an empty file.
- **Entry:**
  - Drawn in PLAYING and PAUSED (inventory closed), under the recipe book, and after the character sheet (FR:353-389).
  - **Not** in FURNACE_UI, WORKBENCH_UI, FOCUS_BATTLE or menus.
- **Input (existing):**
  - Number keys 1–9, polled while held, PLAYING only (`input/HotbarSelector.java:20-26`).
  - Scroll in PLAYING (IH:143-178): **wheel up = next slot**.
  - `HotbarSelector` keeps its own index, never synced from `Inventory`.
- **Assets:** font; SBO item icons via `MTextureRegistry.getForSboItem` (state-aware, `hotbar/HotbarSlotRenderer.java:65-87`), the #330 slot-sprite rule; blocks via `block-icon-renderer`; other legacy items via ItemIconRenderer or a purple swatch.
- **Bindings:** `Inventory.getHotbarSlots()` and the selected index.
- **Layout:** `ui/hotbar/core/HotbarLayoutCalculator.java:59-88`: centred, `y = sh − bgH − 50·s`, slots 40/5 × uiScale.
- **Draw:**
  1. Phase A, Skija: background (`stoneSurface` r8), slots, selected ring, hearts, bars, gauges, dodge.
  2. Phase B: GL block icons.
  3. Phase C: counts.
- **Time:** none for the bar itself.
- **Fixtures:** `HotbarScreenTest` (9), `HotbarLayoutCalculatorTest` (12). No render tests.
- **Performance:** per frame 9 `new MItemSlot` plus gauge panels.
- **Notes:** `ui/hotbar/styling/HotbarTheme` colours are vestigial; colours are hard-coded in `HotbarSlotRenderer.java:27-32`.

### hud-health-hearts
- **Kind:** hud. Owner: `rendering.UI.components.hotbar.HealthHeartsRenderer`, inside hotbar Phase A. Always shown with the hotbar.
- **Assets:** `/ui/HUD/Health Icon/SB_{Empty,Half,Full}_Health_Icon.sbt` via MTextureRegistry (:26-28).
- **Bindings:** `player.getHealth()/getMaxHealth()`; 2 HP per heart; sprite thresholds 0.75 / 0.25 (:33-58).
- **Layout:** size snapped to an integer multiple of the texture near 28 px, **not uiScaled**; fixed 38 px gap; multi-row with 40 % overlap (:20-100).
- **Fixtures:** none.

### hud-stamina-mana
- **Kind:** hud. Owner: `rendering.UI.components.hotbar.StaminaBarRenderer`.
- **Shown:** stamina when `maxStamina>0`; a mana bar for **Arcanist only** (:40-47).
- **Layout:** fixed 8 px tall, full hotbar width, 6 px above the hearts. Colours 0xDC50C850 and 0xDC3C78DC.
- **Fixtures:** none.

### hud-class-gauge
- **Kind:** hud.
- **Owner:** one `ClassGauge` per class (`RageGauge` Berserker, `QuarryGauge` Ranger, `ResonanceGauge` Arcanist, `DoubtGauge` Illusionist, `MomentumGauge` Rogue) drawn on a `GaugePanel`.
  - Only the gauge matching `getSelectedClassId()` draws (`MHotbarRenderer.java:131-136`).
  - Ability lines appear when `getSpentCp(key)>0`.
- **Assets:** `/ui/abilities/berserker/*.png` and `/ui/abilities/ranger/*.png` via AbilityIconCache.
- **Bindings:**
  - Berserker: rage tiers.
  - Ranger: quarry study pips, HP, reveal, snare, culling.
  - Arcanist: resonance pips and overload.
  - Illusionist: doubt.
  - Rogue: momentum pips and cooldowns.
- **Layout:** panel 190 px wide at `bgX + bgW + 12`, absolute px.
- **Performance:** `MomentumGauge` calls `String.format` every frame.
- **Fixtures:** none.

### hud-dodge-indicator
- **Kind:** hud. Owner: `rendering.UI.components.hotbar.DodgeIndicator`. Every class.
- **Layout:** 120 px panel left of the hotbar.
- **Bindings:** `player.getDodge().getCooldownProgress()`; label "Dodge" / "Dodge: Ready".
- **Fixtures:** none.

### hotbar-item-tooltip
- **Kind:** tooltip.
- **Owner:** `rendering.UI.components.hotbar.HotbarTooltipRenderer` + `ui.HotbarScreen`.
- **Entry:** `Inventory.setSelectedHotbarSlotIndex` when the index changes and the slot is non-empty (`items/Inventory.java:613-626`). On world load only for a BlockType (`core/Game.java:228-236`).
- **Time:** 1.5 s shown, then a 0.5 s fade (`HotbarTheme.java:70-71`), ticked by `InventoryScreen.update(dt)`.
  - **Freezes half-faded under PAUSED** (GL:142-153).
  - Runs at double speed when the inventory is visible (double update).
- **Layout:** uiScaled box above the slot, clamped to the screen.
- **Draw:** **drawn twice per PLAYING frame**, by `InventoryRenderCoordinator.java:243-244` and OR:75-79. Its translucent fill double-blends.
  - Also leaks into FURNACE_UI with no hotbar under it.
- **Fixtures:** `HotbarScreenTest` (alpha logic).

### crosshair
- **Kind:** hud.
- **Owner:** `com.stonebreak.rendering.UI.components.MCrosshairRenderer` (own MasonryUI), built in `UIRenderer.initializeSkijaRenderers` (`rendering/UI/UIRenderer.java:73-77`). Not disposed.
- **Entry:** PLAYING only, and only while the inventory, workbench and furnace are not visible (FR:337-351). Still shown with chat open.
- **Input:** none.
- **Bindings:** settings style (6 kinds), size 4–64, thickness 1–8, gap 0–16, opacity, RGB, outline (`config/Settings.java:39-48,350-379`).
  - Applied at startup by `core/bootstrap/CrosshairConfigurator` and on settings Apply (`SettingsManager.java:46-83`).
  - Outline thickness and colour exist only on the renderer and are not persisted.
- **Layout:** screen centre, absolute px, **not uiScaled**.
- **Time / sound / assets:** none.
- **Custom draw:** inverted DOT colours (Hard visuals #14). A Paint is allocated per primitive.
- **Fixtures:** `SettingsPersistenceTest` (fields only).

### chat
- **Kind:** hud (closed feed + open panel).
- **Owner:** `com.stonebreak.ui.chat.ChatSystem` (one, from `core/Game.java:152`) with `chatSystem.{ChatMessageManager, ChatInputHandler, ChatCursorState, TextWrapper, ChatCommandExecutor}`.
  - Renderer `com.stonebreak.ui.chat.SkijaChatRenderer`, owned and disposed by UIRenderer.
  - Cleared on MAIN_MENU.
- **Entry:** T opens it in PLAYING, INVENTORY_UI (inventory hidden) or RECIPE_BOOK_UI (UTK:210-231). Escape closes. Opening and closing calls `MouseCaptureManager.forceUpdate`.
- **Input (existing), while open chat owns everything:**
  - Polled input skipped (IH:79-83); keys, chars, scroll and mouse all route to chat (IH:229-255, 149-153; UMR:58-60).
  - Text: printable ASCII 32–126, max 256; Tab autocomplete with ghost text; Ctrl+V/C (`ChatInputHandler.java`).
  - Mouse, in priority: scrollbar → emoji button → picker → tabs → command buttons (fills `"/cmd "` without executing) (`input/ChatInputRouter.java:39-182`).
  - Enter: `/` → executor; in a world → `MultiplayerSession.submitChat`; otherwise a local echo.
- **Assets:** font; emoji (row `chat-emoji-picker`). `[id]` tokens render inline at `20·s−4`.
- **Bindings:** 100 live and 20 history lines, **wrapped by 60 characters, not pixels** (`TextWrapper.java:11`).
- **Layout:** `Layout.compute` × uiScale (`SkijaChatRenderer.java:76-128`): 40 % of the width, bottom-left. Closed lines start at `h−30`, unscaled.
- **Time:** message fade is 10 s with the last 2 s fading, on **`System.currentTimeMillis`** (`ChatMessage.java:23,42`), so it keeps fading under pause. Caret blinks every 1.0 s on game dt.
- **Sound:** none.
- **Draw:** one Skija bracket, skipped when closed and empty. Stone panel, folder tabs, scrollbar, input box with h-scroll and ghost text, a procedural smiley button.
- **Fixtures:** `ChatMessageManagerTest`, `ChatInputHandlerTest`, `ChatCursorStateTest`, `TextWrapperTest`. Nothing for the renderer, router or executor.
- **Notes:**
  - **The tab hit-test uses unscaled 70 px tabs** (`ChatInputRouter.java:138-160`), while the renderer draws `80·uiScale`. This is wrong even at scale 1.
  - Custom alpha is squared (`SkijaChatRenderer.java:716-722`).
  - Escape that closes chat may also open pause in the same frame (stale edge tracker, UTK:76-91; inferred, verify in-game).
  - Commands that drive UI: `/battle [leave]`, `/battletest [reset|leave]` (`ChatCommandExecutor.java:26-43`).

### chat-emoji-picker
- **Kind:** dialog (popup).
- **Owner:** `com.stonebreak.ui.chat.emoji.EmojiPickerRenderer` + `ChatEmojiSystem`. Recents and favourites are kept in memory only, not persisted.
- **Input (existing):** star → favourite; cell → insert token; **any other click, including the panel's own padding, closes it** (`isPickerClick` is unused).
- **Assets:**
  - `/ui/textChat/emojiPNGS/{Banana,Sword,Swords_Crossed,Rage,Rampage,Skull_Crusher}.png`
  - `/ui/textChat/emojiGIFS/Problem.gif`
  - Static caches `EmojiImageCache` and `GifAnimationCache`, never closed.
- **Layout:** fixed 148 px, **not uiScaled**. Rows never wrap, so the 4-cell General row overflows by about 6 px.
- **Time:** GIF frame chosen by `currentTimeMillis % total` (`GifAnimationCache.java:27-43`).
- **Fixtures:** `EmojiAssetsTest` (ids and resources only).

### debug-overlay
- **Kind:** overlay (F3).
- **Owner:** `com.stonebreak.ui.DebugOverlay` (`core/bootstrap/GameBootstrap.java:60`) with `ui.debug.{DebugInfoPanel, RamPanel, VramPanel, DebugDiagnostics, DebugFormat, GpuInfoProbe}`.
  - Cards share the `ui.debug.DebugPanel` base; `ui.debug.UiBudgetPanel` (#296) is the "UI Documents" card listing every live document's budget snapshot from `GameUiDiagnostics`.
  - Toggled by `GameDiagnostics.toggleDebugOverlay`.
  - Hidden on entering SETTINGS (GSC:114-119) and in `WorldLifecycle` (:85-89).
- **Entry:**
  - Rendered last in **every** state, including menus and FOCUS_BATTLE (FR:136, 453-464).
  - F3 is polled only in the in-game states via IH:92, not in battle, menus, or with chat open. It stays visible in a battle if it was on before.
- **F-keys (existing, `input/DebugKeyHandler.java:41-66`):**
  - F3 overlay
  - F4 leak analysis (console)
  - F5 perspective; Shift+F5 profiler
  - F6 spawn a cow
  - F7 save all, with chat feedback
  - F8 save diagnostics
- **Bindings:**
  - RAM and VRAM: MXBeans, `GpuMemoryTracker`, CEARL pressure, NVX/ATI.
  - Info panel: position, chunk, biome, noise fields, "Looking At" (its own 0.05-step ray, not the game raycast), FPS (60-frame mean), mesh, LOD, region and network stats.
  - "Path Visual: ON" is hard-coded.
- **Layout:** absolute px, 280 px columns. `MStatPanel` uses fixed font sizes. Not uiScaled.
- **Time:** wall-clock refresh: resource panels 250 ms, info panel 50 ms.
- **Fixtures:** none (`MPanelWidgetsRenderTest` covers the widget generically).
- **Performance:** a boxed `Float` FPS deque every frame.

### debug-world-wireframes
- **Kind:** custom-renderer.
- **Owner:** `ui.debug.MobPathWireframeDrawer` (via `DebugOverlay.renderWireframes`) → `Renderer.renderEntityWireframe` (`rendering/Renderer.java:730-737`); `rendering.UI.rendering.DebugRenderer` (`cleanup` never called); `rendering.emitters.SoundEmitterRenderer` (VBO leaked).
- **Draw:**
  - Behaviour-coloured SBE wireframes; route lines lifted 0.25; goal crosses; yellow emitter triangles billboarded toward the **player position**.
  - Drawn after all UI, depth-tested against leftover default-framebuffer depth, so it shows through hotbar slots (depth cleared there) and is masked by the pause curtain.
- **Fixtures:** none.

### world-damage-numbers
- **Kind:** hud (world-space).
- **Owner:** singleton `rendering.UI.components.DamageNumberRenderer`; backend set at `Renderer.java:150`.
- **Entry:** PLAYING only (FR:301-303, 327-329). Spawned in `mobs/entities/LivingEntityCombat.java:86-90` (predicted raw amount for network shadows) and :110-113 (authoritative).
- **Layout:** projected `proj×view`, culled when behind or off-screen, no depth test. Font `min(28+1.5·dmg,44)`, not uiScaled.
- **Time:** `update(Game.getDeltaTime())` called **from the render path** (FR:328), so numbers freeze outside PLAYING. Life 0.75 s, screen-pixel gravity 640 px/s².
- **Fixtures:** none.

### world-quarry-markers
- **Kind:** hud (world-space), Ranger only.
- **Owner:** singleton `QuarryMarkerRenderer`.
- **Draw:** marked-prey diamond + name + HP bar within vision range; snare square (orange while arming, green when armed) (:64-135).
- **Fixtures:** none.

### world-doubt-markers
- **Kind:** hud (world-space), Illusionist only.
- **Owner:** singleton `DoubtMarkerRenderer`.
- **Draw:** "Doubt n/max", red when Bewildered (:50-84).
- **Fixtures:** none.

### world-enemy-awareness
- **Kind:** hud (world-space).
- **Owner:** singleton `EnemyAwarenessRenderer`.
- **Draw:** "?" amber when SUSPICIOUS, "!" red when ALERTED, over entities of the **client** `Game.getEntityManager()`.
- **Notes:**
  - Network shadows never tick awareness, so this likely never shows for server mobs (two-world trap; `EntityManager.java:98-102`).
  - **Opens a Skija bracket every PLAYING frame** even with no glyphs (:52).
- **Fixtures:** none.

### world-player-nametags
- **Kind:** hud (world-space).
- **Owner:** singleton `PlayerNameTagRenderer`.
- **Draw:** `RemotePlayer` names within 48 blocks, 18 px white.
- **Entry:** gated by `Settings.playerNameTagsEnabled` (default true). Opens a bracket every PLAYING frame, even in singleplayer.
- **Fixtures:** none.

### stealth-hud
- **Kind:** hud (screen-space).
- **Owner:** singleton `StealthHudRenderer`.
- **Draw:** "STEALTH" / "ENTERING STEALTH…" at a fixed y=64 top-centre, plus a 160×8 "Re-entry" cooldown bar. The doc comment claims "under the crosshair", which is wrong.
- **Bindings:** `player.getStealth()`.
- **Fixtures:** none.

### world-revealed-outline
- **Kind:** custom-renderer (UI-like world marker drawn in the 3D pass).
- **Owner:** `rendering/gameWorld/effects/WorldEffectsRenderer.java:79,243-281`.
- **Draw:** violet wireframe boxes for entities REVEALED by an Illusionist, with depth off, so visible through walls.
- **Decision for #301:** whether this stays world-pass host code.
- **Fixtures:** none.

### effect-underwater-tint
- **Kind:** effect.
- **Owner:** `UnderwaterOverlayRenderer`, held by OR (`rendering.UI.components.OverlayRenderer`, OR:32-34). Updated **and** rendered in `OR.renderUnderwaterOverlay` (OR:138-148) at FR:158, before the HUD, in non-battle in-game states (PAUSED included).
- **Draw:** legacy immediate-mode GL (Hard visuals #10). RGB (0, 0.4, 0.8) at 0.3, fading 3 alpha/s, keyed on `player.isInWater()`.
- **Time:** `game.getDeltaTime()` from the render path.
- **Fixtures:** none.

### effect-dodge-vignette
- **Kind:** effect. Owner: `DodgeInvincibilityOverlay` (same path as underwater).
- **Draw:** warm-white edge band at 18 % of the min dimension, alpha 0.45, four gradient trapezoids, 10 alpha/s fade, keyed on `getDodge().isInvincible()`.
- **Fixtures:** none.

### effect-screen-droplets
- **Kind:** effect. Owner: `ScreenDropletOverlay` (same path).
- **Draw:** 2–4 `GL_POINTS` with `POINT_SMOOTH`, 10–28 px, life 0.8–1.4 s, sliding 6 % of the height per second, spawned on `justEnteredWaterThisFrame()`.
- **Notes:** random spawn. Since #296 the `Random` comes from `LegacyUiClock.random()`, seeded while the clock is
  pinned (`-Dstonebreak.ui.pinclock=<s>[:<seed>]`), so spawns repeat in fixture runs.
- **Fixtures:** none.

### effect-pause-depth-curtain
- **Kind:** effect (depth-only).
- **Owner:** `rendering.pipeline.DepthCurtainRenderer` via `UIRenderer.renderPauseMenuDepthCurtain` (:176) → `OpenGLQuadRenderer` (FR:434).
- **Draw:**
  - Four depth-only quads over a hard-coded 520×450 rectangle, using the window size captured at construction.
  - Runs after the menu, so it only masks F3 wireframes.
  - Creates and deletes 4 VAOs and 8 buffers per paused frame.
  - With a null player it returns leaving `glColorMask(false)` set (:178-188).
- **Notes:** the other four curtain methods are dead. Candidate for deletion, which needs an explicit decision.
- **Fixtures:** none.

### battle-hud
- **Kind:** screen (HUD shell).
- **Owner:** `com.stonebreak.ui.focusBattle.FocusBattleScreen` + `SkijaFocusBattleRenderer` (one MasonryUI).
  - Created at GS:93.
  - `bind(view,input,layout,host)` resets everything (`FocusBattleScreen.java:119-138`); `unbind()` (:141-155); `cleanup()` from `GameShutdown.java:53`.
  - Bound by `FocusBattle.bindScreen` (`battle/stage/FocusBattle.java:426-429`) after the swirl, or at once on retry.
  - Interfaces: `BattleView` (read), `BattleInput` (commands), `BattleScreenHost`.
- **Entry:** `/battle` → `FocusBattle.start`, then `afterFieldFrame` → FOCUS_BATTLE (FR:166, 132). Ends via `end()` (:511-545): unbind, camera and FOV restore, `suppressHeldUiToggleKeys`, `closeFocusBattle`.
- **Input (existing):**
  - MIR:248-266 delivers only REPEAT/RELEASE for keys whose PRESS it saw; an unhandled Escape PRESS → pause. The `battleKeysDown` clear is lazy (next non-battle key event).
  - Chars and scroll swallowed. Clicks and moves are guarded (`FocusBattle.guardHud`/`guardHudInput` :288-319); an exception ends the battle.
  - No polling.
  - Precedence inside the screen is in `handleKeyInput` (:231-260). Confirm = Enter, KP_Enter, Space, E; directions = WASD and arrows (:344-356).
- **Layout:**
  - `FocusBattleLayout.effectiveScale = min(uiScale×max(1,min(w/1280,h/720)), min(w/1000,h/560))`, floor 0.1, pixel-snapped (:73-79).
  - Layer order is in `paintHud` (`SkijaFocusBattleRenderer.java:137-176`).
  - World anchors use `viewProjection = proj×cinematic view`, allocated per frame (FR:187-188).
- **Time:** C1 via `screen.update(dt)` inside `FocusBattle.step` (:463). Render is pure.
- **Sound:** BATTLE music only, no SFX.
- **Fixtures:**
  - `FocusBattleScreenInputTest` (36)
  - `FocusBattleHudRenderTest`, `FlowHudRenderTest`, `FlowSnapshotGalleryTest`
  - `FocusBattleLayoutTest`, `HudAnimatorTest`, `BattleMenuStateTest`, `BattleHelpTextTest`
  - `battle/stage/FocusBattleGuardTest`

  These are CPU-raster `BattleRasterFixture` tests with **relative** assertions and no golden images. PNGs are written only with `-Dstonebreak.{hud,flow,timed}.snapshots`.
- **Untested:** `FrameGrab`, `FocusBattle` orchestration, `MIR.routeBattleKey`, the FR battle paths, `BattleAutoScript`.
- **Performance:**
  - `FocusBattleLayout` rects are recomputed and a `new MMenuList` is allocated per call.
  - `MPainter.hudFrame` speckle draws up to 1200 rects (`MPainter.java:165,241-266`), unscaled.

### battle-command-window
- **Kind:** hud.
- **Owner:** `ui.focusBattle.elements.CommandWindow` (two MMenuLists: root + Qi Arts submenu) + `BattleMenuState` cursor and target step.
- **Shown:** while `outcome==NONE`. Live vs veiled per `BattleHudRules.menuLive` (:19-24). `syncWindowOpen` resets the cursor on open/close edges, including from render.
- **Input (existing):**
  - Directions act on PRESS **and REPEAT**; confirm on PRESS only.
  - `needsTarget` commands (STRIKE, FLURRY, STUNNING_STRIKE, FOCUS_COMBO) enter the target step. Q cancels or opens pause at root.
  - Mouse: left press, rows first (`pointAtRow` uses rest rects without slide offsets); hover selects.
  - **In an unguarded parry, Space/E block while Enter selects** a command.
- **Bindings:** availability, focus ready/fraction, meditate charges, Qi. Dimming via `BattleHudRules.rowUsable`.
- **Actions:** `BattleInput.submit` / `confirmTarget`.
- **Time:** C1 (wake 0.15 s, submenu slide 0.10 s, cursor bob).
- **Fixtures:** `CommandWindowRenderTest`, `battle/CommandWindowTest`.

### battle-party-status
- **Kind:** hud.
- **Owner:** `ui.focusBattle.elements.PartyStatusWindow` (MGauge hp/atb/focus, MPipRow qi, MChipRow) + `StatusChips` (cached MBadges).
- **Bindings:** monk HP (red at ≤25 %), Qi, ATB, focus, statuses, queued surge. Chip timers show `ceil(remaining)` seconds on C2.
- **Time:** fills on C2; ghost trail, flashes and pip pops on C1. `DamageDealt`, `TurnReady` and `FocusFull` events.
- **Fixtures:** `PartyStatusWindowRenderTest`.

### battle-help-strip
- **Kind:** hud.
- **Owner:** `ui.focusBattle.elements.HelpStrip` (stateless) + `BattleHelpText.lineFor`.
- **Content:** precedence intro > result > prompt hints > menu line or unavailability reason.
- **Time:** cross-fade out 0.07 s / in 0.14 s on C1.
- **Notes:**
  - Avoids "(" and ")" because the font renders them like letters (`BattleHelpText.java:68`).
  - "Space" is hard-coded in `BLOCK_HINT`.
- **Fixtures:** `BattleHelpTextTest`, `EnemyPlateRenderTest`.

### battle-enemy-plate
- **Kind:** hud.
- **Owner:** `ui.focusBattle.elements.EnemyPlate` (MGauge HP, `MCastBar`, MChipRow).
- **Bindings:** without a telegraph, the archon ATB. With a telegraph, an elapsed/impact timeline, the parry window, and the label PARRY / BLOCK / CANCELLED (`cancelled` is reserved and always false).
- **Time:** fill and window marker on C2 (synced with the parry brackets via `TimedLayout.parryTimeline`); pulse and ghost on C1.
- **Fixtures:** `EnemyPlateRenderTest`, `MCastBarTest`.

### battle-mode-tag
- **Kind:** hud. Static `MBadge("ACTIVE")` (`SkijaFocusBattleRenderer.java:63-64`). Bound to nothing, no animation.
- **Fixtures:** `EnemyPlateRenderTest`.

### battle-action-banner
- **Kind:** overlay. Owner: `ui.focusBattle.elements.ActionBanner` (MBanner).
- **Shown:** on `ActionStarted`, held while the actor acts, suppressed in INTRO.
- **Time:** C1: drop 0.12 s, min hold 0.9 s, fade 0.25 s.
- **Fixtures:** `ActionBannerTest`, `MBannerTest`.

### battle-target-cursor
- **Kind:** overlay. Owner: `ui.focusBattle.elements.TargetCursor`.
- **Shown:** during targeting while the menu is live. `place()` is the single geometry source for paint and hit-test.
- **Layout:** `MWorldMarker.project` of the archon at 0.75 height through the last render's viewProjection. Pinned to the plate when off-screen or hidden by the HUD band.
- **Time:** bob `sin(t*7)` on C1.
- **Input:** a click on the body, hand or tag confirms the target.
- **Fixtures:** `FlowHudRenderTest`, `MWorldMarkerTest`.

### battle-floaters
- **Kind:** overlay. Owner: `ui.focusBattle.elements.BattleFloaters` (MFloatingText, cap 24).
- **Content:** damage, crit, BLOCK, PARRY!, heals, Qi, FOCUS MAX, statuses, rejection reasons.
- **Layout:**
  - **The screen position is locked at the first render after the event**, then moves in screen space only (`resolvePending` :209-225).
  - Pending floaters depend on render following the tick.
- **Time:** C1, deterministic lanes.
- **Fixtures:** `BattleFloatersTest`, `MFloatingTextTest`.

### battle-timing-ring
- **Kind:** overlay (Flurry prompt). Owner: `ui.focusBattle.timed.TimingRingOverlay` via `TimedInputLayers`.
- **Geometry is gameplay** (`TimedLayout.ringScaleAt` :77): radius is exactly 1.0 at the perfect midpoint; bands = press windows; anchored at the archon chest.
- **Time:** radius and bands on C2. The PERFECT/GOOD/MISS burst and judder `sin(age*70)` on C1. Slow-motion splits the two.
- **Input:** confirm PRESS or any left click → `pressConfirm`. The hint "SPACE Confirm" is hard-coded.
- **Fixtures:** `TimingRingOverlayTest`, `TimedLayoutTest`, `battle/timed/TimingRingTest`.
- **Shared draw helpers:** `ui.focusBattle.timed.TimedMotion` (normalised-time easing shapes, no clock) and `TimedStrokes`, used by this row, `battle-parry-overlay` and `battle-screen-fx`; they migrate with whichever of those moves first.

### battle-parry-overlay
- **Kind:** overlay. Owner: `ui.focusBattle.timed.ParryOverlay`, anchored on the monk torso.
- **Draw:** brackets shut exactly at `windowStart` (C2). Glow `sin(t*30)` on C1. PARRY full-screen flash 0.32 + ring 0.25 s; BLOCK shield 0.45 s; TOO EARLY 0.5 s.
- **Fixtures:** `ParryOverlayTest`, `battle/timed/ParryWindowTest`.

### battle-combo-strip
- **Kind:** overlay. Owner: `ui.focusBattle.timed.ComboStripOverlay` (MPromptStrip), fed from the `TimedInputState` snapshot.
- **Draw:** arrow symbols plus hard-coded W/A/S/D; stamps; an "n HITS" counter that counts Impacts; timer `1−stepElapsed/stepDuration` (C2).
- **Time:** slide, hold, FLAWLESS and stamp pops on C1.
- **Input:** direction PRESS only; mouse inert.
- **Fixtures:** `ComboStripOverlayTest`, `battle/timed/ComboStringTest`.

### battle-screen-fx
- **Kind:** effect. Owner: `ui.focusBattle.timed.ScreenFxLayer` + `MScreenFx`.
- **Draw:**
  - Stacked vignettes (low-HP heartbeat at 1–2.6 Hz, frost corner crystals as hand-drawn paths, focus aura) capped together.
  - Crit flash 0.18 s; defeat grey wash over 2.5 s; 11 % letterbox in INTRO and the victory hold.
- **Time:** C1.
- **Performance:** `Shader.makeRadialGradient` per call per frame (`MScreenFx.java:147`).
- **Fixtures:** `ScreenFxLayerTest`, `MScreenFxTest`.

### battle-encounter-card
- **Kind:** effect. Owner: `ui.focusBattle.elements.EncounterTransition`, topmost layer.
- **Shown:** armed when the first update sees INTRO; replays on retry.
- **Draw:** white flash 0.3 s, iris wipe 0.8 s; name card from 1.7 to 3.2 s (uppercase, letter-spaced, rule unfold, subtitle); "SPACE Skip" hint.
- **Time:** C1. The card is timed to the camera intro shot by **hard-coded constants only** (`CARD_IN_SECONDS=1.7`).
- **Fixtures:** `EncounterTransitionTest`.

### battle-encounter-swirl
- **Kind:** effect. Owner: `ui.focusBattle.intro.EncounterSwirl` (one per FocusBattle) + `FrameGrab`.
- **Entry:** `FocusBattle.afterFieldFrame` (:171-196) → `FrameGrab.backBuffer`: synchronous `glReadPixels` of GL_BACK, CPU flip, alpha forced to FF (`FrameGrab.java:21-45`) → `swirl.begin`.
- **Draw:** painted by `FocusBattle.renderEncounterTransition` (:331-347) in the HUD's MasonryUI frame.
  - Runtime SkSL effect with 10 taps per pixel, compiled lazily on the first paint (a hitch).
  - Fallback is nine rotated copies.
  - The world is still rendered underneath, fully covered.
- **Time:** C1, 1.6 s. The sim, camera and HUD binding wait.
- **Input:** the screen is unbound, so the MIR Escape fallback opens pause and the swirl freezes.
- **Fixtures:** `EncounterSwirlTest` (timeline, SkSL and null-still paths on CPU raster). `FrameGrab` is untested.

### battle-result-panel
- **Kind:** dialog. Owner: `ui.focusBattle.elements.ResultPanel` (MResultCard: stats plus Retry / Explore arena / Return to world).
- **Timing:** rises after 4.0 s (victory) or 2.0 s (defeat) over 0.45 s; interactive only at full rise; 1 s count-up after it seats; the Time stat from C2 `elapsedSeconds`. Input is gated on the C1 HUD clock (anti-mash).
  - The 4 s victory hold has **three independent C1 accumulators**: `HudAnimator.endAge`, `TimedInputState.victoryHold` and `ResultPanel.delayFor`.
- **Input (existing):** directions PRESS + REPEAT (wrapping); confirm PRESS; mouse via the static `GEOMETRY` card (`synchronized buttonAt`); Escape pauses.
- **Actions:** `retry`, `exploreArena` (free roam with the normal HUD), `returnToWorld` (+ `BattleTestSession.leave`).
- **Fixtures:** `ResultPanelTest`, `MResultCardTest`.

### skija-ui-backend
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend` + `SkiaContext`, built and initialised in `Renderer.java:137-146`.
  - Init failure is logged and the game continues, with no UI.
  - Disposed in Renderer cleanup together with `MTextureRegistry.disposeAll`.
- **Assets:** `Minecraft.ttf`, `Dirt.png`, `Stonebreak_Logo.png` (SUB:40-42).
- **Notes:**
  - Owns render-target selection: it always wraps framebuffer 0, which is the #286 split point.
  - `restoreGLDefaults` is the guard against the 2026-08-22 sampler-leak terrain-smear bug (SC:84-135); it must survive any port.
  - Skija is the subclass seam for the headless tests.
- **Performance:** a normal PLAYING frame does at least 5 sequential full flushes (crosshair, hotbar A, hotbar C, enemy awareness, name tags), about 10–13 when busy. Each resets 16 texture units.
- **Fixtures:** `RasterUiFixture`, `BattleRasterFixture` (CPU raster substitutes).

### masonry-ui-kit
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.UI.masonryUI.*`:
  - `MasonryUI` (per-screen facade + overlay queue)
  - `MPainter` (stoneSurface, panel, hudFrame noise)
  - `MStyle`, `MFonts`, `MGlyphs`, `MSymbol`
  - widgets: MButton, MDropdown, MSlider, MToggle, MTextField, MSearchField, MScrollContainer/MScrollMath, MTabBar, MCategoryButton, MItemSlot, MEquipSlot, MTooltip, MStatPanel, MStatRow, MVitalBar, MProgressBar, MSectionHeader, MIconButton, MBadge, MChipRow, MClipboard, MColor, MKeyHint, MPipRow, MGauge, MMenuList, MCastBar, MBanner, MFloatingText, MPromptStrip, MResultCard, MScreenFx, MWorldMarker
- **Game coupling (#286 decoupling list):**
  - `MWidget`, `MFonts` and `MTooltip` read `Settings.getUiScale()`.
  - `MTextureRegistry` reads ItemRegistry and SpriteVoxelizer.
  - Widgets import the `ui.startupIntro.tween` easing functions.
- **Battle-only widgets:** MScreenFx, MWorldMarker, MFloatingText, MCastBar, MResultCard, MPromptStrip, MBanner, MKeyHint, MPipRow, MChipRow, MMenuList, MGauge.
- **Fixtures:** 24 `M*Test` classes in `test/.../rendering/UI/masonryUI/` (render, raster and interaction).

### mtexture-registry
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry` / `MTexture`. A static ConcurrentHashMap that remembers failures; SBT → embedded OMT → composited Skija Image; state-aware SBO item keys.
- **Users:**
  - heart icons
  - hotbar SBO icons
  - character creation and recipe book `Elm_UI.sbt`
- **Fixtures:** none directly.

### block-icon-renderer
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.UI.menus.BlockIconRenderer`, built by `UIRenderer.initializeBlockIconRenderer` (`UIRenderer.java:60-65`). No cleanup.
- **Callers:** hotbar, inventory, workbench, furnace, recipe book, dragged item.
- **Draw:**
  - Per icon: save about 12 GL states, then viewport and scissor to the slot (Y-flip uses live `Game.getWindowHeight`), and clear depth.
  - World shader with `u_isUIElement`.
  - Ortho ±0.6; view rotate X 30°, Y −45°, scale 0.8.
  - Texture array on unit 1; SBO hand mesh or a cached cube.
  - Blending only for the three leaf blocks (hard-coded, not `BlockRenderTraits`).
- **Leaves behind:** the unit-1 binding, the depth function, and its matrix uniforms.
- **Performance:** about 500 `glGet` calls per frame with the inventory open.
- **Fixtures:** none.

### item-icon-renderer
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.UI.menus.ItemIconRenderer` (Skija), built in `UIRenderer.initializeSkijaRenderers`.
- **Draw:**
  - SBO items: the shared `MTextureRegistry.getForSboItem` image (state-aware), drawn with alpha (#330). It used to be a
    private `SpriteVoxelizer` raster created as `ColorAlphaType.OPAQUE`, which painted transparent pixels black.
  - Block items: the top face read back from the texture array.
  - Fallback swatches: green or purple.
  - **Each call opens its own Skija frame** (:86-98), so outside an outer frame that is a full flush per icon.
- **Fixtures:** none.

### entity-preview-renderer
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.models.entities.EntityRenderer#renderEntityPreview` (:186) and `#renderPlayerPreview` (:229, 242).
- **Callers:**
  - `SkijaCharacterCreationRenderer.java:246`
  - `CharacterRenderCoordinator.java:453`
  - `SkijaGlossaryRenderer.java:521`
  - (`rendering/models/entities/PlayerFigureRenderer.java:145` is a world use)
- **Draw:** each caller sets the scissor, viewport and orbit camera itself; the renderer supplies a UI lighting rig. There is no shared slot abstraction.
- **Fixtures:** none.

### tab-strip-layout
- **Kind:** custom-renderer (shared layout).
- **Owner:** `com.stonebreak.ui.TabStripLayout`: 5 tabs of 84×28 with a 4 px gap, × uiScale, centred on the screen (not the panel).
- **Users:** inventory drawing and hit-testing, character screen.
- **Fixtures:** none.

### ability-icon-cache
- **Kind:** custom-renderer (asset cache).
- **Owner:** `com.stonebreak.rpg.classes.AbilityIconCache`, a static HashMap of Skija Images that is never closed.
- **Notes:** `computeIfAbsent` with a null result means a missing icon is re-read and logged every frame.
- **Users:** GaugePanel, ClassAbilitiesTabRenderer, ClassesTabRenderer.
- **Fixtures:** none.

### opengl-quad-renderer
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.rendering.UI.components.OpenGLQuadRenderer` + `UIQuadRenderer` (one VAO, dynamic VBO).
- **Notes:**
  - `drawQuad`, `drawTexturedQuadUI` and `drawFlat2DItemInSlot` are effectively dead.
  - `setWindowDimensions` is never called (window size 0×0).
  - The only live use is passing through the depth curtain.
- **Fixtures:** none.

### legacy-stb-font
- **Kind:** custom-renderer.
- **Owner:** `com.stonebreak.ui.Font`: an STB-baked 512² alpha texture of Roboto, `rendering/core/ResourceManager.java:24`.
- **Notes:** passed into `InventoryScreen` (GS:105) and gating its construction (GS:104), but `drawText` has no caller. Allocated GPU memory with no visual. Retire it explicitly.
- **Fixtures:** none.

## Coverage method

Run from `stonebreak-game/src/main/java/com/stonebreak/` unless noted (zsh: quote globs).

1. File inventory: `find ui rendering/UI core -name '*.java' | xargs wc -l`.
2. Registries and state machine: read `core/GameState.java`, GS, GSC, `core/Game.java`.
3. What is actually drawn: read FR end to end for every GameState branch, then `rendering/Renderer.java` (UI construction at :129-178, `renderChat`/`renderOverlay` :505-530), `rendering/UI/UIRenderer.java` and OR.
4. Input: read MIR, IH, UMR, UTK, `input/ChatInputRouter.java`, `input/DebugKeyHandler.java`, `input/MouseCaptureManager.java` and `core/Main.java:106-189`.
5. Skija users outside the UI packages: `grep -rln "beginFrame(\|getCanvas()\|io.github.humbleui" --include='*.java' . | grep -v "^./ui/\|^./rendering/UI/"`. Real hits: `battle/stage/FocusBattle.java`, `rpg/classes/AbilityIconCache.java`. The rest are name collisions.
6. Every UI component's users: `grep -rlw <Class> --include='*.java' .` for each component (ScreenDropletOverlay, MScreenFx, MWorldMarker, MHotbarRenderer, HotbarScreen, …).
7. Scale: `grep -rc getUiScale --include='*.java' . | grep -v ':0$'`.
8. Controllers: `grep -rn "glfwGetGamepad\|glfwJoystick\|GLFW_GAMEPAD\|glfwGetJoystick" --include='*.java' .` (no hits at the pinned commit; since #288 only `ui/runtime/GamepadUiSource.java`, which serves documents).
9. NanoVG remnants: `grep -rln "nanovg\|NanoVG\|nvg" --include='*.java' .` (comments only).
10. 3D previews: `grep -rn "renderPlayerPreview\|renderEntityPreview" --include='*.java' .`.
11. World-pass UI-like drawing: grep `rendering/` for `highlight|outline|crack|selection|billboard|nametag|vignette|flash|fade|tint`.
12. Tests: `find stonebreak-game/src/test/java/com/stonebreak -name '*Test.java' | grep -iE "ui/|masonry|screen|hud|chat|menu|overlay|crosshair|hotbar|debug|focusBattle|battle|inventory|furnace|recipe|glossary|statistic|tooltip|intro|loading|settings|terrainMapper|worldSelect|character"`. Plus fixture search: `find . \( -iname '*golden*' -o -iname '*snapshot*' -o -iname '*fixture*' \)`.
13. Four parallel read-only research passes (shell menus, in-world screens, HUD/overlays, Focus battle). Their claims were spot-checked against source before inclusion:
    - FrameGrab
    - `FocusBattle.step`
    - loading-screen font leak
    - MainMenu level-triggered keys
    - furnace `close()`
    - depth curtain null-player path
    - chat tab hit-test
    - immediate-mode overlays
    - awareness bracket
    - chat wall-clock fade

## Known gaps

| gap | suggested owner |
|---|---|
| No golden images anywhere. The battle raster tests are relative, so a pixel-different migration passes. No resolution × DPI × uiScale fixture set exists for any non-battle screen. **#296: pause and furnace baselined** (`ui/fidelity/`, standard viewports × variants, pinned font/clock/seed); other screens get theirs when their wave starts. DPI stays 1 (no DPI handling exists). | #296 → each wave |
| ~~Online 6-button pause layout has no test seam~~ **Done (#296):** `ui.UiOnlineState.override`. | #297 (+#296) |
| Field pause drawn twice (≈72 % scrim) vs battle pause once (≈47 %); Resume never highlights. Decide preserve vs fix and record it. | #297 |
| Raw-GL 3D previews (character creation, character sheet, glossary) and 3D block icons need a render-provider or viewport-hole contract in the document model. | #286, #289 |
| Battle timing contracts: C1/C2 split, WYSIWYG grading, identity-deduped events, render-locked floater births, three 4 s accumulators, card-to-camera constants. These need explicit Lua and animation semantics before the battle migrates. | #292, #295, #301 |
| Escape is dead in STATISTICS and GLOSSARY (not polled); glossary hover never fires; character-creation keyboard dead; level-triggered keys in the main menu, settings and multiplayer; Esc cascade Host/Join → main menu; Esc-closes-chat-also-pauses (inferred). Existing behaviour must be recorded and preserved or consciously changed. | #288 |
| Double-polled input (workbench and recipe book) and double `update` (inventory, workbench). The new router's event ordering is defined in [ui-input.md](ui-input.md) §2–3 (#288); each screen's legacy quirks are still recorded and preserved or consciously changed when it migrates. | #297–#301 |
| Hit-test/render mismatches: chat tabs (unscaled 70 px vs 80·s); workbench pickup offset by slotPadding; invisible workbench buttons and tabs; inventory side-column drop; scrolled-out buttons in character tabs and settings. | #300, #299, #301 |
| ~~Item sprites opaque in screens, alpha in the hotbar~~ **Decided (#330):** alpha everywhere from the shared `MTextureRegistry` image; the legacy `ItemIconRenderer` now follows it, so gates compare sprite rects normally. | #298, #300 |
| Item-loss bugs (furnace close while dragging, furnace shift-click into full inventory, workbench cursor stack on quit); server accepts furnace snapshots without conservation. File as separate bugs; migration must not "fix" them silently. | #298, #300 (+ new Mortar bugs) |
| Mixed scaling: hearts, gauges, crosshair, stealth HUD, world markers, F3, emoji picker, recipe book, character creation, terrain mapper, loading and multiplayer ignore uiScale; no DPI awareness. Define layout units. | #287 |
| Clock zoo: `nanoTime` epoch (furnace), `currentTimeMillis` (chat fade, GIF, carets, splash, world-select card), `getTotalTimeElapsed` (previews, sparkles), render-path dt (damage numbers, overlays), fixed 1/60 (settings scroll), frame dt. Define a UI time source. **#296:** the presentation wall-clock reads (furnace, chat fade, GIF, carets, splash pulse and pick) go through the pinnable `ui.LegacyUiClock`; the dt-driven ones (`getTotalTimeElapsed`, render-path dt) and input/safety timing (double-click, search debounce, world-select card delays, UI-scale auto-revert) do not. Documents use #295 `UiClocks`. | #295, #296 |
| Legacy immediate-mode GL effects (underwater, dodge, droplets) need a compatibility profile; droplets used unseeded randomness (**#296: seeded through `LegacyUiClock`**). | #286, #301 |
| Lifecycle leaks: no shell or per-world screen disposed except four; loading screen leaks 9 Fonts per frame; AbilityIconCache, emoji caches and SoundEmitterRenderer VBO never released. Define instance and dispose rules. | #289, #302 |
| Dead or vestigial code: world-select create dialog, loading error panel, `HotbarRenderer.java`, workbench/furnace hotbar renderers, `InventoryMouseHandler`, OpenGLQuadRenderer API, four depth-curtain methods, `ui.Font`, `WorldSelectConfig`, `HotbarTheme` colours. Decide migrate vs delete per item. | #299, #300, #301 |
| World-pass UI-like markers (Illusionist revealed outline) and the block crack overlay (judged world geometry, out of scope). Confirm the ownership boundary. | #301 |
| Shared `ui.startupIntro.tween` easing package used by MasonryUI and the battle camera must move before the Masonry extraction. | #286 |
| No controller/gamepad support and no keybinding system exist for legacy screens; hard-coded "SPACE" and "W/A/S/D" hints. #288 added controllers, `UiActionMap` bindings and remappable `actionHints` for documents only; a migrated screen gains them, and that is an addition, not a regression baseline. | #297–#301 |
| Representative-state screenshots at each supported resolution/DPI/scale with pinned fonts, time and seed were **not captured** in this pass (ledger only). **#296:** pause and furnace captured as committed baselines; live back-buffer shots via `-Dstonebreak.autopause` + `-Dstonebreak.autoscreenshot` + `-Dstonebreak.ui.pinclock` (world behind is not pinned). | #296 → each wave |
