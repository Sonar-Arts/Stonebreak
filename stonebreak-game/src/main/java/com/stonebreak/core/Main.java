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
            Game.displayDebugInfo();
            inputRouter.pollActiveScreen();

            frameRenderer.renderFrame();
            window.swapBuffers();

            if (!sleepToFrameBudget(System.nanoTime() - frameStartNanos)) {
                break;
            }
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

        // Stop the terrain-diffusion service processes (if this session started any) before the
        // rest of cleanup. Independent of the GL context, so safe to run first; also registered
        // as a JVM shutdown hook as a safety net if cleanup() itself never runs (crash/kill -9).
        com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager.getInstance().shutdown();

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
