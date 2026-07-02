package com.stonebreak.core;


import java.nio.IntBuffer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;

import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.config.Settings;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import org.lwjgl.*;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.openmason.engine.diagnostics.MemoryProfiler;
import com.stonebreak.world.World;
import org.lwjgl.Version;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Main class for the Stonebreak game - a voxel-based sandbox.
 */
public class Main {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Main.class);

    // Window handle
    private long window;
    
    // Game settings - loaded from Settings at startup
    private int width;
    private int height;

    /**
     * Detected monitor refresh rate. Used as the target FPS when VSync
     * is enabled — capping at the display rate gives the same
     * tear-suppression benefit as driver VSync (assuming G-Sync/FreeSync
     * or simply running below the monitor's max) without the half-rate
     * fallback that double-buffered swap-interval=1 imposes when frames miss.
     */
    private static int monitorRefreshHz = 60;
    
    // Game state
    private boolean running = false;
    private boolean firstRender = true;
    // True once the GL context is current and LWJGL capabilities are created.
    // GLFW may fire the framebuffer-size callback while the window is being
    // shown/positioned (notably on Linux) before GL is usable, so GL calls in
    // that callback must be gated on this flag to avoid a native abort.
    private volatile boolean glReady = false;
    // Framebuffer-pixels per window-coordinate. glfwGetCursorPos reports in
    // window (screen) coordinates, but the UI is laid out in framebuffer
    // pixels (width/height). These are 1.0 when the two spaces match (Windows,
    // X11 non-HiDPI) and differ on Wayland / HiDPI, where UI input must be
    // scaled by these factors to line up with rendered UI.
    private double cursorScaleX = 1.0;
    private double cursorScaleY = 1.0;

    // Game components
    private Renderer renderer;
    private InputHandler inputHandler;

    // GLFW callback references for proper cleanup
    private GLFWKeyCallback keyCallback;
    private GLFWCharCallback charCallback;
    private GLFWFramebufferSizeCallback framebufferSizeCallback;
    private GLFWMouseButtonCallback mouseButtonCallback;
    private GLFWCursorPosCallback cursorPosCallback;
    private GLFWWindowFocusCallback windowFocusCallback;
    private GLFWWindowCloseCallback windowCloseCallback;
    
    // Static references for system-wide access
    private static Main instance;

    public static void main(String[] args) {
        GcEnforcement.enforce();
        new Main().run();
    }
    

    private void run() {
        instance = this; // Set the instance
        System.out.println("Starting Stonebreak with LWJGL " + Version.getVersion());
        
        // Load settings early in initialization
        loadSettings();
        
        try {
            init();
            loop();
        } finally {
            cleanup();
            logger.debug("Stonebreak shutdown complete.");
        }
        System.exit(0);
    }
    
    private void loadSettings() {
        Settings settings = Settings.getInstance();
        this.width = settings.getWindowWidth();
        this.height = settings.getWindowHeight();
        System.out.println("Settings loaded - Window size: " + width + "x" + height);
    }

    /**
     * VSync here means "cap to monitor refresh rate via the manual limiter,"
     * not the driver's swap-interval=1. The cap delivers the same anti-tear
     * benefit on G-Sync/FreeSync displays without the half-rate fallback that
     * double-buffered swap-interval=1 forces when a frame misses vblank.
     *
     * <p>swapInterval is left at 0 unconditionally so the driver never blocks
     * us — the {@code Thread.sleep} pacing in {@code loop()} does the work.
     */
    public static void applyVsyncSetting() {
        glfwSwapInterval(0);
        Settings settings = Settings.getInstance();
        boolean enabled = settings.isVsyncEnabled();
        String fpsCap = settings.isMaxFpsUnlimited() ? "unlimited" : settings.getMaxFps() + " FPS";
        System.out.println("[Display] VSync " + (enabled ? "enabled (cap " + monitorRefreshHz + " Hz)"
                                                          : "disabled")
                + ", Max FPS " + fpsCap);
    }

    /**
     * Returns the current per-frame nanosecond budget for the manual FPS
     * limiter, or {@code 0} for fully uncapped (no sleep). The effective cap is
     * the lowest of the active limits: the monitor refresh rate when VSync is
     * on, and the user's Max FPS setting when it isn't set to Unlimited.
     */
    private static long currentFrameBudgetNanos() {
        Settings settings = Settings.getInstance();
        int cap = Integer.MAX_VALUE;
        if (settings.isVsyncEnabled() && monitorRefreshHz > 0) {
            cap = Math.min(cap, monitorRefreshHz);
        }
        if (!settings.isMaxFpsUnlimited()) {
            cap = Math.min(cap, settings.getMaxFps());
        }
        if (cap == Integer.MAX_VALUE || cap <= 0) {
            return 0L; // no active cap — skip the sleep entirely
        }
        return 1_000_000_000L / cap;
    }
    
    /**
     * Re-synchronizes render/UI state from the window's ACTUAL framebuffer size.
     * Call after programmatically changing the window size (e.g. applying a
     * resolution setting): GLFW/Wayland may clamp or ignore the requested size
     * and may not deliver the framebuffer-size callback synchronously, so we
     * read the real size back and update the viewport, projection, cursor scale,
     * and stored dimensions to match — preventing a stale-viewport glitch.
     */
    public static void refreshWindowSize() {
        if (instance != null && instance.window != 0) {
            instance.syncFramebufferSize();
        }
    }

    private void syncFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer fbW = stack.mallocInt(1);
            IntBuffer fbH = stack.mallocInt(1);
            glfwGetFramebufferSize(window, fbW, fbH);
            this.width = fbW.get(0);
            this.height = fbH.get(0);
        }
        if (glReady) {
            glViewport(0, 0, this.width, this.height);
            if (renderer != null) {
                renderer.updateProjectionMatrix(this.width, this.height);
            }
        }
        updateCursorScale();
        Game.getInstance().setWindowDimensions(this.width, this.height);
        // The window may now sit on a different monitor; keep the VSync cap in sync.
        refreshMonitorHz();
    }

    /** True when GLFW selected the native Wayland backend at init. */
    private static boolean isWayland() {
        return glfwGetPlatform() == GLFW_PLATFORM_WAYLAND;
    }

    /**
     * Sets {@link #monitorRefreshHz} from the monitor the window currently
     * occupies (largest window/monitor overlap), rather than always the primary
     * monitor — so the VSync frame cap matches the display the game is shown on
     * in multi-monitor setups.
     *
     * <p>On Wayland the compositor never exposes window positions
     * (glfwGetWindowPos would just emit GLFW_FEATURE_UNAVAILABLE and return
     * 0,0), so the occupied monitor cannot be determined. There we cap at the
     * fastest connected display instead: an over-cap is harmless, while capping
     * a 144 Hz display at a slower primary's 60 Hz would visibly degrade.
     */
    private static void refreshMonitorHz() {
        if (instance == null || instance.window == 0) return;
        try (MemoryStack stack = stackPush()) {
            long bestMonitor = glfwGetPrimaryMonitor();
            PointerBuffer monitors = glfwGetMonitors();

            if (isWayland()) {
                int bestHz = -1;
                if (monitors != null) {
                    for (int i = 0; i < monitors.limit(); i++) {
                        long mon = monitors.get(i);
                        GLFWVidMode mode = glfwGetVideoMode(mon);
                        if (mode != null && mode.refreshRate() > bestHz) {
                            bestHz = mode.refreshRate();
                            bestMonitor = mon;
                        }
                    }
                }
            } else {
                IntBuffer wx = stack.mallocInt(1);
                IntBuffer wy = stack.mallocInt(1);
                IntBuffer ww = stack.mallocInt(1);
                IntBuffer wh = stack.mallocInt(1);
                glfwGetWindowPos(instance.window, wx, wy);
                glfwGetWindowSize(instance.window, ww, wh);
                int winX = wx.get(0), winY = wy.get(0), winW = ww.get(0), winH = wh.get(0);

                long bestArea = -1;
                if (monitors != null) {
                    IntBuffer mx = stack.mallocInt(1);
                    IntBuffer my = stack.mallocInt(1);
                    for (int i = 0; i < monitors.limit(); i++) {
                        long mon = monitors.get(i);
                        GLFWVidMode mode = glfwGetVideoMode(mon);
                        if (mode == null) continue;
                        glfwGetMonitorPos(mon, mx, my);
                        int monX = mx.get(0), monY = my.get(0);
                        // Overlap area between the window rect and this monitor rect.
                        int ox = Math.max(0, Math.min(winX + winW, monX + mode.width())  - Math.max(winX, monX));
                        int oy = Math.max(0, Math.min(winY + winH, monY + mode.height()) - Math.max(winY, monY));
                        long area = (long) ox * oy;
                        if (area > bestArea) {
                            bestArea = area;
                            bestMonitor = mon;
                        }
                    }
                }
            }

            GLFWVidMode bestMode = glfwGetVideoMode(bestMonitor);
            if (bestMode != null && bestMode.refreshRate() > 0 && bestMode.refreshRate() != monitorRefreshHz) {
                monitorRefreshHz = bestMode.refreshRate();
                System.out.println("[Display] Using monitor refresh rate: " + monitorRefreshHz + " Hz");
            }
        }
    }

    /**
     * Recomputes the window-coordinate -> framebuffer-pixel scale used to map
     * cursor positions into UI space. Called whenever the window or framebuffer
     * size changes. width/height hold the current framebuffer size.
     */
    private void updateCursorScale() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer winW = stack.mallocInt(1);
            IntBuffer winH = stack.mallocInt(1);
            glfwGetWindowSize(window, winW, winH);
            cursorScaleX = winW.get(0) > 0 ? (double) width / winW.get(0) : 1.0;
            cursorScaleY = winH.get(0) > 0 ? (double) height / winH.get(0) : 1.0;
        }
    }

    /**
     * Reads the cursor position converted from window coordinates into
     * framebuffer-pixel (UI) space. Use for all UI hit-testing so clicks line
     * up with rendered UI on Wayland/HiDPI where the two spaces differ.
     */
    private void getUiCursorPos(java.nio.DoubleBuffer xpos, java.nio.DoubleBuffer ypos) {
        glfwGetCursorPos(window, xpos, ypos);
        xpos.put(0, xpos.get(0) * cursorScaleX);
        ypos.put(0, ypos.get(0) * cursorScaleY);
    }

    private void init() {
        // Setup an error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        System.out.println("[Display] GLFW platform: " + switch (glfwGetPlatform()) {
            case GLFW_PLATFORM_WAYLAND -> "Wayland";
            case GLFW_PLATFORM_X11 -> "X11";
            case GLFW_PLATFORM_WIN32 -> "Win32";
            case GLFW_PLATFORM_COCOA -> "Cocoa";
            default -> "unknown";
        });

        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // Request a compatible profile - this allows OpenGL 3.2 features in case it's available
        // but falls back to compatibility profile if needed
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);

        // Wayland identifies applications by app_id (taskbar grouping, icon,
        // window rules); ignored on other platforms.
        glfwWindowHintString(GLFW_WAYLAND_APP_ID, "stonebreak");

        // Create the window
        String title = "Stonebreak";
        window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        // Setup key callback
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            Game game = Game.getInstance();

            // Handle world select screen key input
            if (game != null && game.getState() == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
                game.getWorldSelectScreen().handleKeyInput(key, action, mods);
            }
            // Handle terrain mapper key input
            else if (game != null && game.getState() == GameState.TERRAIN_MAPPER && game.getTerrainMapperScreen() != null) {
                game.getTerrainMapperScreen().handleKeyInput(key, action, mods);
            }
            // Multiplayer screens: text-field key handling
            else if (game != null && game.getState() == GameState.HOST_WORLD_SELECT && game.getHostWorldScreen() != null) {
                game.getHostWorldScreen().handleKeyInput(key, action, mods);
            }
            else if (game != null && game.getState() == GameState.JOIN_WORLD_SCREEN && game.getJoinWorldScreen() != null) {
                game.getJoinWorldScreen().handleKeyInput(key, action, mods);
            }
            // Pass key events to InputHandler for chat handling
            else if (inputHandler != null) {
                inputHandler.handleKeyInput(key, action, mods);
            }
        });

        // Setup character callback for chat text input
        glfwSetCharCallback(window, (win, codepoint) -> {
            Game game = Game.getInstance();

            // Drop codepoints outside the BMP; casting them to a single char
            // would produce an unpaired surrogate that crashes Skija's text
            // layout on the next measureTextWidth call.
            if (codepoint < 0 || codepoint > 0xFFFF || Character.isSurrogate((char) codepoint)) {
                return;
            }
            char character = (char) codepoint;

            // Handle world select screen character input
            if (game != null && game.getState() == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
                game.getWorldSelectScreen().handleCharacterInput(character);
            }
            // Handle terrain mapper character input
            else if (game != null && game.getState() == GameState.TERRAIN_MAPPER && game.getTerrainMapperScreen() != null) {
                game.getTerrainMapperScreen().handleCharacterInput(character);
            }
            // Multiplayer screens: text-field char input
            else if (game != null && game.getState() == GameState.HOST_WORLD_SELECT && game.getHostWorldScreen() != null) {
                game.getHostWorldScreen().handleCharInput(character);
            }
            else if (game != null && game.getState() == GameState.JOIN_WORLD_SCREEN && game.getJoinWorldScreen() != null) {
                game.getJoinWorldScreen().handleCharInput(character);
            }
            // Pass character input to InputHandler for chat handling
            else if (inputHandler != null) {
                inputHandler.handleCharacterInput(character);
            }
        });

        // Setup window resize callback
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            this.width = w;
            this.height = h;
            // Guard GL calls: this callback can fire before the context is
            // current / capabilities are created (e.g. during window show or
            // positioning on Linux). The stored width/height are re-applied to
            // the viewport once GL is ready (see below).
            if (glReady) {
                glViewport(0, 0, w, h);
                if (renderer != null) {
                    renderer.updateProjectionMatrix(w, h);
                }
            }
            // Framebuffer size changed -> refresh cursor->UI scale.
            updateCursorScale();
            // Update the Game singleton with new dimensions
            Game.getInstance().setWindowDimensions(w, h);
        });

        // Window (screen-coordinate) size can change independently of the
        // framebuffer on Wayland/HiDPI; keep the cursor->UI scale in sync.
        glfwSetWindowSizeCallback(window, (win, w, h) -> updateCursorScale());

        // Window moved -> it may now be on a different monitor; refresh the
        // VSync refresh-rate target to match the current display. (Never fires
        // on Wayland — there refreshMonitorHz caps at the fastest monitor
        // instead, so no per-move refresh is needed.)
        glfwSetWindowPosCallback(window, (win, x, y) -> refreshMonitorHz());

        // Setup mouse button callback
        // This now directly calls InputHandler's processMouseButton method.
        // InputHandler will then decide how to process the click based on game state (paused, inventory open, etc.)
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            Game game = Game.getInstance();
            if (game != null && game.getState() == GameState.STARTUP_INTRO) {
                if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS
                        && game.getStartupIntroScreen() != null) {
                    game.getStartupIntroScreen().skipToMainMenu();
                }
            } else if (game != null && game.getState() == GameState.MAIN_MENU) {
                // Handle main menu clicks
                if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                    try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                        java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                        java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                        getUiCursorPos(xpos, ypos);
                        if (game.getMainMenu() != null) {
                            game.getMainMenu().handleMouseClick(xpos.get(), ypos.get(), width, height);
                        }
                    }
                }
            } else if (game != null && game.getState() == GameState.WORLD_SELECT) {
                // Handle world select screen clicks
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    if (game.getWorldSelectScreen() != null) {
                        game.getWorldSelectScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (game != null && game.getState() == GameState.SETTINGS) {
                // Handle settings menu clicks
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    if (game.getSettingsMenu() != null) {
                        game.getSettingsMenu().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (game != null && game.getState() == GameState.CHARACTER_CREATION) {
                // Handle character creation clicks
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    if (game.getCharacterCreationScreen() != null) {
                        game.getCharacterCreationScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (game != null && game.getState() == GameState.TERRAIN_MAPPER) {
                // Handle terrain mapper clicks
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    if (game.getTerrainMapperScreen() != null) {
                        game.getTerrainMapperScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (game != null && (game.getState() == GameState.MULTIPLAYER_MENU
                    || game.getState() == GameState.HOST_WORLD_SELECT
                    || game.getState() == GameState.JOIN_WORLD_SCREEN)) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    switch (game.getState()) {
                        case MULTIPLAYER_MENU -> game.getMultiplayerMenu().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                        case HOST_WORLD_SELECT -> game.getHostWorldScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                        case JOIN_WORLD_SCREEN -> game.getJoinWorldScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                        default -> {}
                    }
                }
            } else if (inputHandler != null) {
                inputHandler.processMouseButton(button, action, mods);
            }
        });

        // Setup cursor position callback
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            Game game = Game.getInstance();

            // Process mouse movement for camera look (if mouse is captured).
            // Camera look consumes raw deltas, so it must use unscaled coords.
            MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();
            if (mouseCaptureManager != null) {
                mouseCaptureManager.processMouseMovement(xpos, ypos);
            }

            // UI hit-testing happens in framebuffer-pixel space; convert the
            // window-coordinate cursor position accordingly.
            double uiX = xpos * cursorScaleX;
            double uiY = ypos * cursorScaleY;

            // Update InputHandler for UI interactions (always needed for UI)
            if (inputHandler != null) {
                inputHandler.updateMousePosition((float)uiX, (float)uiY);
            }

            // Handle main menu hover events
            if (game.getState() == GameState.MAIN_MENU && game.getMainMenu() != null) {
                game.getMainMenu().handleMouseMove(uiX, uiY, width, height);
            }
            // Handle world select screen hover events
            else if (game.getState() == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
                game.getWorldSelectScreen().handleMouseMove(uiX, uiY, width, height);
            }
            // Handle settings menu hover events
            else if (game.getState() == GameState.SETTINGS && game.getSettingsMenu() != null) {
                game.getSettingsMenu().handleMouseMove(uiX, uiY, width, height);
            }
            // Handle character creation hover events
            else if (game.getState() == GameState.CHARACTER_CREATION && game.getCharacterCreationScreen() != null) {
                game.getCharacterCreationScreen().handleMouseMove(uiX, uiY, width, height);
            }
            // Handle terrain mapper hover events
            else if (game.getState() == GameState.TERRAIN_MAPPER && game.getTerrainMapperScreen() != null) {
                game.getTerrainMapperScreen().handleMouseMove(uiX, uiY, width, height);
            }
            else if (game.getState() == GameState.MULTIPLAYER_MENU && game.getMultiplayerMenu() != null) {
                game.getMultiplayerMenu().handleMouseMove(uiX, uiY, width, height);
            }
            else if (game.getState() == GameState.HOST_WORLD_SELECT && game.getHostWorldScreen() != null) {
                game.getHostWorldScreen().handleMouseMove(uiX, uiY, width, height);
            }
            else if (game.getState() == GameState.JOIN_WORLD_SCREEN && game.getJoinWorldScreen() != null) {
                game.getJoinWorldScreen().handleMouseMove(uiX, uiY, width, height);
            }
        });

        // Setup scroll callback for world select screen and hotbar selection
        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            Game game = Game.getInstance();
            if (game != null && game.getState() == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
                game.getWorldSelectScreen().handleMouseWheel(yoffset);
            } else if (game != null && game.getState() == GameState.CHARACTER_CREATION && game.getCharacterCreationScreen() != null) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    game.getCharacterCreationScreen().handleMouseWheel(xpos.get(), ypos.get(), yoffset);
                }
            } else if (game != null && game.getState() == GameState.TERRAIN_MAPPER && game.getTerrainMapperScreen() != null) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    getUiCursorPos(xpos, ypos);
                    game.getTerrainMapperScreen().handleMouseWheel(xpos.get(), ypos.get(), yoffset);
                }
            } else if (inputHandler != null) {
                // Forward scroll events to InputHandler for hotbar selection and other UI interactions
                inputHandler.handleScroll(yoffset);
            }
        });

        // Setup window focus callback to handle mouse capture on focus changes
        glfwSetWindowFocusCallback(window, (win, focused) -> {
            Game game = Game.getInstance();
            MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();
            if (mouseCaptureManager != null) {
                if (focused) {
                    mouseCaptureManager.updateCaptureState();
                } else {
                    mouseCaptureManager.temporaryRelease();
                }
            }
        });

        // Setup window close callback to handle X button clicks
        glfwSetWindowCloseCallback(window, win -> {
            logger.debug("Window close requested - initiating shutdown...");
            running = false;
        });

        // Center the window. Wayland forbids clients from positioning their own
        // windows (glfwSetWindowPos would only emit GLFW_FEATURE_UNAVAILABLE);
        // the compositor decides placement there, so skip the attempt entirely.
        if (!isWayland()) {
            try (MemoryStack stack = stackPush()) {
                IntBuffer pWidth = stack.mallocInt(1);
                IntBuffer pHeight = stack.mallocInt(1);

                // Get the window size passed to glfwCreateWindow
                glfwGetWindowSize(window, pWidth, pHeight);

                // Get the resolution of the primary monitor
                GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

                if (vidmode != null) {
                    glfwSetWindowPos(
                        window,
                        (vidmode.width() - pWidth.get(0)) / 2,
                        (vidmode.height() - pHeight.get(0)) / 2
                    );
                } else {
                    System.err.println("Could not get video mode for primary monitor. Window will not be centered.");
                }
            }
        }

        // Capture the VSync target from the monitor the window actually sits on
        // (not always the primary monitor). The FPS cap uses this when VSync is
        // enabled — capping at the display rate avoids tearing without the
        // half-rate fallback the driver's swap-interval=1 imposes on missed
        // frames. Kept in sync as the window moves via the window-pos callback.
        refreshMonitorHz();

        // Monitor hot-plug changes both the monitor list and possibly the
        // fastest available refresh rate; re-derive the VSync cap.
        glfwSetMonitorCallback((mon, event) -> refreshMonitorHz());

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);

        // Apply the persisted VSync preference. We never call
        // glfwSwapInterval(1) — instead VSync = on caps the manual sleep
        // limiter to the monitor's refresh rate.
        applyVsyncSetting();

        // Make the window visible
        glfwShowWindow(window);

        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities();

        // GL is now usable — allow the framebuffer-size callback to touch GL,
        // and set the initial viewport from the actual framebuffer size (which
        // may differ from the requested size on HiDPI displays, and covers any
        // resize events that were skipped while GL was not yet ready).
        glReady = true;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer fbWidth = stack.mallocInt(1);
            java.nio.IntBuffer fbHeight = stack.mallocInt(1);
            glfwGetFramebufferSize(window, fbWidth, fbHeight);
            this.width = fbWidth.get(0);
            this.height = fbHeight.get(0);
            glViewport(0, 0, this.width, this.height);
            Game.getInstance().setWindowDimensions(this.width, this.height);

            // Diagnostic: on some platforms (notably Wayland) the framebuffer
            // size can differ from the window (screen-coordinate) size, which
            // is the space glfwGetCursorPos reports in. If these differ, UI
            // click coordinates must be scaled by framebuffer/window.
            java.nio.IntBuffer winW = stack.mallocInt(1);
            java.nio.IntBuffer winH = stack.mallocInt(1);
            glfwGetWindowSize(window, winW, winH);
            System.out.println("[Display] window=" + winW.get(0) + "x" + winH.get(0)
                    + " framebuffer=" + this.width + "x" + this.height);
        }

        // Establish the initial cursor->UI scale now that both sizes are known.
        updateCursorScale();

        // Set up OpenGL state
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);  // Enable face culling for performance
        glCullFace(GL_BACK);      // Cull back faces
        glFrontFace(GL_CCW);      // Front faces are counter-clockwise

        // Initialize game components
        initializeGameComponents();
    }

    private void initializeGameComponents() {
          MemoryProfiler profiler = MemoryProfiler.getInstance();
          profiler.takeSnapshot("before_initialization");

          // Initialize the renderer with window dimensions
          renderer = new Renderer(width, height);
          profiler.takeSnapshot("after_renderer_init");

          // Initialize the input handler
          inputHandler = new InputHandler(window);

          // Initialize BlockTextureArray (used by Renderer and potentially UI)
          BlockTextureArray textureAtlas = renderer.getBlockTextureArray(); // Get it from renderer after it's created

          // Initialize the Game singleton with core components only (no world/player)
          Game.getInstance().initCoreComponents(renderer, textureAtlas, inputHandler, window);
          Game.getInstance().setWindowDimensions(width, height);
          profiler.takeSnapshot("after_game_init");

          running = true;

          // Log memory usage after initialization
          Game.logDetailedMemoryInfo("Core game components initialized - no world created");

          // Compare memory usage
          profiler.compareSnapshots("before_initialization", "after_game_init");
      }

    @SuppressWarnings("BusyWait")
    private void loop() {
        // Set the clear color
        glClearColor(0.5f, 0.8f, 1.0f, 0.0f);
        
        // Enable depth testing
        glEnable(GL_DEPTH_TEST);
        
        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!glfwWindowShouldClose(window) && running) {
            // Record the start time of this frame (use nanoseconds for better precision)
            long frameStartTime = System.nanoTime();

            // Prepare InputHandler for new frame (e.g., clear single-frame press states)
            if (inputHandler != null) {
                inputHandler.prepareForNewFrame();
            }
            
            // Poll for window events
            glfwPollEvents();
            
            
            // Update Game singleton (for delta time)
            Game.getInstance().update();
            
            // Display debug info periodically (includes memory usage)
            Game.displayDebugInfo();

            // Handle input based on game state
            Game game = Game.getInstance();
            switch (game.getState()) {
                case STARTUP_INTRO -> {
                    if (game.getStartupIntroScreen() != null) {
                        game.getStartupIntroScreen().handleInput(window);
                    }
                }
                case MAIN_MENU -> {
                    // Handle main menu input
                    if (game.getMainMenu() != null) {
                        game.getMainMenu().handleInput(window);
                    }
                }
                case WORLD_SELECT -> {
                    // Handle world select screen input
                    if (game.getWorldSelectScreen() != null) {
                        game.getWorldSelectScreen().handleInput(window);
                    }
                }
                case CHARACTER_CREATION -> {
                    if (game.getCharacterCreationScreen() != null) {
                        game.getCharacterCreationScreen().handleInput(window);
                    }
                }
                case TERRAIN_MAPPER -> {
                    // Handle terrain mapper input
                    if (game.getTerrainMapperScreen() != null) {
                        game.getTerrainMapperScreen().handleInput(window);
                    }
                }
                case LOADING -> {
                    // Handle loading screen input (primarily for error recovery)
                    if (game.getLoadingScreen() != null) {
                        game.getLoadingScreen().handleInput(window);
                    }
                }
                case SETTINGS -> {
                    // Handle settings menu input
                    if (game.getSettingsMenu() != null) {
                        game.getSettingsMenu().handleInput(window);
                    }
                }
                case MULTIPLAYER_MENU -> {
                    if (game.getMultiplayerMenu() != null) game.getMultiplayerMenu().handleInput(window);
                }
                case HOST_WORLD_SELECT -> {
                    if (game.getHostWorldScreen() != null) game.getHostWorldScreen().handleInput(window);
                }
                case JOIN_WORLD_SCREEN -> {
                    if (game.getJoinWorldScreen() != null) game.getJoinWorldScreen().handleInput(window);
                }
                case PLAYING, PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, CHARACTER_SHEET_UI, FURNACE_UI -> {
                    // Handle in-game input if not a purely modal UI like MainMenu
                    // Game.update() will also check its internal state for what to update (e.g. player/world if not paused)
                    if (inputHandler != null) {
                        // Pass input to screens that might need it, even if game world is paused
                        // Note: InventoryScreen & CharacterScreen input is handled inside InputHandler.handleInput()
                        // below — do NOT call handleMouseInput here (duplicate call broke single-click drag)
                        if (game.getRecipeBookScreen() != null && game.getRecipeBookScreen().isVisible()) {
                             game.getRecipeBookScreen().handleInput();
                        } else if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
                             game.getWorkbenchScreen().handleInput(inputHandler);
                        }
                        // General player input handling (movement, interaction) happens if not paused for UI.
                        // InputHandler's own logic + Game.update() decides if player/world updates proceed.
                        Player player = game.getPlayer();
                        if (player != null) {
                            inputHandler.handleInput(player);
                        }
                    }
                }
                default -> {
                    // Optional: handle any other states or do nothing
                }
            }
            // Game.update() itself decides what parts of the game state to update (e.g. only player if playing)
            // update(); // Game.getInstance().update() is already called above and handles state-specific updates

            // Render frame
            render();
            
            // Swap the color buffers
            glfwSwapBuffers(window);
            
            // Calculate how long the frame took to process
            long frameEndTime = System.nanoTime();
            long frameTimeNanos = frameEndTime - frameStartTime;
            
            // Sleep if we're running faster than the current target. The budget
            // reflects the lowest active cap (VSync refresh rate and/or Max FPS);
            // a budget of 0 means uncapped, so the sleep below is skipped.
            long frameBudgetNanos = currentFrameBudgetNanos();
            if (frameTimeNanos < frameBudgetNanos) {
                try {
                    // Sleep to cap FPS (convert back to milliseconds for Thread.sleep)
                    long sleepTimeNanos = frameBudgetNanos - frameTimeNanos;
                    long sleepTimeMillis = sleepTimeNanos / 1_000_000;
                    int sleepTimeNanosRemainder = (int)(sleepTimeNanos % 1_000_000);
                    
                    if (sleepTimeMillis > 0) {
                        Thread.sleep(sleepTimeMillis, sleepTimeNanosRemainder);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break; // Exit loop if interrupted
                }
            }
        }
    }
    
    private void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        Game game = Game.getInstance();
        Renderer renderer = Game.getRenderer();
        
        switch (game.getState()) {
            case STARTUP_INTRO -> {
                com.stonebreak.ui.startupIntro.SonarArtsIntroScreen intro = game.getStartupIntroScreen();
                if (intro != null) intro.render(width, height);
            }
            case MAIN_MENU -> {
                // Skija-backed; same GL-bracketing contract as world select.
                com.stonebreak.ui.MainMenu mm = game.getMainMenu();
                if (mm != null) mm.render(width, height);
            }
            case WORLD_SELECT -> {
                // Skija backend brackets its own GL state; do not wrap in a NanoVG frame.
                WorldSelectScreen wss = game.getWorldSelectScreen();
                if (wss != null) wss.render(width, height);
            }
            case CHARACTER_CREATION -> {
                com.stonebreak.ui.characterCreation.CharacterCreationScreen ccs = game.getCharacterCreationScreen();
                if (ccs != null) ccs.render(width, height);
            }
            case TERRAIN_MAPPER -> {
                // Skija-backed MasonryUI; brackets GL itself.
                com.stonebreak.ui.terrainMapper.TerrainMapperScreen tms = game.getTerrainMapperScreen();
                if (tms != null) tms.render(width, height);
            }
            case LOADING -> {
                // Skija-backed; brackets GL itself.
                LoadingScreen ls = game.getLoadingScreen();
                if (ls != null) ls.render(width, height);
            }
            case SETTINGS -> {
                // Skija-backed MasonryUI; brackets GL itself.
                SettingsMenu sm = game.getSettingsMenu();
                if (sm != null) sm.render(width, height);
            }
            case MULTIPLAYER_MENU -> {
                if (game.getMultiplayerMenu() != null) game.getMultiplayerMenu().render(width, height);
            }
            case HOST_WORLD_SELECT -> {
                if (game.getHostWorldScreen() != null) game.getHostWorldScreen().render(width, height);
            }
            case JOIN_WORLD_SCREEN -> {
                if (game.getJoinWorldScreen() != null) game.getJoinWorldScreen().render(width, height);
            }
            default -> render3DGameState(game, renderer);
        }
        
        renderDebugOverlay(renderer);
    }

    private void render3DGameState(Game game, Renderer renderer) {
        logFirstRender(game);

        if (!resetOpenGLState()) return;

        render3DWorld(game, renderer);
        renderDeferredElements();

        // Render underwater overlay BEFORE UI so it doesn't tint the hotbar/menus
        renderer.getOverlayRenderer().renderUnderwaterOverlay(game, width, height);

        renderGameUI(game, renderer);
        renderFullscreenMenus(game);
        renderer.renderOverlay(game, width, height);
        renderPauseMenu(game, renderer);
    }

    private void logFirstRender(Game game) {
        if (firstRender) {
            System.out.println("First 3D render after loading - State: " + game.getState());
            firstRender = false;
        }
    }

    private boolean resetOpenGLState() {
        try {
            long currentContext = glfwGetCurrentContext();
            if (currentContext != window) {
                System.err.println("CRITICAL: Wrong OpenGL context - resetting");
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
            }
            
            if (firstRender) {
                String version = glGetString(GL_VERSION);
                System.out.println("OpenGL Version: " + version);
            }
            
            glUseProgram(0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_CULL_FACE);

            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            
            int finalError = glGetError();
            if (finalError != GL_NO_ERROR && firstRender) {
                System.err.println("Error after complete state reset: 0x" + Integer.toHexString(finalError));
            }
            
            return true;
        } catch (Exception e) {
            System.err.println("Exception during OpenGL state reset: " + e.getMessage());
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            return false;
        }
    }

    private void render3DWorld(Game game, Renderer renderer) {
        try {
            World world = game.getWorld();
            Player player = game.getPlayer();
            if (world != null && player != null) {
                renderer.renderWorld(world, player, game.getTotalTimeElapsed());
            }
        } catch (Exception e) {
            logRenderCrash(game, e);
            throw new RuntimeException("Render crash - see crash_log.txt", e);
        }
    }

    private void logRenderCrash(Game game, Exception e) {
        World world = game.getWorld();
        Player player = game.getPlayer();

        System.err.println("CRITICAL CRASH: Exception in renderWorld() - State: " + game.getState());
        System.err.println("Time: " + java.time.LocalDateTime.now());
        System.err.println("Player pos: " + (player != null ? player.getPosition().x + ", " + player.getPosition().y + ", " + player.getPosition().z : "null"));
        System.err.println("World chunks loaded: " + (world != null ? world.getLoadedChunkCount() : "null"));
        System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
        System.err.println("Exception: " + e.getMessage());
        System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));

        try (java.io.FileWriter fw = new java.io.FileWriter("crash_log.txt", true)) {
            fw.write("=== CRASH LOG " + java.time.LocalDateTime.now() + " ===\n");
            fw.write("State: " + game.getState() + "\n");
            fw.write("Player: " + (player != null ? player.getPosition().x + ", " + player.getPosition().y + ", " + player.getPosition().z : "null") + "\n");
            fw.write("Chunks: " + (world != null ? world.getLoadedChunkCount() : "null") + "\n");
            fw.write("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB\n");
            fw.write("Exception: " + e.getMessage() + "\n");
            fw.write("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()) + "\n\n");
        } catch (java.io.IOException logEx) {
            System.err.println("Failed to write crash log: " + logEx.getMessage());
        }
    }

    private void renderGameUI(Game game, Renderer renderer) {
        if (renderer == null) return;

        if (game.getState() == GameState.PLAYING || game.getState() == GameState.PAUSED || game.getState() == GameState.INVENTORY_UI || game.getState() == GameState.RECIPE_BOOK_UI || game.getState() == GameState.CHARACTER_SHEET_UI || game.getState() == GameState.FURNACE_UI || game.getState() == GameState.WORKBENCH_UI) {
            renderCrosshair(game, renderer);
            renderInventoryAndHotbar(game);
            renderChat(game, renderer);
        }

        if (game.getState() == GameState.PLAYING) {
            com.stonebreak.player.Player player = Game.getPlayer();
            if (player != null) {
                DamageNumberRenderer dmg = DamageNumberRenderer.getInstance();
                dmg.update(Game.getDeltaTime());
                dmg.render(renderer.getProjectionMatrix(),
                           player.getViewMatrix(),
                           width, height);
                com.stonebreak.rendering.UI.components.QuarryMarkerRenderer.getInstance()
                        .render(renderer.getProjectionMatrix(), player.getViewMatrix(), width, height);
                com.stonebreak.rendering.UI.components.DoubtMarkerRenderer.getInstance()
                        .render(renderer.getProjectionMatrix(), player.getViewMatrix(), width, height);
                com.stonebreak.rendering.UI.components.EnemyAwarenessRenderer.getInstance()
                        .render(renderer.getProjectionMatrix(), player.getViewMatrix(), width, height);
                com.stonebreak.rendering.UI.components.StealthHudRenderer.getInstance()
                        .render(width, height);
            }
        }

        // Render recipe book as overlay, not fullscreen
        if (game.getState() == GameState.RECIPE_BOOK_UI) {
            RecipeScreen recipeScreen = game.getRecipeBookScreen();
            if (recipeScreen != null && recipeScreen.isVisible()) {
                recipeScreen.render();
            }
        }

        renderActivePauseMenu(game, renderer);
    }

    private void renderCrosshair(Game game, Renderer renderer) {
        if (game.getState() == GameState.PLAYING) {
            InventoryScreen inventoryScreen = game.getInventoryScreen();
            WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
            FurnaceScreen furnaceScreen = game.getFurnaceScreen();

            // Don't render crosshair if any UI screen is open
            boolean anyUIVisible = (inventoryScreen != null && inventoryScreen.isVisible()) ||
                                   (workbenchScreen != null && workbenchScreen.isVisible()) ||
                                   (furnaceScreen != null && furnaceScreen.isVisible());

            if (!anyUIVisible) {
                renderer.getUIRenderer().renderCrosshair(width, height);
            }
        }
    }

    private void renderInventoryAndHotbar(Game game) {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        com.stonebreak.ui.characterScreen.CharacterScreen characterScreen = game.getCharacterScreen();
        GameState state = game.getState();

        // Recipe book takes the foreground; render just the hotbar underneath
        // so hover detection over the inventory grid doesn't run.
        if (state == GameState.RECIPE_BOOK_UI) {
            if (inventoryScreen != null) {
                inventoryScreen.renderHotbar(width, height);
            }
            return;
        }

        // Furnace and Workbench are rendered exclusively by renderFullscreenMenus
        // (outside the UIFrame bracket). Rendering them here too causes a double-render
        // where the second Skija pass covers the GL block-texture icons from the first.
        if (state == GameState.FURNACE_UI || state == GameState.WORKBENCH_UI) {
            return;
        }

        if (characterScreen != null && characterScreen.isVisible()
                && state == GameState.CHARACTER_SHEET_UI) {
            // Character screen is open — render it, but keep the hotbar visible below
            characterScreen.render(width, height);
            if (inventoryScreen != null) {
                inventoryScreen.renderHotbar(width, height);
            }
        } else if (inventoryScreen != null) {
            if (inventoryScreen.isVisible()) {
                inventoryScreen.render(width, height);
            } else {
                inventoryScreen.renderHotbar(width, height);
            }
        }
    }

    private void renderChat(Game game, Renderer renderer) {
        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null) {
            renderer.renderChat(chatSystem, width, height);
        }
    }

    private void renderActivePauseMenu(Game game, Renderer renderer) {
        if ((game.getState() == GameState.PLAYING || game.getState() == GameState.PAUSED)
            && game.getPauseMenu() != null && game.getPauseMenu().isVisible()) {
            // Skija-backed; brackets its own GL state.
            game.getPauseMenu().render(width, height);
        }
    }

    private void renderFullscreenMenus(Game game) {
        if (game.getState() == GameState.WORKBENCH_UI) {
            WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
            if (workbenchScreen != null && workbenchScreen.isVisible()) {
                workbenchScreen.render();
            }
        }
        if (game.getState() == GameState.FURNACE_UI) {
            FurnaceScreen furnaceScreen = game.getFurnaceScreen();
            if (furnaceScreen != null && furnaceScreen.isVisible()) {
                furnaceScreen.render();
            }
        }
    }

    private void renderDeferredElements() {
        // No deferred elements to render
    }


    private void renderPauseMenu(Game game, Renderer renderer) {
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible() && renderer != null) {
            // Skija-backed; brackets its own GL state — no NanoVG frame here.
            pauseMenu.render(width, height);
            renderer.getUIRenderer().renderPauseMenuDepthCurtain();
        }

        // Render statistics screen
        com.stonebreak.ui.statisticsScreen.StatisticsScreen statsScreen = game.getStatisticsScreen();
        if (statsScreen != null && statsScreen.isVisible() && renderer != null) {
            statsScreen.render(width, height);
        }

        // Render glossary screen
        com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if (glossaryScreen != null && glossaryScreen.isVisible() && renderer != null) {
            glossaryScreen.render(width, height);
        }

        // Render death menu if player is dead
        com.stonebreak.ui.DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null && deathMenu.isVisible() && renderer != null) {
            deathMenu.render(width, height);
        }
    }

    private void renderDebugOverlay(Renderer renderer) {
        DebugOverlay debugOverlay = Game.getDebugOverlay();
        if (debugOverlay != null && debugOverlay.isVisible()) {
            debugOverlay.renderWireframes(renderer);

            if (renderer != null) {
                // All debug panels (left RAM/VRAM + right debug info) use
                // MasonryUI/Skija — single GL bracket covers them.
                debugOverlay.renderResourcePanels(renderer, width, height);
            }
        }
    }
    
    private void cleanup() {
        Game.logDetailedMemoryInfo("Before cleanup");

        // CRITICAL: Clean up OpenGL resources BEFORE destroying the window
        // OpenGL context must be current when cleaning up OpenGL resources

        // Ensure OpenGL context is current
        if (window != 0) {
            glfwMakeContextCurrent(window);
        }

        // Clean up CBR resource manager FIRST (if initialized)
        // This must happen on the OpenGL thread to avoid "No context is current" errors
        try {
            if (com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.isInitialized()) {
                com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.getInstance().close();
                logger.debug("CBRResourceManager cleaned up successfully");
            }
        } catch (Exception e) {
            logger.error("Error cleaning up CBRResourceManager", e);
        }
        Game.logDetailedMemoryInfo("After CBR cleanup");

        // Clean up renderer resources (while OpenGL context is still valid)
        if (renderer != null) {
            renderer.cleanup();
            Game.logDetailedMemoryInfo("After renderer cleanup");
        }

        // Clean up world resources (while OpenGL context is still valid)
        World world = Game.getInstance().getWorld();
        if (world != null) {
            world.cleanup();
            Game.logDetailedMemoryInfo("After world cleanup");
        }

        // Clean up game resources
        Game.getInstance().cleanup();
        Game.logDetailedMemoryInfo("After game cleanup");

        // NOW it's safe to destroy the window (destroys OpenGL context)
        if (window != 0) {
            glfwDestroyWindow(window);
            Game.logDetailedMemoryInfo("After GLFW window cleanup");
        }

        // Force garbage collection and report
        Game.forceGCAndReport("Final cleanup");

        // Terminate GLFW and free the error callback
        GLFWMonitorCallback monitorCallback = glfwSetMonitorCallback(null);
        if (monitorCallback != null) {
            monitorCallback.free();
        }
        glfwTerminate();
        GLFWErrorCallback prevCallback = glfwSetErrorCallback(null);
        if (prevCallback != null) {
            prevCallback.free();
        }
    }
    


    /**
     * Return the window handle.
     */
    public static long getWindowHandle() {
        return instance != null ? instance.window : 0;
    }
}
