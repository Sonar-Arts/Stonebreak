package com.stonebreak.core;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWMonitorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openmason.engine.diagnostics.MemoryProfiler;
import com.stonebreak.config.Settings;
import com.stonebreak.core.render.FrameRenderer;
import com.stonebreak.core.window.DisplayBackend;
import com.stonebreak.core.window.GameWindow;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MenuInputRouter;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.vram.CearlBootstrap;
import com.stonebreak.world.World;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glEnable;

/**
 * Entry point for Stonebreak: owns the application lifecycle — start up, run the frame loop, shut
 * down — and nothing else.
 *
 * <p>The pieces it drives each own one concern: {@link DisplayBackend} picks and starts the GLFW
 * platform, {@link GameWindow} owns the window and its GL context, {@link MenuInputRouter} decides
 * which screen an input event belongs to, and {@link FrameRenderer} draws the frame.</p>
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String WINDOW_TITLE = "Stonebreak";

    /** Static handle for the few systems that need to reach the live window (settings, resolution). */
    private static Main instance;

    private GameWindow window;
    private MenuInputRouter inputRouter;
    private FrameRenderer frameRenderer;

    private Renderer renderer;
    private InputHandler inputHandler;

    private boolean running = false;

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

        DisplayBackend.initialize();

        window = new GameWindow(settings.getWindowWidth(), settings.getWindowHeight());
        window.setResizeListener(this::onFramebufferResized);
        inputRouter = new MenuInputRouter(window);
        frameRenderer = new FrameRenderer(window);

        window.create(WINDOW_TITLE);
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
     * Wires GLFW's callbacks to the input router. Each one only adapts the raw GLFW signature —
     * which screen an event reaches is {@link MenuInputRouter}'s decision, not this class's.
     */
    private void installCallbacks() {
        long handle = window.handle();

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> inputRouter.onKey(key, action, mods));
        glfwSetCharCallback(handle, (win, codepoint) -> inputRouter.onCharacter(codepoint));
        glfwSetMouseButtonCallback(handle, (win, button, action, mods) ->
                inputRouter.onMouseButton(button, action, mods));
        glfwSetCursorPosCallback(handle, (win, x, y) -> inputRouter.onMouseMove(x, y));
        glfwSetScrollCallback(handle, (win, xOffset, yOffset) -> inputRouter.onScroll(yOffset));

        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> window.onFramebufferResized(w, h));
        // Window (screen-coordinate) size can change independently of the framebuffer on
        // Wayland/HiDPI; keep the cursor-to-UI scale in sync.
        glfwSetWindowSizeCallback(handle, (win, w, h) -> window.updateCursorScale());
        // A moved window may now be on a different monitor, so the VSync target changes. (Never fires
        // on Wayland — there refreshMonitorHz caps at the fastest monitor instead.)
        glfwSetWindowPosCallback(handle, (win, x, y) -> window.refreshMonitorHz());

        glfwSetWindowFocusCallback(handle, (win, focused) -> {
            var mouseCapture = Game.getInstance().getMouseCaptureManager();
            if (mouseCapture == null) {
                return;
            }
            if (focused) {
                mouseCapture.updateCaptureState();
            } else {
                mouseCapture.temporaryRelease();
            }
        });

        glfwSetWindowCloseCallback(handle, win -> {
            logger.debug("Window close requested - initiating shutdown...");
            running = false;
        });
    }

    private void initializeGameComponents() {
        MemoryProfiler profiler = MemoryProfiler.getInstance();
        profiler.takeSnapshot("before_initialization");

        renderer = new Renderer(window.width(), window.height());
        profiler.takeSnapshot("after_renderer_init");

        inputHandler = new InputHandler(window.handle());
        inputRouter.setInputHandler(inputHandler);

        BlockTextureArray textureAtlas = renderer.getBlockTextureArray();
        Game.getInstance().initCoreComponents(renderer, textureAtlas, inputHandler, window.handle());
        Game.getInstance().setWindowDimensions(window.width(), window.height());
        profiler.takeSnapshot("after_game_init");

        running = true;

        Game.logDetailedMemoryInfo("Core game components initialized - no world created");
        profiler.compareSnapshots("before_initialization", "after_game_init");
    }

    // ─── Frame loop ───────────────────────────────────────────────────────────

    @SuppressWarnings("BusyWait")
    private void loop() {
        glClearColor(0.5f, 0.8f, 1.0f, 0.0f);
        glEnable(GL_DEPTH_TEST);

        while (!window.shouldClose() && running) {
            long frameStartNanos = System.nanoTime();

            if (inputHandler != null) {
                inputHandler.prepareForNewFrame();
            }
            glfwPollEvents();

            Game.getInstance().update();
            maybeAutoStartWorld();
            Game.displayDebugInfo();
            inputRouter.pollActiveScreen();

            frameRenderer.renderFrame();
            maybeAutoScreenshot();
            window.swapBuffers();

            if (!sleepToFrameBudget(System.nanoTime() - frameStartNanos)) {
                break;
            }
        }
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
        String name = spec;
        long seed = 20260820L;
        int colon = spec.lastIndexOf(':');
        if (colon > 0) {
            name = spec.substring(0, colon);
            try {
                seed = Long.parseLong(spec.substring(colon + 1));
            } catch (NumberFormatException e) {
                System.err.println("[autoworld] bad seed in " + spec + " — using " + seed);
            }
        }
        System.out.println("[autoworld] starting singleplayer world '" + name + "' seed " + seed);
        com.stonebreak.network.MultiplayerSession.startSingleplayer(name, seed);
    }

    // ─── Dev: -Dstonebreak.autoscreenshot=<seconds>:<file.png>[:quit] ──────────

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
        if (autoShotDeadlineNanos < 0 && state != GameState.PLAYING) {
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
            running = false;
        }
    }

    /**
     * Paces the loop to the active FPS cap. A budget of 0 means uncapped, so no sleep happens.
     *
     * @return false if the sleep was interrupted and the loop should exit
     */
    private boolean sleepToFrameBudget(long frameTimeNanos) {
        long budgetNanos = window.frameBudgetNanos();
        long remainingNanos = budgetNanos - frameTimeNanos;
        if (remainingNanos <= 0) {
            return true;
        }
        long millis = remainingNanos / 1_000_000;
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis, (int) (remainingNanos % 1_000_000));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ─── Shutdown ─────────────────────────────────────────────────────────────

    private void cleanup() {
        Game.logDetailedMemoryInfo("Before cleanup");

        // OpenGL resources must be released while their context is still current, so all of this
        // happens before the window (and with it the context) is destroyed.
        if (window != null) {
            window.makeContextCurrent();
        }

        try {
            if (CBRResourceManager.isInitialized()) {
                CBRResourceManager.getInstance().close();
                logger.debug("CBRResourceManager cleaned up successfully");
            }
        } catch (Exception e) {
            logger.error("Error cleaning up CBRResourceManager", e);
        }
        Game.logDetailedMemoryInfo("After CBR cleanup");

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

        GLFWMonitorCallback monitorCallback = glfwSetMonitorCallback(null);
        if (monitorCallback != null) {
            monitorCallback.free();
        }
        glfwTerminate();
        GLFWErrorCallback errorCallback = glfwSetErrorCallback(null);
        if (errorCallback != null) {
            errorCallback.free();
        }
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

    /** Applies the persisted VSync preference to the frame limiter. */
    public static void applyVsyncSetting() {
        if (instance != null && instance.window != null) {
            instance.window.applyVsyncSetting();
        }
    }
}
