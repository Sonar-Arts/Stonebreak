package com.stonebreak.core;

import java.nio.*;

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
    }    private void render() {
        // Clear the framebuffer
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        Game game = Game.getInstance();
        Renderer renderer = Game.getRenderer(); // Get Renderer once
        
        switch (game.getState()) {
            case MAIN_MENU -> {
                if (renderer != null && game.getMainMenu() != null) {
                    renderer.beginUIFrame(width, height, 1.0f);
                    game.getMainMenu().render(width, height);
                    renderer.endUIFrame();
                }
            }
            case LOADING -> {
                // Render loading screen using NanoVG
                if (renderer != null && game.getLoadingScreen() != null) {
                    renderer.beginUIFrame(width, height, 1.0f);
                    game.getLoadingScreen().render(width, height);
                    renderer.endUIFrame();
                }
            }
            case SETTINGS -> {
                // Render settings menu using NanoVG
                if (renderer != null) {
                    renderer.beginUIFrame(width, height, 1.0f);
                    if (game.getSettingsMenu() != null) {
                        game.getSettingsMenu().render(width, height);
                    }
                    renderer.endUIFrame();
                }
            }
            default -> { // Covers PLAYING, PAUSED, RECIPE_BOOK_UI, WORKBENCH_UI and any other potential states
                // Debug: Log state transition
                if (firstRender) {
                    System.out.println("First 3D render after loading - State: " + game.getState());
                    firstRender = false;
                }
                
                // Step 1: Completely reset OpenGL state for 3D rendering
                try {
                    // Verify OpenGL context
                    long currentContext = glfwGetCurrentContext();
                    if (currentContext != window) {
                        System.err.println("CRITICAL: Wrong OpenGL context - resetting");
                        glfwMakeContextCurrent(window);
                        GL.createCapabilities(); // Recreate capabilities
                    }
                    
                    if (firstRender) {
                        String version = glGetString(GL_VERSION);
                        System.out.println("OpenGL Version: " + version);
                    }
                    
                    // Aggressive state reset - disable everything first, then re-enable what we need
                    glUseProgram(0);              // Unbind any shader program
                    glBindTexture(GL_TEXTURE_2D, 0); // Unbind textures
                    glBindVertexArray(0);         // Unbind VAO
                    glBindBuffer(GL_ARRAY_BUFFER, 0); // Unbind VBO
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0); // Unbind EBO
                    
                    // Reset all OpenGL state
                    glDisable(GL_BLEND);
                    glDisable(GL_SCISSOR_TEST);
                    glDisable(GL_STENCIL_TEST);
                    glDisable(GL_CULL_FACE);
                    
                    // Clear error queue completely
                    while (glGetError() != GL_NO_ERROR) { /* clear */ }
                    
                    // Now enable depth test
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(GL_LESS);
                    glDepthMask(true);
                    
                    // Check for any errors after state reset
                    int finalError = glGetError();
                    if (finalError != GL_NO_ERROR && firstRender) {
                        System.err.println("Error after complete state reset: 0x" + Integer.toHexString(finalError));
                    }
                } catch (Exception e) {
                    System.err.println("Exception during OpenGL state reset: " + e.getMessage());
                    System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                    return; // Skip this frame
                }
                
                // Step 2: Render the 3D world first as a background for overlays
                try {
                    renderer.renderWorld(world, player, game.getTotalTimeElapsed());
                } catch (Exception e) {
                    System.err.println("CRITICAL CRASH: Exception in renderWorld() - State: " + game.getState());
                    System.err.println("Time: " + java.time.LocalDateTime.now());
                    System.err.println("Player pos: " + (player != null ? player.getPosition().x + ", " + player.getPosition().y + ", " + player.getPosition().z : "null"));
                    System.err.println("World chunks loaded: " + (world != null ? world.getLoadedChunkCount() : "null"));
                    System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
                    System.err.println("Exception: " + e.getMessage());
                    System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                    // Try to save crash log to file
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
                    throw new RuntimeException("Render crash - see crash_log.txt", e);
                }
                // Step 2: Render NanoVG UIs on top
                if (renderer != null) {
                    renderer.beginUIFrame(width, height, 1.0f); // Single begin/end frame for all NanoVG UI for these states
                    // Inventory / Hotbar / Chat (primarily for PLAYING/PAUSED state)
                    if (game.getState() == GameState.PLAYING || game.getState() == GameState.PAUSED) {
                        InventoryScreen inventoryScreen = game.getInventoryScreen();
                        if (inventoryScreen != null) {
                            if (inventoryScreen.isVisible()) {
                                inventoryScreen.render(width, height);
                            } else {
                                inventoryScreen.renderHotbar(width, height);
                            }
                        }
                        ChatSystem chatSystem = game.getChatSystem();
                        if (chatSystem != null) {
                            renderer.renderChat(chatSystem, width, height);
                        }
                    }
                    // Pause Menu (if active, and we are not in Main Menu or other full-screen UI states)
                    if ((game.getState() == GameState.PLAYING || game.getState() == GameState.PAUSED) && game.getPauseMenu() != null && game.getPauseMenu().isVisible()) {
                        game.getPauseMenu().render(renderer.getUIRenderer(), width, height); // Assumes PauseMenu draws within this frame
                    }
                    renderer.endUIFrame(); // End the shared NanoVG frame for standard game overlays
                }
                // Separate rendering for full-screen UI states that manage their own NanoVG frames
                // and should draw AFTER the 3D world.
                if (game.getState() == GameState.RECIPE_BOOK_UI) {
                     RecipeBookScreen recipeBookScreen = game.getRecipeBookScreen();
                     if (recipeBookScreen != null && recipeBookScreen.isVisible()) {
                         recipeBookScreen.render(); // This screen now manages its own NanoVG begin/end frame
                     }
                } else if (game.getState() == GameState.WORKBENCH_UI) {
                    WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
                    if (workbenchScreen != null && workbenchScreen.isVisible()){
                        workbenchScreen.render(); // This screen now manages its own NanoVG begin/end frame
                    }
                }
                // DEFERRED: Render block drops AFTER all UI is complete
                // Block drops render behind both inventory and pause menu with depth curtain protection
                renderer.renderBlockDropsDeferred(world, player);
                // Render tooltips AFTER block drops to ensure they appear above 3D block drops
                InventoryScreen inventoryScreen = game.getInventoryScreen();
                RecipeBookScreen recipeBookScreen = game.getRecipeBookScreen();
                if (renderer != null) { // Added null check for renderer
                    // Render inventory tooltips
                    if (inventoryScreen != null) {
                        renderer.beginUIFrame(width, height, 1.0f);
                        if (inventoryScreen.isVisible()) {
                            // Render only tooltips for full inventory screen
                            inventoryScreen.renderTooltipsOnly(width, height);
                        } else {
                            // Render only hotbar tooltips when inventory is not open
                            inventoryScreen.renderHotbarTooltipsOnly(width, height);
                        }
                        renderer.endUIFrame();
                    }
                    // Render recipe book tooltips
                    if (recipeBookScreen != null && recipeBookScreen.isVisible()) {
                        recipeBookScreen.renderTooltipsOnly();
                    }
                }
                // Render pause menu if paused
                PauseMenu pauseMenu = game.getPauseMenu();
                if (pauseMenu != null && pauseMenu.isVisible()) {
                    // UI rendering handled by renderer
                    if (renderer != null) {
                        renderer.beginUIFrame(width, height, 1.0f);
                        pauseMenu.render(renderer.getUIRenderer(), width, height);
                        renderer.endUIFrame();
                    }
                    // STEP 2: Render invisible depth curtain AFTER NanoVG to prevent interference
                    renderer.renderPauseMenuDepthCurtain();
                }
            }
        }
        
        // Render debug overlay last, so it's on top of everything
        DebugOverlay debugOverlay = Game.getDebugOverlay();
        if (debugOverlay != null && debugOverlay.isVisible()) {
            // Render wireframes first (3D rendering)
            debugOverlay.renderWireframes(renderer);
            
            // Then render UI overlay on top
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
