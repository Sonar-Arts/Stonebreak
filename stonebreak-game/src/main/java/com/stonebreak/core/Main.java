package com.stonebreak.core;


import java.nio.IntBuffer;

import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.config.Settings;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import org.lwjgl.*;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.util.MemoryProfiler;
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
    
    // Window handle
    private long window;
    
    // Game settings - loaded from Settings at startup
    private int width;
    private int height;

    /**
     * FPS cap used when VSync is disabled. Picked above common refresh rates
     * so high-refresh monitors aren't held back by it.
     */
    private static final int UNCAPPED_TARGET_FPS = 240;

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
            System.out.println("Stonebreak shutdown complete.");
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
        boolean enabled = Settings.getInstance().isVsyncEnabled();
        System.out.println("[Display] VSync " + (enabled ? "enabled (cap " + monitorRefreshHz + " Hz)"
                                                          : "disabled (cap " + UNCAPPED_TARGET_FPS + " FPS)"));
    }

    /**
     * Returns the current per-frame nanosecond budget for the manual FPS
     * limiter. Picks the monitor refresh rate when VSync is on, the
     * uncapped target otherwise.
     */
    private static long currentFrameBudgetNanos() {
        int targetHz = Settings.getInstance().isVsyncEnabled()
                ? monitorRefreshHz
                : UNCAPPED_TARGET_FPS;
        if (targetHz <= 0) targetHz = 60; // defensive — never divide by zero
        return 1_000_000_000L / targetHz;
    }
    
    private void init() {
        // Setup an error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // Request a compatible profile - this allows OpenGL 3.2 features in case it's available
        // but falls back to compatibility profile if needed
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);

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
            // Pass character input to InputHandler for chat handling
            else if (inputHandler != null) {
                inputHandler.handleCharacterInput(character);
            }
        });

        // Setup window resize callback
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            this.width = w;
            this.height = h;
            glViewport(0, 0, w, h);
            if (renderer != null) {
                renderer.updateProjectionMatrix(w, h);
            }
            // Update the Game singleton with new dimensions
            Game.getInstance().setWindowDimensions(w, h);
        });

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
                        glfwGetCursorPos(window, xpos, ypos);
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
                    glfwGetCursorPos(window, xpos, ypos);
                    if (game.getWorldSelectScreen() != null) {
                        game.getWorldSelectScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (game != null && game.getState() == GameState.SETTINGS) {
                // Handle settings menu clicks
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    glfwGetCursorPos(window, xpos, ypos);
                    if (game.getSettingsMenu() != null) {
                        game.getSettingsMenu().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (game != null && game.getState() == GameState.TERRAIN_MAPPER) {
                // Handle terrain mapper clicks
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    glfwGetCursorPos(window, xpos, ypos);
                    if (game.getTerrainMapperScreen() != null) {
                        game.getTerrainMapperScreen().handleMouseClick(xpos.get(), ypos.get(), width, height, button, action);
                    }
                }
            } else if (inputHandler != null) {
                inputHandler.processMouseButton(button, action, mods);
            }
        });

        // Setup cursor position callback
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            Game game = Game.getInstance();

            // Process mouse movement for camera look (if mouse is captured)
            MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();
            if (mouseCaptureManager != null) {
                mouseCaptureManager.processMouseMovement(xpos, ypos);
            }

            // Update InputHandler for UI interactions (always needed for UI)
            if (inputHandler != null) {
                inputHandler.updateMousePosition((float)xpos, (float)ypos);
            }

            // Handle main menu hover events
            if (game.getState() == GameState.MAIN_MENU && game.getMainMenu() != null) {
                game.getMainMenu().handleMouseMove(xpos, ypos, width, height);
            }
            // Handle world select screen hover events
            else if (game.getState() == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
                game.getWorldSelectScreen().handleMouseMove(xpos, ypos, width, height);
            }
            // Handle settings menu hover events
            else if (game.getState() == GameState.SETTINGS && game.getSettingsMenu() != null) {
                game.getSettingsMenu().handleMouseMove(xpos, ypos, width, height);
            }
            // Handle terrain mapper hover events
            else if (game.getState() == GameState.TERRAIN_MAPPER && game.getTerrainMapperScreen() != null) {
                game.getTerrainMapperScreen().handleMouseMove(xpos, ypos, width, height);
            }
        });

        // Setup scroll callback for world select screen and hotbar selection
        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            Game game = Game.getInstance();
            if (game != null && game.getState() == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
                game.getWorldSelectScreen().handleMouseWheel(yoffset);
            } else if (game != null && game.getState() == GameState.TERRAIN_MAPPER && game.getTerrainMapperScreen() != null) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer xpos = stack.mallocDouble(1);
                    java.nio.DoubleBuffer ypos = stack.mallocDouble(1);
                    glfwGetCursorPos(window, xpos, ypos);
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
            System.out.println("Window close requested - initiating shutdown...");
            running = false;
        });

        // Get the thread stack and push a new frame
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);
            
            // Get the resolution of the primary monitor
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            if (vidmode != null) {
                glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
                );
                // Capture the monitor's refresh rate as the VSync target. This
                // is what the FPS cap uses when VSync is enabled — capping at
                // the display rate avoids tearing without the half-rate
                // fallback the driver's swap-interval=1 imposes on missed frames.
                int hz = vidmode.refreshRate();
                if (hz > 0) {
                    monitorRefreshHz = hz;
                    System.out.println("[Display] Monitor refresh rate: " + hz + " Hz");
                }
            } else {
                // Fallback or log error if vidmode is null
                System.err.println("Could not get video mode for primary monitor. Window will not be centered.");
                // Optionally, center based on some default or last known screen size
                // For now, we just don't center it.
            }
        }

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

          // Initialize TextureAtlas (used by Renderer and potentially UI)
          TextureAtlas textureAtlas = renderer.getTextureAtlas(); // Get it from renderer after it's created

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
                case PLAYING, PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, CHARACTER_SHEET_UI -> {
                    // Handle in-game input if not a purely modal UI like MainMenu
                    // Game.update() will also check its internal state for what to update (e.g. player/world if not paused)
                    if (inputHandler != null) {
                        // Pass input to screens that might need it, even if game world is paused
                        if (game.getRecipeBookScreen() != null && game.getRecipeBookScreen().isVisible()) {
                             game.getRecipeBookScreen().handleInput();
                        } else if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
                             game.getWorkbenchScreen().handleInput(inputHandler);
                        } else if (game.getInventoryScreen() != null && game.getInventoryScreen().isVisible()){
                             game.getInventoryScreen().handleMouseInput(width, height); // InventoryScreen has specific mouse handling for drag/drop
                        } else if (game.getCharacterScreen() != null && game.getCharacterScreen().isVisible()) {
                             game.getCharacterScreen().handleMouseInput(width, height);
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
            
            // Sleep if we're running faster than the current target.
            // VSync = on  → target = monitor refresh rate (cap-based "VSync")
            // VSync = off → target = UNCAPPED_TARGET_FPS
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
            case TERRAIN_MAPPER -> {
                // Skija-backed MasonryUI; brackets GL itself.
                com.stonebreak.ui.terrainMapper.TerrainMapperScreen tms = game.getTerrainMapperScreen();
                if (tms != null) tms.render(width, height);
            }
            case LOADING -> renderUIState(renderer, game.getLoadingScreen());
            case SETTINGS -> {
                // Skija-backed MasonryUI; brackets GL itself.
                SettingsMenu sm = game.getSettingsMenu();
                if (sm != null) sm.render(width, height);
            }
            default -> render3DGameState(game, renderer);
        }
        
        renderDebugOverlay(renderer);
    }

    private void renderUIState(Renderer renderer, Object screen) {
        if (renderer == null || screen == null) return;

        renderer.beginUIFrame(width, height, 1.0f);
        if (screen instanceof com.stonebreak.ui.LoadingScreen loadingScreen) {
            loadingScreen.render(width, height);
        }
        renderer.endUIFrame();
    }

    private void render3DGameState(Game game, Renderer renderer) {
        logFirstRender(game);

        if (!resetOpenGLState()) return;

        render3DWorld(game, renderer);
        renderDeferredElements();

        // Render underwater overlay BEFORE UI so it doesn't tint the hotbar/menus
        renderer.getOverlayRenderer().renderUnderwaterOverlay(game, width, height);

        renderGameUI(game, renderer);
        renderCharacterScreen(game);
        renderFullscreenMenus(game);
        renderer.renderOverlay(game, width, height);
        renderPauseMenu(game, renderer);
    }

    private void renderCharacterScreen(Game game) {
        if (game.getState() == GameState.CHARACTER_SHEET_UI) {
            com.stonebreak.ui.characterScreen.CharacterScreen cs = game.getCharacterScreen();
            if (cs != null && cs.isVisible()) {
                cs.render(width, height);
            }
        }
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

        renderer.beginUIFrame(width, height, 1.0f);

        if (game.getState() == GameState.PLAYING || game.getState() == GameState.PAUSED || game.getState() == GameState.INVENTORY_UI || game.getState() == GameState.RECIPE_BOOK_UI || game.getState() == GameState.CHARACTER_SHEET_UI) {
            renderCrosshair(game, renderer);
            renderInventoryAndHotbar(game);
            renderChat(game, renderer);
        }

        // Render recipe book as overlay, not fullscreen
        if (game.getState() == GameState.RECIPE_BOOK_UI) {
            RecipeScreen recipeScreen = game.getRecipeBookScreen();
            if (recipeScreen != null && recipeScreen.isVisible()) {
                recipeScreen.render();
            }
        }

        renderActivePauseMenu(game, renderer);
        renderer.endUIFrame();
    }

    private void renderCrosshair(Game game, Renderer renderer) {
        if (game.getState() == GameState.PLAYING) {
            InventoryScreen inventoryScreen = game.getInventoryScreen();
            WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();

            // Don't render crosshair if any UI screen is open
            boolean anyUIVisible = (inventoryScreen != null && inventoryScreen.isVisible()) ||
                                   (workbenchScreen != null && workbenchScreen.isVisible());

            if (!anyUIVisible) {
                renderer.getUIRenderer().renderCrosshair(width, height);
            }
        }
    }

    private void renderInventoryAndHotbar(Game game) {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        GameState state = game.getState();

        // Recipe book takes the foreground; render just the hotbar underneath
        // so hover detection over the inventory grid doesn't run.
        if (state == GameState.RECIPE_BOOK_UI) {
            if (inventoryScreen != null) {
                inventoryScreen.renderHotbar(width, height);
            }
            return;
        }

        // Check which screen is visible and render accordingly
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            workbenchScreen.render();
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

        // Render death menu if player is dead
        com.stonebreak.ui.DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null && deathMenu.isVisible() && renderer != null) {
            renderer.beginUIFrame(width, height, 1.0f);
            deathMenu.render(renderer.getUIRenderer(), width, height);
            renderer.endUIFrame();
        }
    }

    private void renderDebugOverlay(Renderer renderer) {
        DebugOverlay debugOverlay = Game.getDebugOverlay();
        if (debugOverlay != null && debugOverlay.isVisible()) {
            debugOverlay.renderWireframes(renderer);

            if (renderer != null) {
                // Right-side text overlay uses NanoVG.
                renderer.beginUIFrame(width, height, 1.0f);
                debugOverlay.render(renderer.getUIRenderer());
                renderer.endUIFrame();
                // Left-side resource cards use MasonryUI/Skija (separate GL bracket).
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
                System.out.println("CBRResourceManager cleaned up successfully");
            }
        } catch (Exception e) {
            System.err.println("Error cleaning up CBRResourceManager: " + e.getMessage());
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
