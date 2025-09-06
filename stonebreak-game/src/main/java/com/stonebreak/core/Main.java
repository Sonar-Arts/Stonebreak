package com.stonebreak.core;

import java.nio.*;

import com.stonebreak.rendering.textures.TextureAtlas;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import org.lwjgl.opengl.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import org.lwjgl.system.*;
import static org.lwjgl.system.MemoryStack.*;

import com.stonebreak.chat.*;
import com.stonebreak.config.*;
import com.stonebreak.input.*;
import com.stonebreak.player.*;
import com.stonebreak.rendering.*;
import com.stonebreak.textures.atlas.TextureAtlasBuilder;
import com.stonebreak.ui.*;
import com.stonebreak.util.*;
import com.stonebreak.world.*;

/**
 * Main class for the Stonebreak game - a voxel-based sandbox.
 */
public class Main {
    
    // Window handle
    private long window;
    
    // Game settings - loaded from Settings at startup
    private int width;
    private int height;
    private final String title = "Stonebreak";
    private static final int TARGET_FPS = 144;
    private static final long FRAME_TIME_NANOS = (long)(1_000_000_000.0 / TARGET_FPS);
    
    // Game state
    private boolean running = false;
    private boolean firstRender = true;
    
    // Game components
    private World world;
    private Player player;
    private Renderer renderer;
    private InputHandler inputHandler;
    private TextureAtlas textureAtlas; // Added TextureAtlas
    
    // Static references for system-wide access
    private static Main instance;
    
    public static void main(String[] args) {
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
            System.exit(0);
        }
    }
    
    private void loadSettings() {
        Settings settings = Settings.getInstance();
        this.width = settings.getWindowWidth();
        this.height = settings.getWindowHeight();
        System.out.println("Settings loaded - Window size: " + width + "x" + height);
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
        window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create the GLFW window");
        }
          // Setup key callback
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            // Pass key events to InputHandler for chat handling
            if (inputHandler != null) {
                inputHandler.handleKeyInput(key, action);
            }
        });
        
        // Setup character callback for chat text input
        glfwSetCharCallback(window, (win, codepoint) -> {
            if (inputHandler != null) {
                inputHandler.handleCharacterInput((char) codepoint);
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
            if (game != null && game.getState() == GameState.MAIN_MENU) {
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
            // Handle settings menu hover events
            else if (game.getState() == GameState.SETTINGS && game.getSettingsMenu() != null) {
                game.getSettingsMenu().handleMouseMove(xpos, ypos, width, height);
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
            } else {
                // Fallback or log error if vidmode is null
                System.err.println("Could not get video mode for primary monitor. Window will not be centered.");
                // Optionally, center based on some default or last known screen size
                // For now, we just don't center it.
            }
        }
          // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        
        // Disable v-sync since we're implementing our own FPS limiter
        glfwSwapInterval(0);
        
        
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
        glDisable(GL_CULL_FACE); // Disable face culling for proper double-sided rendering
        
        // Initialize game components
        initializeGameComponents();
    }
      private void initializeGameComponents() {
          MemoryProfiler profiler = MemoryProfiler.getInstance();
          profiler.takeSnapshot("before_initialization");
          
          // Check if texture atlas needs regeneration
          regenerateAtlasIfNeeded();
          
          // Initialize the renderer with window dimensions
          renderer = new Renderer(width, height);
          profiler.takeSnapshot("after_renderer_init");
          
          // Initialize the input handler
          inputHandler = new InputHandler(window);
          
          // Initialize the world
          world = new World();
          profiler.takeSnapshot("after_world_init");
          
          // Initialize the player
          player = new Player(world);
          
          // Set initial camera position
          player.setPosition(0, 100, 0);
  
          // Initialize TextureAtlas (used by Renderer and potentially UI)
          textureAtlas = renderer.getTextureAtlas(); // Get it from renderer after it's created
  
            // Initialize the Game singleton
          // Pass inputHandler to Game's init method
          Game.getInstance().init(world, player, renderer, textureAtlas, inputHandler, window);
          Game.getInstance().setWindowDimensions(width, height);
          profiler.takeSnapshot("after_game_init");
          
          running = true;
          
          // Log memory usage after initialization
          Game.logDetailedMemoryInfo("Game components initialized");
          
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
                case MAIN_MENU -> {
                    // Handle main menu input
                    if (game.getMainMenu() != null) {
                        game.getMainMenu().handleInput(window);
                    }
                }
                case LOADING -> {
                    // Loading screen - no input handling needed
                    // User should wait for world generation to complete
                }
                case SETTINGS -> {
                    // Handle settings menu input
                    if (game.getSettingsMenu() != null) {
                        game.getSettingsMenu().handleInput(window);
                    }
                }
                case PLAYING, PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI -> {
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
                        }
                        // General player input handling (movement, interaction) happens if not paused for UI.
                        // InputHandler's own logic + Game.update() decides if player/world updates proceed.
                        inputHandler.handleInput(player);
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
            
            // Sleep if we're running faster than the target FPS
            if (frameTimeNanos < FRAME_TIME_NANOS) {
                try {
                    // Sleep to cap FPS (convert back to milliseconds for Thread.sleep)
                    long sleepTimeNanos = FRAME_TIME_NANOS - frameTimeNanos;
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
            case MAIN_MENU -> renderUIState(renderer, game.getMainMenu());
            case LOADING -> renderUIState(renderer, game.getLoadingScreen());
            case SETTINGS -> renderUIState(renderer, game.getSettingsMenu());
            default -> render3DGameState(game, renderer);
        }
        
        renderDebugOverlay(renderer);
    }

    private void renderUIState(Renderer renderer, Object screen) {
        if (renderer == null || screen == null) return;
        
        renderer.beginUIFrame(width, height, 1.0f);
        if (screen instanceof com.stonebreak.ui.MainMenu mainMenu) {
            mainMenu.render(width, height);
        } else if (screen instanceof com.stonebreak.ui.LoadingScreen loadingScreen) {
            loadingScreen.render(width, height);
        } else if (screen instanceof com.stonebreak.ui.SettingsMenu settingsMenu) {
            settingsMenu.render(width, height);
        }
        renderer.endUIFrame();
    }

    private void render3DGameState(Game game, Renderer renderer) {
        logFirstRender(game);
        
        if (!resetOpenGLState()) return;
        
        render3DWorld(game, renderer);
        renderDeferredElements(game, renderer);
        renderGameUI(game, renderer);
        renderFullscreenMenus(game);
        renderTooltips(game, renderer);
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
            
            while (glGetError() != GL_NO_ERROR) { /* clear */ }
            
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
            renderer.renderWorld(world, player, game.getTotalTimeElapsed());
        } catch (Exception e) {
            logRenderCrash(game, e);
            throw new RuntimeException("Render crash - see crash_log.txt", e);
        }
    }

    private void logRenderCrash(Game game, Exception e) {
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
        
        if (game.getState() == GameState.PLAYING || game.getState() == GameState.PAUSED) {
            renderCrosshair(game, renderer);
            renderInventoryAndHotbar(game);
            renderChat(game, renderer);
        }
        
        renderActivePauseMenu(game, renderer);
        renderer.endUIFrame();
    }

    private void renderCrosshair(Game game, Renderer renderer) {
        if (game.getState() == GameState.PLAYING) {
            InventoryScreen inventoryScreen = game.getInventoryScreen();
            if (inventoryScreen == null || !inventoryScreen.isVisible()) {
                renderer.getUIRenderer().renderCrosshair(width, height);
            }
        }
    }

    private void renderInventoryAndHotbar(Game game) {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null) {
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
            game.getPauseMenu().render(renderer.getUIRenderer(), width, height);
        }
    }

    private void renderFullscreenMenus(Game game) {
        if (game.getState() == GameState.RECIPE_BOOK_UI) {
            RecipeBookScreen recipeBookScreen = game.getRecipeBookScreen();
            if (recipeBookScreen != null && recipeBookScreen.isVisible()) {
                recipeBookScreen.render();
            }
        } else if (game.getState() == GameState.WORKBENCH_UI) {
            WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
            if (workbenchScreen != null && workbenchScreen.isVisible()) {
                workbenchScreen.render();
            }
        }
    }

    private void renderDeferredElements(Game game, Renderer renderer) {
        renderer.getBlockRenderer().renderBlockDrops(world, renderer.getProjectionMatrix());
    }

    private void renderTooltips(Game game, Renderer renderer) {
        if (renderer == null) return;
        
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        RecipeBookScreen recipeBookScreen = game.getRecipeBookScreen();
        
        if (inventoryScreen != null) {
            renderer.beginUIFrame(width, height, 1.0f);
            if (inventoryScreen.isVisible()) {
                inventoryScreen.renderTooltipsOnly(width, height);
            } else {
                inventoryScreen.renderHotbarTooltipsOnly(width, height);
            }
            renderer.endUIFrame();
        }
        
        if (recipeBookScreen != null && recipeBookScreen.isVisible()) {
            recipeBookScreen.renderTooltipsOnly();
        }
    }

    private void renderPauseMenu(Game game, Renderer renderer) {
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible() && renderer != null) {
            renderer.beginUIFrame(width, height, 1.0f);
            pauseMenu.render(renderer.getUIRenderer(), width, height);
            renderer.endUIFrame();
            renderer.getUIRenderer().renderPauseMenuDepthCurtain();
        }
    }

    private void renderDebugOverlay(Renderer renderer) {
        DebugOverlay debugOverlay = Game.getDebugOverlay();
        if (debugOverlay != null && debugOverlay.isVisible()) {
            debugOverlay.renderWireframes(renderer);
            
            if (renderer != null) {
                renderer.beginUIFrame(width, height, 1.0f);
                debugOverlay.render(renderer.getUIRenderer());
                renderer.endUIFrame();
            }
        }
    }
    
    /**
     * Check if texture atlas needs regeneration and regenerate if necessary.
     * This ensures textures are up-to-date before rendering starts.
     */
    private void regenerateAtlasIfNeeded() {
        try {
            System.out.println("Checking if texture atlas regeneration is needed...");
            
            TextureAtlasBuilder atlasBuilder = new TextureAtlasBuilder();
            
            if (atlasBuilder.shouldRegenerateAtlas()) {
                System.out.println("Texture atlas regeneration required - starting generation...");
                
                long startTime = System.currentTimeMillis();
                boolean success = atlasBuilder.generateAtlas();
                long endTime = System.currentTimeMillis();
                
                if (success) {
                    System.out.println("Texture atlas regenerated successfully in " + (endTime - startTime) + "ms");
                } else {
                    System.err.println("Failed to regenerate texture atlas - game may display incorrectly");
                }
            } else {
                System.out.println("Texture atlas is up-to-date, no regeneration needed");
            }
            
        } catch (Exception e) {
            System.err.println("Error during atlas regeneration check: " + e.getMessage());
            e.printStackTrace();
            System.err.println("Continuing with existing atlas...");
        }
    }
    
      private void cleanup() {
          Game.logDetailedMemoryInfo("Before cleanup");
          
          // Free the window callbacks and destroy the window
          glfwFreeCallbacks(window);
          glfwDestroyWindow(window);
          Game.logDetailedMemoryInfo("After GLFW cleanup");
          
          // Clean up renderer resources
          if (renderer != null) {
              renderer.cleanup();
              Game.logDetailedMemoryInfo("After renderer cleanup");
          }
          
          // Clean up world resources
          if (world != null) {
              world.cleanup();
              Game.logDetailedMemoryInfo("After world cleanup");
          }
          
          // Clean up game resources
          Game.getInstance().cleanup();
          Game.logDetailedMemoryInfo("After game cleanup");
          
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
    
    
    /**
     * Return the input handler.
     */
    public static InputHandler getInputHandler() {
        return instance != null ? instance.inputHandler : null;
    }
}
