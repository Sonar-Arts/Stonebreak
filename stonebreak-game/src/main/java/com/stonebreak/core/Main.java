package com.stonebreak.core;

import org.lwjgl.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openmason.engine.diagnostics.MemoryProfiler;
import com.openmason.engine.loop.FramePacing;
import com.openmason.engine.platform.input.InputChain;
import com.openmason.engine.platform.loop.FrameLoop;
import com.openmason.engine.platform.window.BackendPolicy;
import com.openmason.engine.platform.window.DisplayBackend;
import com.openmason.engine.platform.window.Window;
import com.openmason.engine.platform.window.WindowSpec;
import com.stonebreak.config.Settings;
import com.stonebreak.core.render.FrameRenderer;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MenuInputRouter;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.vram.CearlBootstrap;
import com.stonebreak.world.World;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_CCW;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFrontFace;

/**
 * Entry point for Stonebreak: owns the application lifecycle — start up, run the frame loop, shut
 * down — and nothing else.
 *
 * <p>The pieces it drives each own one concern. The engine's platform layer (#96) picks and starts
 * the GLFW backend ({@link DisplayBackend}, native Wayland unless the NVIDIA driver is blocklisted),
 * owns the window and its GL context ({@link Window}) and runs the frame loop ({@link FrameLoop});
 * {@link MenuInputRouter} decides which screen an input event belongs to, and {@link FrameRenderer}
 * draws the frame.</p>
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String WINDOW_TITLE = "Stonebreak";

    /** Largest frame delta the game simulates; a longer stall arrives as one 0.1 s step. */
    private static final float MAX_FRAME_DELTA = 0.1f;

    /** Dev: {@code -Dstonebreak.uncapped=true} lifts every frame cap for this session (benchmarks). */
    private static final boolean UNCAPPED = Boolean.getBoolean("stonebreak.uncapped");

    /** Static handle for the few systems that need to reach the live window (settings, resolution). */
    private static Main instance;

    private Window window;
    private FrameLoop frameLoop;
    private MenuInputRouter inputRouter;
    private FrameRenderer frameRenderer;

    private Renderer renderer;
    private InputHandler inputHandler;

    public static void main(String[] args) {
        GcEnforcement.enforce();
        new Main().run();
    }

    private void run() {
        instance = this;
        logger.info("Starting Stonebreak with LWJGL {}", Version.getVersion());

        try {
            init();
            loop();
        } finally {
            cleanup();
            logger.debug("Stonebreak shutdown complete.");
        }
        System.exit(0);
    }

    // ─── Startup ──────────────────────────────────────────────────────────────

    private void init() {
        Settings settings = Settings.getInstance();
        logger.info("Settings loaded - Window size: {}x{}", settings.getWindowWidth(), settings.getWindowHeight());

        DisplayBackend.initialize(BackendPolicy.AUTO_WAYLAND);

        // GL 3.2 compatibility profile; swap interval 0 always: VSync is the limiter's monitor-rate
        // cap (see frameBudgetNanos), never a driver block.
        window = new Window(WindowSpec.builder(WINDOW_TITLE, settings.getWindowWidth(), settings.getWindowHeight())
                .gl(3, 2, WindowSpec.GlProfile.COMPAT)
                .waylandAppId("stonebreak")
                .swapInterval(0)
                .build());
        window.addResizeListener(this::onFramebufferResized);
        window.setCloseHandler(() -> {
            logger.debug("Window close requested - initiating shutdown...");
            if (frameLoop != null) {
                frameLoop.stop();
            }
        });
        inputRouter = new MenuInputRouter(window);
        frameRenderer = new FrameRenderer(window);

        window.create();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
        logVsyncSetting();
        installCallbacks();
        // Compile the CEARL program and install its VRAM plan BEFORE any
        // renderer exists: region arenas and the staging ring read the plan
        // when they are created. Needs the GL context (VRAM detection).
        CearlBootstrap.install();
        initializeGameComponents();
    }

    /** Keeps the projection matrix and the Game singleton's cached dimensions on the real size. */
    private void onFramebufferResized(int width, int height) {
        if (renderer != null) {
            renderer.updateProjectionMatrix(width, height);
        }
        Game.getInstance().setWindowDimensions(width, height);
    }

    /**
     * Joins the window's input chain. Each method only adapts the event: which screen it reaches is
     * {@link MenuInputRouter}'s decision, not this class's. Window size, framebuffer, position and
     * close callbacks are the engine window's own.
     */
    private void installCallbacks() {
        // UI documents see letter keys by their layout label (Ctrl+Z on QWERTZ is the key labelled Z).
        com.stonebreak.ui.runtime.GameUiInput.get().setKeyTranslator(key -> Window.layoutKey(key, 0));
        window.input().add(new InputChain.Listener() {
            @Override
            public void key(int key, int scancode, int action, int mods) {
                inputRouter.onKey(key, action, mods);
            }

            @Override
            public void character(int codePoint) {
                inputRouter.onCharacter(codePoint);
            }

            @Override
            public void mouseButton(int button, int action, int mods) {
                inputRouter.onMouseButton(button, action, mods);
            }

            @Override
            public void cursor(double x, double y) {
                inputRouter.onMouseMove(x, y);
            }

            @Override
            public void scroll(double xOffset, double yOffset) {
                inputRouter.onScroll(xOffset, yOffset);
            }

            @Override
            public void focus(boolean focused) {
                if (!focused) {
                    // Releases made while unfocused never arrive: end UI drags, captures and held keys now (#288).
                    com.stonebreak.ui.runtime.GameUiInput.get().windowFocusLost();
                }
                var mouseCapture = Game.getInstance().getMouseCaptureManager();
                if (mouseCapture == null) {
                    return;
                }
                if (focused) {
                    mouseCapture.updateCaptureState();
                } else {
                    mouseCapture.temporaryRelease();
                }
            }
        });
    }

    /**
     * The frame budget: the lowest of the monitor rate (VSync on) and Max FPS (unless Unlimited);
     * 0 = no sleep. VSync here caps at the monitor's refresh rate with the limiter instead of the
     * driver's swap-interval 1, which halves the rate whenever a frame misses vblank.
     */
    private static long frameBudgetNanos(Window w) {
        if (UNCAPPED) {
            return 0L; // dev: -Dstonebreak.uncapped=true (benchmarks), never saved
        }
        Settings settings = Settings.getInstance();
        return FramePacing.budgetNanos(settings.isVsyncEnabled(), w.monitorRefreshHz(),
                settings.isMaxFpsUnlimited() ? 0 : settings.getMaxFps());
    }

    private void logVsyncSetting() {
        Settings settings = Settings.getInstance();
        String fpsCap = settings.isMaxFpsUnlimited() ? "unlimited" : settings.getMaxFps() + " FPS";
        logger.info("[Display] VSync {}, Max FPS {}",
                settings.isVsyncEnabled() ? "enabled (cap " + window.monitorRefreshHz() + " Hz)" : "disabled",
                fpsCap);
    }

    private void initializeGameComponents() {
        MemoryProfiler profiler = MemoryProfiler.getInstance();
        profiler.takeSnapshot("before_initialization");

        renderer = new Renderer(window.width(), window.height());
        profiler.takeSnapshot("after_renderer_init");

        inputHandler = new InputHandler(window.handle());
        inputRouter.setInputHandler(inputHandler);

        BlockTextureArray textureAtlas = renderer.getBlockTextureArray();
        Game.getInstance().initCoreComponents(renderer, textureAtlas, inputHandler, window);
        Game.getInstance().setWindowDimensions(window.width(), window.height());
        profiler.takeSnapshot("after_game_init");

        Game.logDetailedMemoryInfo("Core game components initialized - no world created");
        profiler.compareSnapshots("before_initialization", "after_game_init");
    }

    // ─── Frame loop ───────────────────────────────────────────────────────────

    /**
     * The engine frame loop: poll, {@code Game.update(dt)} with dt clamped to 0.1 s, render, swap,
     * then sleep to the frame budget. Dev automation runs after the update and before the swap.
     */
    private void loop() {
        glClearColor(0.5f, 0.8f, 1.0f, 0.0f);
        glEnable(GL_DEPTH_TEST);

        frameLoop = new FrameLoop(window, MAX_FRAME_DELTA, Main::frameBudgetNanos);
        frameLoop.run(new FrameLoop.Frame() {
            @Override
            public void beforePoll() {
                if (inputHandler != null) {
                    inputHandler.prepareForNewFrame();
                }
            }

            @Override
            public void update(float dt) {
                Game.getInstance().update(dt);
            }

            @Override
            public void afterUpdate() {
                maybeAutoStartWorld();
                maybeAutoView();
                com.stonebreak.core.dev.SkyCapture.update();
                maybeAutoPause();
                Game.displayDebugInfo();
                inputRouter.pollActiveScreen();
            }

            @Override
            public void render() {
                frameRenderer.renderFrame();
            }

            @Override
            public void beforeSwap() {
                com.stonebreak.core.render.Screenshots.captureIfRequested(window.width(), window.height());
                maybeAutoFurnace();
                maybeAutoScreen();
                maybeAutoTorch();
                maybeAutoFx();
                maybeAutoBattle();
                maybeAutoScreenshot();
                if (com.stonebreak.core.dev.SkyCapture.shoot(window.width(), window.height())) {
                    requestClose();
                }
                maybeAutoResume();
                maybeAutoFly();
                maybeAutoChat();
            }
        });
    }

    // ─── Dev: -Dstonebreak.autoworld=<name>[:<seed>] ─────────────────────────

    private boolean autoWorldDone;
    private int autoWorldMenuFrames;

    /**
     * Development shortcut: once the main menu is up, start (or load) the named
     * singleplayer world exactly as the world-select screen would — the same
     * {@code MultiplayerSession.startSingleplayer} path, integrated server and
     * all — so rendering changes can be eyeballed from one command line
     * without clicking through menus. Inert unless the property is set.
     */
    private void maybeAutoStartWorld() {
        if (autoWorldDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autoworld");
        if (spec == null || spec.isBlank()) {
            autoWorldDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.MAIN_MENU) {
            return;
        }
        if (++autoWorldMenuFrames < 10) {
            return; // let the menu settle for a few frames first
        }
        autoWorldDone = true;
        AutoWorldSpec world = AutoWorldSpec.parse(spec, System.getProperty("stonebreak.autoscale"));
        String name = world.name();
        long seed = world.seed();
        if (world.badSeed()) {
            System.err.println("[autoworld] bad seed in " + spec + " — using " + seed);
        }
        // -Dstonebreak.vrd=<blocks>: vertical render distance for this session only (never saved).
        String vrd = System.getProperty("stonebreak.vrd");
        if (vrd != null && !vrd.isBlank()) {
            com.stonebreak.config.Settings.getInstance().setVerticalRenderDistance(Integer.parseInt(vrd.trim()));
            System.out.println("[autoworld] vertical render distance "
                + com.stonebreak.config.Settings.getInstance().getVerticalRenderDistance() + " blocks");
        }
        // A new world is written here with the requested terrain scale (the server only ever creates
        // default-scale worlds itself); an existing world keeps the scale it was created with.
        // -Dstonebreak.autogen=<standard|diffusion>: the generator of a world the hook creates (default Standard).
        com.stonebreak.world.generation.TerrainGeneratorType gen = null;
        String autogen = System.getProperty("stonebreak.autogen");
        if (autogen != null && !autogen.isBlank()) {
            try {
                gen = com.stonebreak.world.generation.TerrainGeneratorType.valueOf(autogen.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                System.err.println("[autoworld] unknown generator " + autogen + " (standard | diffusion)");
            }
        }
        // -Dstonebreak.autoclimate=<belts|zones|latitude>: the EXP-2 climate layout of a world the hook creates
        // (default belts; part of the world's identity, so an existing world keeps its own).
        com.stonebreak.world.generation.ClimateLayout climate = null;
        String autoclimate = System.getProperty("stonebreak.autoclimate");
        if (autoclimate != null && !autoclimate.isBlank()) {
            climate = com.stonebreak.world.generation.ClimateLayout.parse(autoclimate);
            if (climate == null) {
                System.err.println("[autoworld] unknown climate layout " + autoclimate + " (belts | zones | latitude)");
            }
        }
        // -Dstonebreak.autocurve=<1|2> (NewWorlds): the height curve of a DaedalusTGM-Exp world the hook creates; it
        // means nothing for Standard worlds (review-java LOW 4: alone it used to be ignored silently).
        String autocurve = System.getProperty("stonebreak.autocurve");
        boolean curveSet = autocurve != null && !autocurve.isBlank();
        if (curveSet && gen != com.stonebreak.world.generation.TerrainGeneratorType.DIFFUSION
                && !com.stonebreak.core.world.NewWorlds.exists(name)) {
            System.err.println("[autoworld] autocurve=" + autocurve + " only applies to DaedalusTGM-Exp worlds; add "
                + "-Dstonebreak.autogen=diffusion (this world is created " + (gen != null ? gen : "STANDARD") + ")");
        }
        if (!com.stonebreak.core.world.NewWorlds.exists(name)) {
            if (world.scale() != null || gen != null || climate != null || curveSet) {
                try {
                    com.stonebreak.core.world.NewWorlds.write(new com.stonebreak.core.world.NewWorlds.Options(name, seed,
                        gen, world.scale(), climate, null, false));
                } catch (java.io.IOException e) {
                    System.err.println("[autoworld] could not write the new world: " + e.getMessage());
                }
            }
        } else if (world.scale() != null || climate != null || gen != null || curveSet) {
            var existing = com.stonebreak.core.world.NewWorlds.read(name);
            if (existing != null && gen != null && existing.getGeneratorType() != gen) {
                System.err.println("[autoworld] '" + name + "' exists with generator " + existing.getGeneratorType()
                    + "; ignoring autogen=" + gen + " (pick a fresh world name)");
            }
            var curve = curveSet ? com.stonebreak.world.generation.HeightCurveVersion.parse(autocurve, null) : null;
            if (existing != null && curve != null && existing.getHeightCurve() != curve) {
                System.err.println("[autoworld] '" + name + "' exists with height curve v"
                    + existing.getHeightCurve().number() + "; ignoring autocurve=" + autocurve + " (pick a fresh world name)");
            }
            if (existing != null && world.scale() != null && existing.getTerrainScale() != world.scale()) {
                System.err.println("[autoworld] '" + name + "' exists at terrain scale "
                    + com.stonebreak.config.TerrainScaleOption.label(existing.getTerrainScale())
                    + "; ignoring scale=" + com.stonebreak.config.TerrainScaleOption.label(world.scale())
                    + " (pick a fresh world name)");
            }
            if (existing != null && climate != null && existing.getClimateLayout() != climate) {
                System.err.println("[autoworld] '" + name + "' exists with climate layout "
                    + existing.getClimateLayout().label() + "; ignoring autoclimate=" + climate.label()
                    + " (pick a fresh world name)");
            }
        }
        // -Dstonebreak.tgmpipe.model names the model of NEW worlds: a saved Daedalus world keeps the one it was made with
        // (review-python HIGH-1) unless -Dstonebreak.tgmpipe.forceModel=true.
        String flagModel = System.getProperty("stonebreak.tgmpipe.model");
        if (flagModel != null && !flagModel.isBlank() && !Boolean.getBoolean("stonebreak.tgmpipe.forceModel")) {
            var existing = com.stonebreak.core.world.NewWorlds.read(name);
            if (existing != null && existing.getGeneratorType() == com.stonebreak.world.generation.TerrainGeneratorType.DIFFUSION
                    && !com.stonebreak.world.generation.diffusion.tgmpipe.TerrainModels.refOf(flagModel)
                        .equals(existing.getTerrainModel())) {
                System.err.println("[autoworld] '" + name + "' was made with terrain model "
                    + existing.getTerrainModel().path() + " and keeps it; -Dstonebreak.tgmpipe.model=" + flagModel
                    + " applies to new worlds (pick a fresh world name, or add -Dstonebreak.tgmpipe.forceModel=true)");
            }
        }
        if (climate != null) {
            System.out.println("[autoworld] climate layout " + climate.label());
        }
        if (world.scale() != null) {
            System.out.println("[autoworld] terrain scale " + com.stonebreak.config.TerrainScaleOption.describe(world.scale()));
        }
        // -Dstonebreak.autorender=<chunks>: render distance for this session only (never saved),
        // so join timing can be measured at a fixed view (issue #345).
        String render = System.getProperty("stonebreak.autorender");
        if (render != null && !render.isBlank()) {
            com.stonebreak.config.Settings.getInstance().setRenderDistance(Integer.parseInt(render.trim()));
            System.out.println("[autoworld] render distance "
                + com.stonebreak.config.Settings.getInstance().getRenderDistance());
        }
        System.out.println("[autoworld] starting singleplayer world '" + name + "' seed " + seed);
        com.stonebreak.network.MultiplayerSession.startSingleplayer(name, seed);
    }

    // ─── Dev: -Dstonebreak.autoview=<x>:<y|top[+n]>:<z>:<yaw>:<pitch>[:<bob>[:<period>]] ─
    //          -Dstonebreak.autolod=<LOW|MEDIUM|HIGH|ULTRA>[:<lodDistance>]
    //          -Dstonebreak.autotime=<absolute ticks> (day × 24000 + tick: picks the moon's phase too)
    //          -Dstonebreak.autoweather=<clear|cloudy|rain|storm|auto> (the whole world, as /weather <mode> world)

    private boolean autoViewArmed;
    private long autoViewStartNanos = -1;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: once the
     * world is entered, holds the player as a spectator at a fixed point with a
     * fixed camera, so distant-terrain (FastLOD) changes can be screenshotted
     * from the same viewpoint run after run. {@code stonebreak.autolod}
     * overrides the LOD quality preset (and optionally the LOD distance) for
     * this session only — nothing is saved. An optional {@code bob} sways the
     * camera that many blocks up and down every {@code period} seconds (default
     * 3), to reproduce artifacts that only show while the camera moves. Inert
     * unless a property is set.
     */
    private void maybeAutoView() {
        if (Game.getInstance().getState() != GameState.PLAYING) {
            return;
        }
        if (!autoViewArmed) {
            autoViewArmed = true;
            String time = System.getProperty("stonebreak.autotime");
            if (time != null && !time.isBlank()) {
                long ticks = Long.parseLong(time.trim());
                // The integrated server owns the clock, so set it there as /timeset does.
                com.stonebreak.network.MultiplayerSession.requestServerTimeSet(ticks);
                if (Game.getTimeOfDay() != null) Game.getTimeOfDay().setTicks(ticks);
                System.out.println("[autoview] time set to " + ticks);
            }
            String weather = System.getProperty("stonebreak.autoweather");
            if (weather != null && !weather.isBlank()) {
                var mode = com.stonebreak.world.weather.WeatherMode.parse(weather);
                if (mode != null && com.stonebreak.network.MultiplayerSession.requestServerWeatherSet(mode, true)) {
                    System.out.println("[autoview] weather set to " + mode);
                }
            }
            // -Dstonebreak.autoperspective=third: the third-person body (shots of the player's pose, #457).
            if ("third".equalsIgnoreCase(System.getProperty("stonebreak.autoperspective", "").trim())
                    && Game.getPlayer() != null && !Game.getPlayer().isThirdPerson()) {
                Game.getPlayer().togglePerspective();
                System.out.println("[autoview] third person");
            }
            String lod = System.getProperty("stonebreak.autolod");
            if (lod != null && !lod.isBlank()) {
                String[] p = lod.split(":");
                var settings = com.stonebreak.config.Settings.getInstance();
                settings.setLodQuality(p[0]);
                if (p.length > 1) {
                    settings.setLodDistance(Integer.parseInt(p[1]));
                }
                var world = Game.getWorld();
                if (world != null && world.getConfig() != null) {
                    world.getConfig().setLodQuality(
                            com.stonebreak.world.fastlod.FastLodQuality.parse(settings.getLodQuality()));
                    world.getConfig().setLodRange(settings.getLodDistance());
                }
                System.out.println("[autoview] LOD quality " + settings.getLodQuality()
                        + ", distance " + settings.getLodDistance());
            }
        }
        String spec = System.getProperty("stonebreak.autoview");
        var player = Game.getPlayer();
        if (spec == null || spec.isBlank() || player == null) {
            return;
        }
        String[] p = spec.split(":");
        if (p.length < 5) {
            return;
        }
        player.setSpectator(true);
        double x = Double.parseDouble(p[0]);
        double z = Double.parseDouble(p[2]);
        double y = p[1].startsWith("top") ? autoViewTopY(x, z, p[1]) : Double.parseDouble(p[1]);
        y = autoViewClimb(y);
        double[] cruise = autoViewCruise();
        x += cruise[0];
        z += cruise[1];
        if (p.length > 5) {
            if (autoViewStartNanos < 0) {
                autoViewStartNanos = System.nanoTime();
            }
            double period = p.length > 6 ? Double.parseDouble(p[6]) : 3.0;
            double t = (System.nanoTime() - autoViewStartNanos) / 1e9;
            y += Double.parseDouble(p[5]) * Math.sin(2.0 * Math.PI * t / period);
        }
        player.setPosition(x, y, z);
        player.setVelocity(new org.joml.Vector3f());
        player.getCamera().setYaw(Float.parseFloat(p[3]));
        player.getCamera().setPitch(Float.parseFloat(p[4]));
    }

    private long autoClimbStartNanos = -1;
    private double autoClimbBaseY = Double.NaN;

    /**
     * {@code -Dstonebreak.autoview.climb=<toY>:<blocksPerSecond>[:<holdSeconds>]}: holds the autoview height
     * for {@code holdSeconds} (default 10: the ground streams in), then climbs straight up from it at a fixed
     * rate to {@code toY} - a continuous vertical flight, no horizontal movement. Inert unless set.
     */
    private double autoViewClimb(double y) {
        String spec = System.getProperty("stonebreak.autoview.climb");
        if (spec == null || spec.isBlank()) {
            return y;
        }
        String[] c = spec.split(":");
        double toY = Double.parseDouble(c[0]);
        double rate = c.length > 1 ? Double.parseDouble(c[1]) : 30.0;
        double hold = c.length > 2 ? Double.parseDouble(c[2]) : 10.0;
        if (autoClimbStartNanos < 0) {
            autoClimbStartNanos = System.nanoTime();
        }
        double t = (System.nanoTime() - autoClimbStartNanos) / 1e9 - hold;
        if (t < 0) {
            return y;
        }
        if (Double.isNaN(autoClimbBaseY)) {
            autoClimbBaseY = y;
            System.out.println("[autoview] climb from y " + y + " to " + toY + " at " + rate + " b/s");
        }
        double climbed = Math.min(toY, autoClimbBaseY + rate * t);
        if (climbed >= toY && autoClimbReachedNanos < 0) {
            autoClimbReachedNanos = System.nanoTime();
            System.out.println("[autoview] climb reached y " + toY);
        }
        return climbed;
    }

    private long autoClimbReachedNanos = -1;

    /**
     * {@code -Dstonebreak.autoview.cruise=<blocksPerSecond>:<headingDegrees>[:<delay s>]}: once the climb
     * ({@code stonebreak.autoview.climb}) reached its height (or from the start without one), flies horizontally
     * at a fixed rate along the heading (0 = +X, 90 = +Z) after {@code delay} seconds (default 2) - columns leave
     * and enter the load disc continuously while the camera stays high. Returns the {dx, dz} offset. Inert unless
     * set.
     */
    private double[] autoViewCruise() {
        String spec = System.getProperty("stonebreak.autoview.cruise");
        if (spec == null || spec.isBlank()) {
            return new double[2];
        }
        boolean climbing = System.getProperty("stonebreak.autoview.climb") != null;
        if (climbing && autoClimbReachedNanos < 0) {
            return new double[2];
        }
        if (!climbing && autoClimbReachedNanos < 0) {
            autoClimbReachedNanos = System.nanoTime();
        }
        String[] c = spec.split(":");
        double rate = Double.parseDouble(c[0]);
        double heading = Math.toRadians(c.length > 1 ? Double.parseDouble(c[1]) : 0.0);
        double delay = c.length > 2 ? Double.parseDouble(c[2]) : 2.0;
        double t = (System.nanoTime() - autoClimbReachedNanos) / 1e9 - delay;
        if (t <= 0) {
            return new double[2];
        }
        return new double[] {rate * t * Math.cos(heading), rate * t * Math.sin(heading)};
    }

    /**
     * {@code top[+n]}: stand on the highest block of the column (n blocks above it),
     * re-evaluated every frame so the camera rises as the column streams in.
     */
    private static double autoViewTopY(double x, double z, String spec) {
        double above = spec.length() > 3 ? Double.parseDouble(spec.substring(4)) : 0.0;
        var world = Game.getWorld();
        // The surface tracker's TOP (water included); the world floor until the column is known.
        long top = world != null ? world.topY((long) Math.floor(x), (long) Math.floor(z))
            : com.stonebreak.world.operations.WorldConfiguration.SEA_LEVEL;
        return top + above;
    }

    // ─── Dev: -Dstonebreak.autotorch=<seconds>[:night][:cluster] ─────────────

    private long autoTorchDeadlineNanos = -1;
    private boolean autoTorchDone;
    private boolean autoTorchPlaced;
    private float autoTorchLookX, autoTorchLookY, autoTorchLookZ;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds
     * after the world is entered, clears a pocket in front of the player, raises
     * a stone wall, places a ground torch and a wall torch (through the same
     * state-carrying {@code World.setBlockAt} the BlockPlacer uses), hands the
     * player a stack of torches (selected, so the held-torch light shows) and
     * aims the camera at them. {@code :night} jumps the clock to midnight so
     * the point lights are visible. Inert unless the property is set.
     */
    /**
     * Dev-hook edit that may land on an occupied cell: the server only accepts a placement into
     * air or water (issue #370), so a solid cell is broken first, as a player would.
     */
    private static void placeOverAnything(World world, int x, int y, int z,
                                          com.stonebreak.blocks.BlockType type) {
        var air = com.stonebreak.blocks.BlockType.AIR;
        var current = world.getBlockAt(x, y, z);
        if (type != air && current != air && current != com.stonebreak.blocks.BlockType.WATER) {
            world.setBlockAt(x, y, z, air, true);
        }
        world.setBlockAt(x, y, z, type, true);
    }

    private void maybeAutoTorch() {
        if (autoTorchDone) {
            if (autoTorchPlaced) {
                aimCameraAt(autoTorchLookX, autoTorchLookY, autoTorchLookZ);
            }
            return;
        }
        String spec = System.getProperty("stonebreak.autotorch");
        if (spec == null || spec.isBlank()) {
            autoTorchDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.PLAYING && autoTorchDeadlineNanos < 0) {
            return;
        }
        String[] parts = spec.trim().split(":");
        if (autoTorchDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(parts[0]);
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoTorchDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoTorchDeadlineNanos) {
            return;
        }
        autoTorchDone = true;
        var world = Game.getWorld();
        var player = Game.getPlayer();
        if (world == null || player == null) {
            System.err.println("[autotorch] world/player not ready");
            return;
        }
        var torchItem = com.stonebreak.blocks.torch.TorchBlock.item();
        if (torchItem == null) {
            System.err.println("[autotorch] torch item not registered");
            return;
        }
        org.joml.Vector3d pos = player.getPosition();
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);
        var air = com.stonebreak.blocks.BlockType.AIR;
        var stone = com.stonebreak.blocks.BlockType.STONE;
        // Pocket: x+1..x+7, z-3..z+3, floor of stone, back wall of stone at x+7.
        for (int x = px + 1; x <= px + 7; x++) {
            for (int z = pz - 3; z <= pz + 3; z++) {
                for (int y = py; y <= py + 4; y++) {
                    boolean wall = x == px + 7;
                    var want = wall ? stone : air;
                    if (world.getBlockAt(x, y, z) != want) {
                        placeOverAnything(world, x, y, z, want);
                    }
                }
                if (world.getBlockAt(x, py - 1, z) != stone) {
                    placeOverAnything(world, x, py - 1, z, stone);
                }
            }
        }
        var torchBlock = com.stonebreak.blocks.torch.TorchBlock.block();
        // Ground torch on the floor, 3 blocks ahead.
        String ground = com.stonebreak.blocks.torch.TorchState.ground().toStateString();
        boolean g = world.setBlockAt(px + 3, py, pz - 1, torchBlock, true, ground);
        // Wall torch hanging off the back wall (the wall is at +X of its cell → EAST).
        String side = com.stonebreak.blocks.torch.TorchState
                .side(com.stonebreak.blocks.torch.TorchState.Facing.EAST).toStateString();
        boolean w = world.setBlockAt(px + 6, py + 1, pz + 1, torchBlock, true, side);
        // ":cluster" — a ring of ground torches around the first one, to check
        // that overlapping lights saturate gracefully instead of bleaching.
        if (java.util.Arrays.asList(parts).contains("cluster")) {
            int[][] ring = {{2, -2}, {4, -2}, {2, 1}, {4, 1}, {5, -1}, {3, 2}, {1, -1}, {5, 2}};
            for (int[] o : ring) {
                world.setBlockAt(px + o[0], py, pz + o[1], torchBlock, true, ground);
            }
        }
        // Hand the player torches and select them.
        var inv = player.getInventory();
        inv.addItem(new com.stonebreak.items.ItemStack(torchItem, 16));
        for (int i = 0; i < 9; i++) {
            inv.setSelectedHotbarSlotIndex(i);
            var sel = inv.getSelectedHotbarSlot();
            if (sel != null && !sel.isEmpty() && sel.getItem() == torchItem) break;
        }
        if (java.util.Arrays.asList(parts).contains("night")) {
            // The integrated server owns the clock (TimeSyncS2C snaps the client
            // back), so route through the same path as /timeset.
            long day = Game.getTimeOfDay() != null ? Game.getTimeOfDay().getDay() : 0L;
            long midnight = day * com.stonebreak.world.TimeOfDay.TICKS_PER_DAY + 18000L;
            com.stonebreak.network.MultiplayerSession.requestServerTimeSet(midnight);
            if (Game.getTimeOfDay() != null) Game.getTimeOfDay().setTicks(midnight);
        }
        autoTorchLookX = px + 4.5f;
        autoTorchLookY = py + 0.6f;
        autoTorchLookZ = pz + 0f;
        autoTorchPlaced = true;
        System.out.println("[autotorch] ground=" + g + " @" + (px + 3) + "," + py + "," + (pz - 1)
                + " [" + world.getBlockStateAt(px + 3, py, pz - 1) + "]"
                + " wall=" + w + " @" + (px + 6) + "," + (py + 1) + "," + (pz + 1)
                + " [" + world.getBlockStateAt(px + 6, py + 1, pz + 1) + "]"
                + " held=" + (inv.getSelectedHotbarSlot() != null ? inv.getSelectedHotbarSlot().getItem().getName() : "-"));
    }

    // ─── Dev: -Dstonebreak.autofx=<seconds> ──────────────────────────────────

    private long autoFxStartNanos = -1;
    private long autoFxNextNanos;
    private boolean autoFxOff;
    private boolean autoFxStaged;
    private float autoFxLookX = Float.NaN, autoFxLookY, autoFxLookZ;

    /**
     * Development shortcut for FX shots (Stonebreak#436/#437): N seconds after entering the world, and every
     * 0.7 s from then on, plays every first-content asset binding in front of the camera through the real
     * binding paths — stone, dirt, wood and leaf debris (their SBOs' {@code break}), dust from a step on dirt,
     * and the player SBE's {@code splash}. Torch flames and furnace smoke come from real blocks: combine with
     * {@code stonebreak.autotorch} / {@code stonebreak.autofurnace}. Inert unless the property is set.
     * Options after the seconds: {@code :torches} places ground torches on the air above the ground ahead (their
     * SBO's {@code ambient} flame — placing into air passes the server's edit rules, clearing would not), plus a
     * standing furnace-smoke FX beside them; {@code :night} sets midnight; {@code :quiet} skips the bursts;
     * {@code :gameplay} plays the Stonebreak#441 effects instead (a fire-bolt trail sweeping across the view, its
     * impact burst, a decoy's smoke and a swimmer's ripples).
     */
    private void maybeAutoFx() {
        if (autoFxOff) {
            return;
        }
        String spec = System.getProperty("stonebreak.autofx");
        if (spec == null || spec.isBlank()) {
            autoFxOff = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.PLAYING) {
            return;
        }
        long now = System.nanoTime();
        if (autoFxStartNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(spec.trim().split(":")[0]);
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoFxStartNanos = now + (long) (seconds * 1e9);
            autoFxNextNanos = autoFxStartNanos;
            return;
        }
        if (!Float.isNaN(autoFxLookX)) {
            aimCameraAt(autoFxLookX, autoFxLookY, autoFxLookZ);
        }
        if (now >= autoFxStartNanos && Game.getPlayer() != null && spec.contains(":gameplay")) {
            if (!autoFxStaged) {
                autoFxStaged = true;
                stageAutoFx(java.util.Arrays.asList(spec.trim().split(":")));
            }
            autoFxGameplay(now);
            return;
        }
        if (now < autoFxNextNanos || Game.getPlayer() == null) {
            return;
        }
        autoFxNextNanos = now + 700_000_000L;
        java.util.List<String> opts = java.util.Arrays.asList(spec.trim().split(":"));
        if (!autoFxStaged) {
            autoFxStaged = true;
            stageAutoFx(opts);
        }
        if (opts.contains("quiet")) {
            return;
        }
        var camera = Game.getPlayer().getCamera();
        org.joml.Vector3d eye = camera.getPosition();
        org.joml.Vector3f front = new org.joml.Vector3f(camera.getFront().x, 0, camera.getFront().z).normalize();
        org.joml.Vector3f right = new org.joml.Vector3f(-front.z, 0, front.x);
        com.stonebreak.blocks.BlockType[] blocks = {com.stonebreak.blocks.BlockType.STONE,
                com.stonebreak.blocks.BlockType.DIRT, com.stonebreak.blocks.BlockType.WOOD,
                com.stonebreak.blocks.BlockType.LEAVES};
        for (int i = 0; i < blocks.length; i++) {
            float side = (i - 1.5f) * 1.6f;
            int x = (int) Math.floor(eye.x + front.x * 4 + right.x * side);
            int y = (int) Math.floor(eye.y - 0.2f);
            int z = (int) Math.floor(eye.z + front.z * 4 + right.z * side);
            com.stonebreak.fx.bindings.BlockFx.breakAt(blocks[i], x, y, z);
        }
        com.stonebreak.fx.bindings.BlockFx.stepAt(com.stonebreak.blocks.BlockType.DIRT,
                eye.x + front.x * 3 - right.x * 1.2f, eye.y - com.stonebreak.player.PlayerCharacter.get().eyeHeight(), eye.z + front.z * 3 - right.z * 1.2f);
        com.stonebreak.fx.bindings.EntityFx.splash(com.stonebreak.player.PlayerCharacter.ASSET_ID,
                eye.x + front.x * 3 + right.x * 1.5f, eye.y - com.stonebreak.player.PlayerCharacter.get().eyeHeight(), eye.z + front.z * 3 + right.z * 1.5f, 9f);
        com.stonebreak.fx.FxRuntime rt = com.stonebreak.fx.Fx.installed();
        if (rt != null) {
            System.out.printf("[autofx] live=%d instances=%d sim=%.3fms enabled=%s%n", rt.liveParticles(),
                    rt.instanceCount(), rt.lastUpdateNanos() / 1e6, com.stonebreak.fx.Fx.enabled());
        }
    }

    private com.stonebreak.fx.TrailFx autoFxTrail;
    private float autoFxTrailAt = Float.NaN;
    private long autoFxLastNanos;

    /**
     * {@code :gameplay}: every frame a fire-bolt trail flies 25 blocks/s across the view 5 blocks ahead (a new
     * streak every 8 blocks, ending in its impact burst); every 0.7 s decoy smoke on the left and a ripple on the
     * ground (or water) to the right, all around where the camera faces (pair with {@code stonebreak.autoview}).
     */
    private void autoFxGameplay(long now) {
        var camera = Game.getPlayer().getCamera();
        org.joml.Vector3d eye = camera.getPosition();
        org.joml.Vector3f front = new org.joml.Vector3f(camera.getFront().x, 0, camera.getFront().z).normalize();
        org.joml.Vector3f right = new org.joml.Vector3f(-front.z, 0, front.x);
        float dt = autoFxLastNanos == 0 ? 0f : Math.min(0.1f, (now - autoFxLastNanos) / 1e9f);
        autoFxLastNanos = now;
        if (autoFxTrail == null) {
            autoFxTrail = new com.stonebreak.fx.TrailFx(com.stonebreak.mobs.entities.FireBolt.TRAIL_FX);
        }
        double cx = eye.x + front.x * 5f, cz = eye.z + front.z * 5f, by = eye.y - 1.4;
        autoFxTrailAt = Float.isNaN(autoFxTrailAt) ? -4f : autoFxTrailAt + 25f * dt;
        if (autoFxTrailAt > 4f) {
            autoFxTrail.end();
            com.stonebreak.fx.Fx.fire(com.stonebreak.mobs.entities.FireBolt.IMPACT_FX,
                    cx + right.x * 4f, by, cz + right.z * 4f);
            autoFxTrailAt = -4f;
        }
        autoFxTrail.follow(cx + right.x * autoFxTrailAt, by, cz + right.z * autoFxTrailAt, -right.x, 0, -right.z);
        if (now < autoFxNextNanos) {
            return;
        }
        autoFxNextNanos = now + 700_000_000L;
        double sx = eye.x + front.x * 4f - right.x * 2.5f, sz = eye.z + front.z * 4f - right.z * 2.5f;
        com.stonebreak.fx.Fx.fire(com.stonebreak.player.combat.illusionist.MirroredDeceitAbility.SMOKE_FX,
                sx, autoFxSurface(sx, eye.y, sz), sz);
        for (int i = 0; i < 2; i++) {
            double rx = eye.x + front.x * (5f + i * 1.5f) + right.x * (1.5f + i * 0.8f);
            double rz = eye.z + front.z * (5f + i * 1.5f) + right.z * (1.5f + i * 0.8f);
            com.stonebreak.fx.bindings.EntityFx.ripple(com.stonebreak.player.PlayerCharacter.ASSET_ID,
                    rx, autoFxSurface(rx, eye.y, rz), rz);
        }
        com.stonebreak.fx.FxRuntime rt = com.stonebreak.fx.Fx.installed();
        if (rt != null) {
            System.out.printf("[autofx] gameplay live=%d instances=%d%n", rt.liveParticles(), rt.instanceCount());
        }
    }

    /** The top of the first block (or water surface) below {@code (x, fromY, z)}, scanning 12 blocks down. */
    private static double autoFxSurface(double x, double fromY, double z) {
        var world = Game.getWorld();
        long bx = (long) Math.floor(x), bz = (long) Math.floor(z);
        for (long y = (long) Math.floor(fromY); world != null && y > fromY - 12; y--) {
            var block = world.getBlockAt(bx, y, bz);
            if (block == com.stonebreak.blocks.BlockType.WATER) return y + 0.875;
            if (block != null && block != com.stonebreak.blocks.BlockType.AIR && block.isSolid()) return y + 1.0;
        }
        return fromY - 1.62;
    }

    /** {@link #maybeAutoFx}'s one-time staging: torches on the ground ahead, night, a standing smoke FX. */
    private void stageAutoFx(java.util.List<String> opts) {
        var world = Game.getWorld();
        var player = Game.getPlayer();
        if (world == null || player == null) return;
        org.joml.Vector3d pos = player.getPosition();
        org.joml.Vector3f front = new org.joml.Vector3f(player.getCamera().getFront().x, 0,
                player.getCamera().getFront().z).normalize();
        if (opts.contains("torches")) {
            var torchBlock = com.stonebreak.blocks.torch.TorchBlock.block();
            String ground = com.stonebreak.blocks.torch.TorchState.ground().toStateString();
            int placed = 0;
            for (int i = 0; i < 5; i++) {
                float side = (i - 2) * 1.7f;
                int x = (int) Math.floor(pos.x + front.x * 4.5f - front.z * side);
                int z = (int) Math.floor(pos.z + front.z * 4.5f + front.x * side);
                for (int y = (int) Math.floor(pos.y) + 3; y > pos.y - 6; y--) {
                    var below = world.getBlockAt(x, y - 1, z);
                    if (world.getBlockAt(x, y, z) == com.stonebreak.blocks.BlockType.AIR
                            && below != null && below.isSolid()) {
                        if (world.setBlockAt(x, y, z, torchBlock, true, ground)) placed++;
                        // Survival worlds refuse a dev placement server-side; the flame FX itself still shows.
                        com.stonebreak.fx.Fx.play("stonebreak:torch_fire", x + 0.5, y + 0.62, z + 0.5);
                        break;
                    }
                }
            }
            double sx = pos.x + front.x * 6.5f, sz = pos.z + front.z * 6.5f;
            var smoke = com.stonebreak.fx.Fx.play("stonebreak:furnace_smoke", sx, pos.y + 0.5, sz);
            autoFxLookX = (float) (pos.x + front.x * 4.5f);
            autoFxLookY = (float) (pos.y + 0.3);
            autoFxLookZ = (float) (pos.z + front.z * 4.5f);
            System.out.println("[autofx] torches placed=" + placed + " smoke=" + (smoke != null));
        }
        if (opts.contains("night")) {
            long day = Game.getTimeOfDay() != null ? Game.getTimeOfDay().getDay() : 0L;
            long midnight = day * com.stonebreak.world.TimeOfDay.TICKS_PER_DAY + 18000L;
            com.stonebreak.network.MultiplayerSession.requestServerTimeSet(midnight);
            if (Game.getTimeOfDay() != null) Game.getTimeOfDay().setTicks(midnight);
        }
    }

    private void aimCameraAt(double x, double y, double z) {
        var player = Game.getPlayer();
        if (player == null) return;
        org.joml.Vector3d pos = player.getPosition();
        double dx = x - pos.x;
        double dz = z - pos.z;
        double dy = y - (pos.y + 1.6);
        player.getCamera().setYaw((float) Math.toDegrees(Math.atan2(dz, dx)));
        player.getCamera().setPitch((float) Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    // ─── Dev: -Dstonebreak.autoscreen=<seconds>:<inventory|workbench|chat|f3|recipes|character|creation|terrain|death> 

    private long autoScreenDeadlineNanos = -1;
    private boolean autoScreenDone;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld} (#300): N seconds after the world is
     * entered, opens the inventory screen, or places a crafting table in front of the player and opens
     * it, through the game's own paths, so the container documents can be screenshotted from a script.
     * Inert unless the property is set.
     */
    private void maybeAutoScreen() {
        if (autoScreenDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autoscreen");
        if (spec == null || spec.isBlank()) {
            autoScreenDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.PLAYING) {
            return;
        }
        String[] parts = spec.split(":");
        if (autoScreenDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(parts[0].trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoScreenDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoScreenDeadlineNanos) {
            return;
        }
        autoScreenDone = true;
        String which = parts.length > 1 ? parts[1].trim() : "inventory";
        if (which.equals("workbench")) {
            var world = Game.getWorld();
            var player = Game.getPlayer();
            if (world == null || player == null) {
                System.err.println("[autoscreen] world/player not ready");
                return;
            }
            org.joml.Vector3d pos = player.getPosition();
            int x = (int) Math.floor(pos.x) + 2;
            int y = (int) Math.floor(pos.y);
            int z = (int) Math.floor(pos.z);
            world.setBlockAt(x, y, z, com.stonebreak.blocks.BlockType.WORKBENCH, true);
            System.out.println("[autoscreen] opening a crafting table at " + x + "," + y + "," + z);
            Game.getInstance().openWorkbenchScreen(new com.openmason.engine.util.BlockPos(x, y, z));
        } else if (which.equals("recipes")) { // #301: the recipe book document (opened from the inventory)
            System.out.println("[autoscreen] opening the recipe book");
            Game.getInstance().toggleInventoryScreen();
            Game.getInstance().openRecipeBookScreen();
        } else if (which.equals("character")) { // #301: the character sheet document
            System.out.println("[autoscreen] opening the character sheet");
            Game.getInstance().toggleCharacterScreen();
        } else if (which.equals("creation") || which.equals("terrain")) { // #301: the two creation state documents
            System.out.println("[autoscreen] entering " + which);
            Game.getInstance().setState(which.equals("creation") ? GameState.CHARACTER_CREATION : GameState.TERRAIN_MAPPER);
        } else if (which.equals("death")) { // #302: the death screen (pair with autopause: pausing while dead)
            System.out.println("[autoscreen] killing the player");
            Game.getPlayer().damage(Float.MAX_VALUE);
        } else if (which.equals("f3")) { // #301: the F3 debug overlay document
            System.out.println("[autoscreen] showing the F3 overlay");
            Game.getDebugOverlay().toggleVisibility();
        } else if (which.equals("chat")) { // #301: the chat document, open with a line typed and the picker up
            var chat = Game.getInstance().getChatSystem();
            System.out.println("[autoscreen] opening the chat");
            chat.addMessage("<autoscreen> hello [banana] and [problem]");
            chat.addMessage("Teleported to 120, 64, -38", new float[]{0.6f, 1f, 0.6f, 1f});
            chat.openChat();
            for (char ch : "hi [sword]".toCharArray()) {
                chat.handleCharInput(ch);
            }
            chat.toggleEmojiPicker();
        } else {
            System.out.println("[autoscreen] opening the inventory");
            Game.getInstance().toggleInventoryScreen();
        }
    }

    // ─── Dev: -Dstonebreak.autofurnace=<seconds> ─────────────────────────────

    private long autoFurnaceDeadlineNanos = -1;
    private boolean autoFurnaceDone;
    private int autoFurnaceX, autoFurnaceY, autoFurnaceZ;
    private boolean autoFurnacePlaced;
    /** Server-owned inventory (issue #334): the /obtain'd items still awaiting slot actions. */
    private boolean autoFurnaceFillPending;
    private com.stonebreak.items.Item autoFurnaceInputItem;
    private com.stonebreak.items.Item autoFurnaceFuelItem;
    private int autoFurnaceFrames;
    private String autoFurnaceLastState;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds
     * after the world is entered, places a furnace a few blocks from the player
     * and loads it with the first registered fuel + smeltable through the same
     * slot-intent packet the furnace UI sends, so the Unlit→Lit re-mesh can be
     * reproduced from a script. Inert unless the property is set.
     */
    private void maybeAutoFurnace() {
        if (autoFurnaceDone) {
            if (autoFurnacePlaced) {
                autoFurnaceWatch();
            }
            return;
        }
        String spec = System.getProperty("stonebreak.autofurnace");
        if (spec == null || spec.isBlank()) {
            autoFurnaceDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.PLAYING && autoFurnaceDeadlineNanos < 0) {
            return;
        }
        if (autoFurnaceDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(spec.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoFurnaceDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoFurnaceDeadlineNanos) {
            return;
        }
        autoFurnaceDone = true;
        var world = Game.getWorld();
        var player = Game.getPlayer();
        var smelting = Game.getInstance().getSmeltingManager();
        if (world == null || player == null || smelting == null) {
            System.err.println("[autofurnace] world/player/smelting not ready");
            return;
        }
        com.stonebreak.items.Item fuel = null;
        for (com.stonebreak.items.ItemType t : com.stonebreak.items.ItemType.values()) {
            if (smelting.getBurnTimePerUnit(t) > 0) { fuel = t; break; }
        }
        if (fuel == null) {
            for (com.stonebreak.blocks.BlockType t : com.stonebreak.blocks.BlockType.values()) {
                if (smelting.getBurnTimePerUnit(t) > 0) { fuel = t; break; }
            }
        }
        var recipes = smelting.getAllRecipes();
        if (fuel == null || recipes.isEmpty()) {
            System.err.println("[autofurnace] no fuel/recipe registered (fuel=" + fuel
                + ", recipes=" + recipes.size() + ")");
            return;
        }
        org.joml.Vector3d pos = player.getPosition();
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);
        int fx = px + 3;
        int fy = py;
        int fz = pz;
        // Clear a pocket in front of the player so the furnace and its
        // neighbours are in view (stone floor under it).
        for (int x = px + 1; x <= px + 6; x++) {
            for (int z = pz - 3; z <= pz + 3; z++) {
                for (int y = py; y <= py + 5; y++) {
                    if (world.getBlockAt(x, y, z) != com.stonebreak.blocks.BlockType.AIR) {
                        world.setBlockAt(x, y, z, com.stonebreak.blocks.BlockType.AIR, true);
                    }
                }
                if (world.getBlockAt(x, py - 1, z) == com.stonebreak.blocks.BlockType.AIR) {
                    world.setBlockAt(x, py - 1, z, com.stonebreak.blocks.BlockType.STONE, true);
                }
            }
        }
        if (!world.setBlockAt(fx, fy, fz, com.stonebreak.blocks.BlockType.FURNACE, true)) {
            System.err.println("[autofurnace] setBlockAt failed at " + fx + "," + fy + "," + fz);
            return;
        }
        var fr = Game.getInstance().getFurnaceRegistry();
        if (fr != null) {
            fr.onBlockPlaced(world, fx, fy, fz, com.stonebreak.blocks.BlockType.FURNACE);
        }
        // Server-owned inventory (issue #334): fill the furnace THROUGH the server by
        // requesting the items (/cheats + /obtain), then shipping the same atomic slot
        // actions a player's clicks would (see autoFurnaceWatch). BlockStateS2C echoes
        // drive the display registry from there.
        com.stonebreak.network.MultiplayerSession.requestServerCheatsSet(true);
        // The server splits the line on whitespace, so send the one-word identifier its
        // parser accepts (ItemLookup: stonebreak:<enum name>), not the display name —
        // "Iron Ore" would arrive as item "Iron" with count "Ore".
        com.stonebreak.network.MultiplayerSession.submitChat("/obtain "
            + obtainId(recipes.getFirst().getInput().getItem()) + " 8");
        com.stonebreak.network.MultiplayerSession.submitChat("/obtain " + obtainId(fuel) + " 8");
        // Give the server a beat to grant the items, then the watch loop ships the
        // TAKE/PUT action pairs once the synced inventory actually holds them.
        autoFurnaceInputItem = recipes.getFirst().getInput().getItem();
        autoFurnaceFuelItem = fuel;
        autoFurnaceFillPending = true;
        autoFurnaceX = fx;
        autoFurnaceY = fy;
        autoFurnaceZ = fz;
        autoFurnacePlaced = true;
        System.out.println("[autofurnace] placed furnace at " + fx + "," + fy + "," + fz
            + " fuel=" + fuel.getName() + " input=" + recipes.getFirst().getInput().getItem().getName()
            + " — waiting for /obtain to land before filling slots");
    }

    /**
     * The /obtain identifier for {@code item}: {@code stonebreak:<enum name>} (lower case), a
     * single token that {@code ItemLookup.find} resolves by enum name. Display names may hold
     * spaces, and the server splits the command line on whitespace.
     */
    static String obtainId(com.stonebreak.items.Item item) {
        String name;
        if (item instanceof com.stonebreak.blocks.BlockType b) {
            name = b.name();
        } else if (item instanceof com.stonebreak.items.ItemType t) {
            name = t.name();
        } else {
            name = item.getName().trim().replace(' ', '_');
        }
        return "stonebreak:" + name.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Server-owned inventory (issue #334): once the /obtain'd items landed in the synced
     * mirror, ship the same atomic slot actions a player's clicks would — TAKE_ALL from the
     * slot they sit in, PUT_ALL into the furnace's ingredient/fuel slot.
     */
    private void maybeAutoFurnaceFill(com.stonebreak.player.Player player) {
        if (!autoFurnaceFillPending) {
            return;
        }
        var inv = player.getInventory();
        if (inv == null) {
            return;
        }
        // Trigger: the /obtain'd stacks are visible in the synced mirror (the /obtain round
        // trip landed). The client's furnace display state stays empty until the server's
        // BlockStateS2C echoes the accepted slot actions.
        if (autoFurnaceInputItem == null || autoFurnaceFuelItem == null
                || inv.getItemCount(autoFurnaceInputItem) < 8
                || inv.getItemCount(autoFurnaceFuelItem) < 8) {
            return;
        }
        byte kind = com.stonebreak.network.packet.player.ContainerActionC2S.ContainerKind.FURNACE;
        fillFromInventory(inv, autoFurnaceInputItem, (byte) 0, kind);
        fillFromInventory(inv, autoFurnaceFuelItem, (byte) 1, kind);
        autoFurnaceFillPending = false;
        System.out.println("[autofurnace] slot actions sent for ingredient+fuel");
    }

    /** TAKE_ALL the first stack of {@code item} in the mirror, PUT_ALL it into furnace slot. */
    private void fillFromInventory(com.stonebreak.items.Inventory inv,
                                   com.stonebreak.items.Item item, byte furnaceSlot, byte kind) {
        for (int i = 0; i < com.stonebreak.items.Inventory.TOTAL_SLOTS; i++) {
            var s = i < 9 ? inv.getHotbarSlot(i) : inv.getMainInventorySlot(i - 9);
            if (s != null && !s.isEmpty() && s.getItem() == item) {
                byte region = i < 9
                        ? com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR
                        : com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN;
                byte idx = (byte) (i < 9 ? i : i - 9);
                com.stonebreak.network.MultiplayerSession.sendContainerAction(
                        autoFurnaceX, autoFurnaceY, autoFurnaceZ, kind, region, idx,
                        com.stonebreak.network.packet.player.ContainerActionC2S.Action.TAKE_ALL);
                com.stonebreak.network.MultiplayerSession.sendContainerAction(
                        autoFurnaceX, autoFurnaceY, autoFurnaceZ, kind,
                        furnaceSlot == 0
                                ? com.stonebreak.network.packet.player.ContainerActionC2S.Region.FURNACE_ING
                                : com.stonebreak.network.packet.player.ContainerActionC2S.Region.FURNACE_FUEL,
                        furnaceSlot,
                        com.stonebreak.network.packet.player.ContainerActionC2S.Action.PUT_ALL);
                return;
            }
        }
    }

    /** Keeps the camera on the placed furnace and logs its client-side state whenever it changes. */
    private void autoFurnaceWatch() {
        var world = Game.getWorld();
        var player = Game.getPlayer();
        if (world == null || player == null) {
            return;
        }
        maybeAutoFurnaceFill(player);
        org.joml.Vector3d pos = player.getPosition();
        double dx = autoFurnaceX + 0.5 - pos.x;
        double dz = autoFurnaceZ + 0.5 - pos.z;
        double dy = autoFurnaceY + 0.5 - (pos.y + 1.6);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.getCamera().setYaw(yaw);
        player.getCamera().setPitch(pitch);
        if (++autoFurnaceFrames % 30 != 0) {
            return;
        }
        // -Dstonebreak.autofurnace.ui=<frames-after-place>[:close] — open the
        // furnace UI on the furnace (and optionally close it again 120 frames
        // later) to reproduce UI-triggered rendering state leaks.
        String uiSpec = System.getProperty("stonebreak.autofurnace.ui");
        if (uiSpec != null && !uiSpec.isBlank()) {
            String[] parts = uiSpec.split(":");
            int openAt = Integer.parseInt(parts[0].trim());
            boolean close = parts.length > 1 && parts[1].equals("close");
            if (autoFurnaceFrames == openAt) {
                System.out.println("[autofurnace] opening furnace UI");
                Game.getInstance().openFurnaceScreen(
                    new com.openmason.engine.util.BlockPos(autoFurnaceX, autoFurnaceY, autoFurnaceZ));
            } else if (close && autoFurnaceFrames == openAt + 120) {
                System.out.println("[autofurnace] closing furnace UI");
                Game.getInstance().closeFurnaceScreen();
            }
        }
        String state = world.getBlockStateAt(autoFurnaceX, autoFurnaceY, autoFurnaceZ);
        String render = com.stonebreak.blocks.BlockRenderState.meshVariantKey(state);
        if (!java.util.Objects.equals(render, autoFurnaceLastState)) {
            autoFurnaceLastState = render;
            System.out.println("[autofurnace] render state now " + render + " (raw " + state + ")");
        }
    }

    // ─── Dev: -Dstonebreak.autobattle=<seconds>[:skipintro] ───────────────────

    private long autoBattleDeadlineNanos = -1;
    private boolean autoBattleDone;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds after the world is
     * entered, starts the Focus battle exactly as {@code /battle} does (entering the arena on the
     * way), optionally skipping the camera intro, so the mode can be screenshotted from a script.
     * Inert unless the property is set.
     */
    private com.stonebreak.battle.stage.BattleAutoScript autoBattleScript;
    private boolean autoBattleSkipIntro;

    private void maybeAutoBattle() {
        if (autoBattleDone && autoBattleSkipIntro && com.stonebreak.battle.stage.FocusBattle.isActive()
                && !com.stonebreak.battle.stage.FocusBattle.encounterTransitionActive()) {
            autoBattleSkipIntro = false; // the battle begins a frame after it is requested
            com.stonebreak.battle.stage.FocusBattle.skipIntroNow();
            System.out.println("[autobattle] intro skipped");
        }
        if (autoBattleDone) {
            // ...:script:<dir> / ...:shots:<dir>: a bot plays the fight and dumps labelled screenshots.
            if (autoBattleScript != null
                    && autoBattleScript.frame(Game.getDeltaTime(), window.width(), window.height())) {
                autoBattleScript = null;
                stopLoop();
            }
            return;
        }
        String spec = System.getProperty("stonebreak.autobattle");
        if (spec == null || spec.isBlank()) {
            autoBattleDone = true;
            return;
        }
        // Arm on the first PLAYING frame, and only fire from PLAYING: a window that lost focus under
        // the compositor auto-pauses, and the battle refuses to start from the pause menu.
        if (Game.getInstance().getState() != GameState.PLAYING) {
            return;
        }
        String[] parts = spec.split(":");
        if (autoBattleDeadlineNanos < 0) {
            double seconds = 3;
            try {
                seconds = Double.parseDouble(parts[0].trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoBattleDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoBattleDeadlineNanos) {
            return;
        }
        autoBattleDone = true;
        String result = com.stonebreak.battle.stage.FocusBattle.start();
        System.out.println("[autobattle] " + result + " (pending="
                + com.stonebreak.battle.stage.FocusBattle.isStartPending() + ")");
        autoBattleScript = com.stonebreak.battle.stage.BattleAutoScript.parse(parts);
        autoBattleSkipIntro = parts.length > 1 && parts[1].trim().equalsIgnoreCase("skipintro");
    }

    // ─── Dev: -Dstonebreak.autochat=<seconds>:<line>[;<line>...][|<seconds>:...] ──

    private long autoChatStartNanos = -1;
    private java.util.ArrayDeque<String> autoChatGroups;

    /**
     * Types chat lines as the player at set times into the world, exactly as if entered in the
     * chat box — so server-side commands go to the server like any other. Groups are
     * {@code <seconds>:<line>;<line>} joined by {@code |}, seconds counted from entering the world
     * and in ascending order. Drives in-game checks that need a setup, e.g. a goblin hunt:
     * {@code -Dstonebreak.autochat="5:/cheats 1;/spawn sheep 4;/spawn goblin 3|60:/goblins"}.
     * Inert unless set.
     */
    private void maybeAutoChat() {
        if (autoChatGroups == null) {
            String spec = System.getProperty("stonebreak.autochat");
            autoChatGroups = new java.util.ArrayDeque<>();
            if (spec != null && !spec.isBlank()) {
                autoChatGroups.addAll(java.util.List.of(spec.split("\\|")));
            }
        }
        if (autoChatGroups.isEmpty() || Game.getInstance().getState() != GameState.PLAYING) {
            return;
        }
        if (autoChatStartNanos < 0) {
            autoChatStartNanos = System.nanoTime();
        }
        String group = autoChatGroups.peek();
        int colon = group.indexOf(':');
        double seconds = 5;
        try {
            seconds = Double.parseDouble(colon > 0 ? group.substring(0, colon).trim() : "5");
        } catch (NumberFormatException ignored) {
            // keep default
        }
        if (System.nanoTime() - autoChatStartNanos < (long) (seconds * 1e9)) {
            return;
        }
        autoChatGroups.poll();
        var chat = Game.getInstance().getChatSystem();
        if (chat == null) {
            System.err.println("[autochat] no chat system");
            return;
        }
        for (String line : group.substring(colon + 1).split(";")) {
            if (line.isBlank()) {
                continue;
            }
            chat.openChat();
            chat.setInput(line.trim());
            chat.handleEnter();
            System.out.println("[autochat] " + line.trim());
        }
    }

    // ─── Dev: -Dstonebreak.autopause=<seconds>[:online] ─────────────────────────

    private long autoPauseDeadlineNanos = -1;
    private boolean autoPauseDone;

    /**
     * Fidelity captures of the pause menu (#296): N seconds after the world is entered, opens the
     * pause menu; {@code :online} lays it out as in a host/join session (six buttons, Resync World;
     * documents see {@code session.online = true}) without touching the network ({@code UiOnlineState}). Pair with
     * {@code -Dstonebreak.autoscreenshot} (a later deadline) and {@code -Dstonebreak.ui.pinclock}.
     * Inert unless the property is set.
     */
    private void maybeAutoPause() {
        if (autoPauseDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autopause");
        if (spec == null || spec.isBlank()) {
            autoPauseDone = true;
            return;
        }
        if (autoPauseDeadlineNanos < 0) {
            if (Game.getInstance().getState() != GameState.PLAYING) {
                return;
            }
            double seconds = 3;
            try {
                seconds = Double.parseDouble(spec.split(":")[0].trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoPauseDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoPauseDeadlineNanos) {
            return;
        }
        autoPauseDone = true;
        if (spec.trim().endsWith(":online")) {
            com.stonebreak.ui.UiOnlineState.override(() -> true);
        }
        com.stonebreak.ui.PauseMenu menu = Game.getInstance().getPauseMenu();
        if (Game.getInstance().getState() == GameState.PLAYING && menu != null && !menu.isVisible()) {
            Game.getInstance().togglePauseMenu();
        }
        System.out.println("[autopause] pause menu open: " + (menu != null && menu.isVisible())
            + (spec.trim().endsWith(":online") ? " (online layout)" : ""));
    }

    // ─── Dev: -Dstonebreak.autoresume=<seconds>[:<dir>] ──────────────────────

    private long autoResumeDeadlineNanos = -1;
    private int autoResumeShot = -1;
    private long autoResumePlayingNanos = -1;

    /**
     * Development shortcut for the main menu's Resume: N seconds after the menu's live preview of the
     * last-played world is on screen, presses RESUME as a click would. With a {@code <dir>}, writes
     * {@code resume-menu.png} as it presses, {@code resume-mid.png} half way through the move and
     * {@code resume-playing.png} two seconds into play. Inert unless the property is set.
     */
    private void maybeAutoResume() {
        String spec = System.getProperty("stonebreak.autoresume");
        if (spec == null || spec.isBlank() || autoResumeShot >= 3) {
            return;
        }
        var showcase = com.stonebreak.ui.mainMenu.showcase.MenuShowcase.get();
        GameState state = Game.getInstance().getState();
        String[] parts = spec.split(":", 2);
        String dir = parts.length > 1 ? parts[1] : null;
        if (autoResumeShot < 0) {
            if (state != GameState.MAIN_MENU || !showcase.isRenderable()) {
                return;
            }
            if (autoResumeDeadlineNanos < 0) {
                double seconds = 20;
                try {
                    seconds = Double.parseDouble(parts[0]);
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
                autoResumeDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
                return;
            }
            if (System.nanoTime() < autoResumeDeadlineNanos) {
                return;
            }
            autoResumeShot(dir, "resume-menu.png");
            System.out.println("[autoresume] pressing RESUME");
            Game.getInstance().getMainMenu().choose(4);
            autoResumeShot = 1;
            return;
        }
        if (autoResumeShot == 1 && showcase.isResuming() && showcase.menuExit() >= 0.5f) {
            autoResumeShot(dir, "resume-mid.png");
            autoResumeShot = 2;
            return;
        }
        if (autoResumeShot >= 1 && state == GameState.PLAYING) {
            if (autoResumePlayingNanos < 0) {
                autoResumePlayingNanos = System.nanoTime();
                System.out.println("[autoresume] playing");
            } else if (System.nanoTime() - autoResumePlayingNanos > 2_000_000_000L) {
                autoResumeShot(dir, "resume-playing.png");
                autoResumeShot = 3;
            }
        }
    }

    private void autoResumeShot(String dir, String name) {
        if (dir != null && !dir.isBlank()) {
            String file = java.nio.file.Path.of(dir, name).toString();
            if (com.stonebreak.core.dev.DevScreenshot.capture(file, window.width(), window.height())) {
                System.out.println("[autoresume] wrote " + file);
            }
        }
    }

    // ─── Dev: -Dstonebreak.autoscreenshot=<seconds>:<file.png>[:quit|:rejoin[N]] ─

    private long autoShotDeadlineNanos = -1;
    private boolean autoShotDone;
    private GameState lastLoggedState;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds
     * after the world is entered, reads the back buffer into a PNG (and
     * optionally quits) so a rendering change can be verified from a script
     * without a compositor screenshot. Inert unless the property is set.
     */
    private void maybeAutoScreenshot() {
        if (autoShotDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autoscreenshot");
        if (spec == null || spec.isBlank()) {
            autoShotDone = true;
            return;
        }
        GameState state = Game.getInstance().getState();
        if (state != lastLoggedState) {
            lastLoggedState = state;
            System.out.println("[autoscreenshot] game state: " + state);
        }
        // A window that loses focus under the compositor auto-pauses; the back
        // buffer still holds the rendered world behind the pause menu.
        // Arm on the first PLAYING frame; afterwards shoot on schedule whatever
        // UI state stray focus/keys may have toggled (the world is still drawn).
        // -Dstonebreak.autoscreenshot.anystate=true arms on the first frame of any state (the main
        // menu with a -Dstonebreak.uidoc overlay needs no world).
        boolean anyState = Boolean.getBoolean("stonebreak.autoscreenshot.anystate");
        if (autoShotDeadlineNanos < 0 && state != GameState.PLAYING && !anyState) {
            return;
        }
        String[] parts = spec.split(":");
        if (autoShotDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(parts[0]);
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoShotDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoShotDeadlineNanos) {
            return;
        }
        autoShotDone = true;
        var tracker = com.openmason.engine.diagnostics.GpuMemoryTracker.getInstance();
        StringBuilder vram = new StringBuilder("[autoscreenshot] vram");
        for (var c : com.openmason.engine.diagnostics.GpuMemoryTracker.Category.values()) {
            vram.append(' ').append(c).append('=').append(tracker.getBytes(c) >> 10).append("KiB");
        }
        System.out.println(vram.append(" total=").append(tracker.getTotalBytes() >> 10).append("KiB"));
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            var rr = com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
            System.out.println("[autoscreenshot] regions atlas=" + (rr.layerBytes(0) >> 10) + "KiB/"
                + rr.layerMeshes(0) + " water=" + (rr.layerBytes(1) >> 10) + "KiB/" + rr.layerMeshes(1)
                + " stamp=" + (rr.layerBytes(2) >> 10) + "KiB/" + rr.layerMeshes(2)
                + " translucent=" + (rr.layerBytes(3) >> 10) + "KiB/" + rr.layerMeshes(3)
                + " format=" + com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat.active());
        }
        var lod = com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.active();
        if (lod != null) {
            System.out.println("[autoscreenshot] lod terrain=" + (lod.layerBytes(0) >> 10) + "KiB/"
                + lod.layerMeshes(0) + " nodes/" + lod.layerQuads(0) + " quads"
                + " water=" + (lod.layerBytes(1) >> 10) + "KiB/" + lod.layerMeshes(1) + " nodes/"
                + lod.layerQuads(1) + " quads");
        }
        String file = parts.length > 1 ? parts[1] : "autoscreenshot.png";
        if (autoShotRejoins > 0) {
            file = file.endsWith(".png") ? file.substring(0, file.length() - 4) + "_rejoin" + autoShotRejoins + ".png"
                : file + "_rejoin" + autoShotRejoins;
        }
        int w = window.width();
        int h = window.height();
        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_BACK);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
        org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA,
            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 4;
                int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
            System.out.println("[autoscreenshot] wrote " + file + " (" + w + "x" + h + ")");
        } catch (java.io.IOException e) {
            System.err.println("[autoscreenshot] failed: " + e.getMessage());
        }
        if (parts.length > 2 && "quit".equalsIgnoreCase(parts[2])) {
            stopLoop();
        } else if (parts.length > 2 && parts[2].toLowerCase(java.util.Locale.ROOT).startsWith("rejoin")) {
            // <s>:<png>:rejoin[N] (agent Q, 2026-11-19): save and quit to the menu, load the same world again through
            // autoworld and shoot again (<png> with _rejoin<k> before .png), N times (default 1), then quit - world
            // teardown (FastLOD, terrain, integrated server) plus a reload in one session.
            int rounds = 1;
            try {
                rounds = Math.max(1, Integer.parseInt(parts[2].substring("rejoin".length())));
            } catch (NumberFormatException ignored) {
                // "rejoin" alone: once
            }
            if (autoShotRejoins < rounds) {
                autoShotRejoins++;
                System.out.println("[autoscreenshot] leaving to the menu, rejoin " + autoShotRejoins + "/" + rounds);
                Game.getInstance().leaveWorldToMenu();
                autoShotDone = false;
                autoShotDeadlineNanos = -1;
                autoWorldDone = false;
                autoWorldMenuFrames = 0;
            } else {
                stopLoop();
            }
        }
    }

    /** Rejoins done by {@code -Dstonebreak.autoscreenshot=<s>:<png>:rejoin[N]}. */
    private int autoShotRejoins;

    // ─── Dev: -Dstonebreak.autofly=<delay>:<speed>:<seconds>:<dir>[:<everyN>] ──

    private long autoFlyStartNanos = -1;
    private boolean autoFlyDone;
    private boolean autoFlyFlying;
    private double autoFlyX, autoFlyY, autoFlyZ;
    private long autoFlyFrame;
    private java.util.concurrent.ExecutorService autoFlyWriter;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: after
     * {@code delay} seconds in the world, flies the player along +X at
     * {@code speed} blocks/s, 40 blocks above the start, sweeping the yaw so
     * chunks stream in and out of view continuously, and dumps every
     * {@code everyN}-th frame (default 15) to {@code dir/f<frame>.png}. Quits
     * after {@code seconds} of flight. Built to reproduce transient
     * mesh-streaming corruption from a script. Inert unless the property is set.
     */
    private void maybeAutoFly() {
        if (autoFlyDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autofly");
        if (spec == null || spec.isBlank()) {
            autoFlyDone = true;
            return;
        }
        GameState state = Game.getInstance().getState();
        if (autoFlyStartNanos < 0) {
            if (state != GameState.PLAYING) {
                return;
            }
            autoFlyStartNanos = System.nanoTime();
            return;
        }
        if (state == GameState.PAUSED) {
            Game.getInstance().setState(GameState.PLAYING); // a stray focus loss must not stop the run
        }
        String[] parts = spec.split(":");
        double delay = parseOr(parts, 0, 10);
        double speed = parseOr(parts, 1, 40);
        double seconds = parseOr(parts, 2, 60);
        String dir = parts.length > 3 ? parts[3] : "autofly";
        int everyN = (int) parseOr(parts, 4, 15);
        double t = (System.nanoTime() - autoFlyStartNanos) / 1e9 - delay;
        if (t < 0) {
            return;
        }
        var player = Game.getPlayer();
        if (player == null) {
            return;
        }
        if (!autoFlyFlying) {
            autoFlyFlying = true;
            org.joml.Vector3d p = player.getPosition();
            autoFlyX = p.x;
            autoFlyY = Math.min(p.y + 40.0,
                com.stonebreak.world.operations.WorldConfiguration.MAX_Y - 8.0);
            autoFlyZ = p.z;
            new java.io.File(dir).mkdirs();
            autoFlyWriter = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread th = new Thread(r, "autofly-png");
                th.setDaemon(true);
                return th;
            });
            System.out.println("[autofly] start @" + autoFlyX + "," + autoFlyY + "," + autoFlyZ
                + " speed=" + speed + " seconds=" + seconds + " dir=" + dir + " every=" + everyN);
        }
        if (t > seconds) {
            autoFlyDone = true;
            for (int spin = 0; spin < 2000 && java.util.Arrays.stream(autoFlyPboFence).anyMatch(f -> f != 0); spin++) {
                org.lwjgl.opengl.GL11.glFinish();
                drainAutoFlyReadbacks(dir);
            }
            autoFlyWriter.shutdown();
            try {
                autoFlyWriter.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[autofly] done after " + autoFlyFrame + " frames, skipped " + autoFlySkipped);
            stopLoop();
            return;
        }
        // Fixed-rate path (not dt-integrated) so runs at different FPS cover the same ground.
        double x = autoFlyX + speed * t;
        // Terrain-following: hold ~25 blocks over the highest loaded surface just
        // ahead, climbing fast and sinking slowly, so the camera never ends up
        // inside a mountain (which reads as garbage to the frame analyzer).
        double ground = Double.NEGATIVE_INFINITY;
        for (int ahead = 0; ahead <= 48; ahead += 8) {
            ground = Math.max(ground, autoFlySurface(x + ahead, autoFlyZ));
        }
        if (ground > Double.NEGATIVE_INFINITY) {
            double target = Math.min(ground + 25.0,
                com.stonebreak.world.operations.WorldConfiguration.MAX_Y - 8.0);
            float dt = Math.min(0.1f, Game.getDeltaTime());
            autoFlyY = target > autoFlyY
                ? Math.min(target, autoFlyY + 60f * dt)
                : Math.max(target, autoFlyY - 15f * dt);
        }
        player.setFlying(true);
        player.teleport(x, autoFlyY, autoFlyZ);
        player.getCamera().setYaw((float) (60.0 * Math.sin(t * 0.7)));
        player.getCamera().setPitch(-20f);

        autoFlyFrame++;
        drainAutoFlyReadbacks(dir);
        if (everyN > 0 && autoFlyFrame % everyN == 0) {
            // Async PBO readback: a blocking glReadPixels drains the GPU queue
            // every capture, which is exactly the CPU/GPU lag a streaming race
            // needs — so never stall; skip the capture if every PBO is busy.
            int slot = (int) ((autoFlyFrame / everyN) % AUTOFLY_PBOS);
            if (autoFlyPboFence[slot] != 0) {
                autoFlySkipped++;
                return;
            }
            int w = window.width();
            int h = window.height();
            if (autoFlyPbo[slot] == 0 || autoFlyPboW != w || autoFlyPboH != h) {
                if (autoFlyPbo[slot] == 0) {
                    autoFlyPbo[slot] = org.lwjgl.opengl.GL15.glGenBuffers();
                }
                autoFlyPboW = w;
                autoFlyPboH = h;
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, autoFlyPbo[slot]);
                org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,
                    (long) w * h * 4, org.lwjgl.opengl.GL15.GL_STREAM_READ);
            }
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, autoFlyPbo[slot]);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_BACK);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, 0L);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
            autoFlyPboFence[slot] = org.lwjgl.opengl.GL32.glFenceSync(
                org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            autoFlyPboFrame[slot] = autoFlyFrame;
            autoFlyPboW2[slot] = w;
            autoFlyPboH2[slot] = h;
            System.out.printf("[autofly] frame %d t=%.2f x=%.1f fps=%.0f skipped=%d%n",
                autoFlyFrame, t, x, 1.0 / Math.max(1e-4, Game.getDeltaTime()), autoFlySkipped);
        }
    }

    private static final int AUTOFLY_PBOS = 6;
    private final int[] autoFlyPbo = new int[AUTOFLY_PBOS];
    private final long[] autoFlyPboFence = new long[AUTOFLY_PBOS];
    private final long[] autoFlyPboFrame = new long[AUTOFLY_PBOS];
    private final int[] autoFlyPboW2 = new int[AUTOFLY_PBOS];
    private final int[] autoFlyPboH2 = new int[AUTOFLY_PBOS];
    private int autoFlyPboW, autoFlyPboH;
    private long autoFlySkipped;

    /** Hands every finished PBO readback to the PNG writer without waiting on the GPU. */
    private void drainAutoFlyReadbacks(String dir) {
        for (int i = 0; i < AUTOFLY_PBOS; i++) {
            long fence = autoFlyPboFence[i];
            if (fence == 0) {
                continue;
            }
            int status = org.lwjgl.opengl.GL32.glClientWaitSync(fence, 0, 0L);
            if (status != org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED
                    && status != org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
                continue;
            }
            org.lwjgl.opengl.GL32.glDeleteSync(fence);
            autoFlyPboFence[i] = 0;
            int w = autoFlyPboW2[i];
            int h = autoFlyPboH2[i];
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, autoFlyPbo[i]);
            java.nio.ByteBuffer mapped = org.lwjgl.opengl.GL15.glMapBuffer(
                org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, org.lwjgl.opengl.GL15.GL_READ_ONLY);
            if (mapped != null) {
                java.nio.ByteBuffer copy = java.nio.ByteBuffer.allocate(w * h * 4);
                copy.put(mapped.limit(w * h * 4)).flip();
                org.lwjgl.opengl.GL15.glUnmapBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER);
                String file = String.format("%s/f%06d.png", dir, autoFlyPboFrame[i]);
                autoFlyWriter.submit(() -> writePng(copy, w, h, file));
            }
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    /** Top solid block + 1 at (x, z), or -inf when the column's chunk isn't loaded. */
    /**
     * The column's top (first free y above the topmost non-air block, water included) straight from the
     * world's surface tracker - no block scan - or -inf where nothing is known yet (a REMOTE client world
     * answers MIN_Y for columns the server has not sent).
     */
    private static double autoFlySurface(double x, double z) {
        var world = Game.getWorld();
        if (world == null) {
            return Double.NEGATIVE_INFINITY;
        }
        long top = world.topY((long) Math.floor(x), (long) Math.floor(z));
        return top <= com.stonebreak.world.operations.WorldConfiguration.MIN_Y ? Double.NEGATIVE_INFINITY : top;
    }

    private static double parseOr(String[] parts, int i, double fallback) {
        if (parts.length <= i) {
            return fallback;
        }
        try {
            return Double.parseDouble(parts[i].trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Writes the bottom-up RGBA readback as a half-resolution PNG (keeps up with every-2nd-frame capture). */
    private static void writePng(java.nio.ByteBuffer pixels, int w, int h, String file) {
        int ow = w / 2, oh = h / 2;
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(ow, oh, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int i = ((h - 1 - 2 * y) * w + 2 * x) * 4;
                int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
        } catch (java.io.IOException e) {
            System.err.println("[autofly] png failed: " + e.getMessage());
        }
    }

    // ─── Shutdown ─────────────────────────────────────────────────────────────

    private void cleanup() {
        Game.logDetailedMemoryInfo("Before cleanup");

        // Stop the terrain-diffusion service processes (if this session started any) before the
        // rest of cleanup. Independent of the GL context, so safe to run first; also registered
        // as a JVM shutdown hook as a safety net if cleanup() itself never runs (crash/kill -9).
        com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe.getInstance().shutdown();

        // OpenGL resources must be released while their context is still current, so all of this
        // happens before the window (and with it the context) is destroyed.
        if (window != null) {
            window.makeContextCurrent();
        }

        // UI document screens (their Masonry handles) and the draw providers' GL textures (item
        // icon atlas, model previews) go before the renderer and its Skija backend.
        try {
            com.stonebreak.ui.runtime.screens.DocumentScreenHost.ifCreated()
                    .ifPresent(com.stonebreak.ui.runtime.screens.DocumentScreenHost::closeAll);
            com.stonebreak.ui.runtime.providers.GameDrawProviders.shutdown();
        } catch (Exception e) {
            logger.error("Error cleaning up UI document screens and draw providers", e);
        }

        if (renderer != null) {
            renderer.cleanup();
            Game.logDetailedMemoryInfo("After renderer cleanup");
        }

        World world = Game.getInstance().getWorld();
        if (world != null) {
            world.cleanup();
            Game.logDetailedMemoryInfo("After world cleanup");
        }

        Game.getInstance().cleanup();
        Game.logDetailedMemoryInfo("After game cleanup");

        if (window != null) {
            window.destroy();
            Game.logDetailedMemoryInfo("After GLFW window cleanup");
        }

        Game.forceGCAndReport("Final cleanup");

        DisplayBackend.terminate();
    }

    // ─── Access for systems that outlive a single screen ──────────────────────

    /** The live GLFW window handle, or 0 before startup completes. */
    public static long getWindowHandle() {
        return instance != null && instance.window != null ? instance.window.handle() : 0;
    }

    /** Re-reads the real framebuffer size after a programmatic resize (e.g. a resolution change). */
    public static void refreshWindowSize() {
        if (instance != null && instance.window != null && instance.window.handle() != 0) {
            instance.window.syncFramebufferSize();
        }
    }

    /**
     * Applies the persisted VSync preference. The limiter reads the settings every frame, so this
     * only re-asserts swap interval 0 and logs the active cap.
     */
    public static void applyVsyncSetting() {
        if (instance != null && instance.window != null && instance.window.handle() != 0) {
            instance.window.setSwapInterval(0);
            instance.logVsyncSetting();
        }
    }

    /** Dev automation: quit after the current frame. */
    private void stopLoop() {
        if (frameLoop != null) {
            frameLoop.stop();
        }
    }

    /** The live game window, or null before startup completes. */
    public static Window window() {
        return instance != null ? instance.window : null;
    }

    /** Ends the frame loop after the current frame (dev benchmarks that quit when done). */
    public static void requestClose() {
        if (instance != null && instance.window != null) {
            instance.window.requestClose();
        }
    }
}
