package com.stonebreak;

import java.nio.IntBuffer;

import org.lwjgl.Version;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_COMPAT_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glViewport;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Main class for the Stonebreak game - a voxel-based sandbox.
 */
public class Main {
    
    // Window handle
    private long window;
    
    // Game settings
    private int width = 1280;
    private int height = 720;
    private final String title = "Stonebreak";
    private static final int TARGET_FPS = 144;
    private static final long FRAME_TIME_MILLIS = (long)(1000.0 / TARGET_FPS);
    
    // Game state
    private boolean running = false;
    
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
        
        try {
            init();
            loop();
        } finally {
            cleanup();
        }
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
            } else if (inputHandler != null) {
                inputHandler.processMouseButton(button, action, mods);
            }
        });
        
        // Setup cursor position callback for menu navigation and player mouse look
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            Game game = Game.getInstance();
            if (game.getState() == GameState.MAIN_MENU && game.getMainMenu() != null) {
                // Handle main menu mouse hover
                game.getMainMenu().handleMouseMove(xpos, ypos, width, height);
            } else if (game.getState() == GameState.PLAYING && inputHandler != null) {
                // Handle player mouse look by updating InputHandler's mouse position
                inputHandler.updateMousePosition((float)xpos, (float)ypos);
            }
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
        
        // Start with cursor visible for main menu
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        
        // Make the window visible
        glfwShowWindow(window);
        
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities();
        
        // Initialize game components
        initializeGameComponents();
    }
      private void initializeGameComponents() {
        // Initialize the renderer with window dimensions
        renderer = new Renderer(width, height);
        
        // Initialize the input handler
        inputHandler = new InputHandler(window);
        
        // Initialize the world
        world = new World();
        
        // Initialize the player
        player = new Player(world);
        
        // Set initial camera position
        player.setPosition(0, 100, 0);

        // Initialize TextureAtlas (used by Renderer and potentially UI)
        textureAtlas = renderer.getTextureAtlas(); // Get it from renderer after it's created

          // Initialize the Game singleton
        // Pass inputHandler to Game's init method
        Game.getInstance().init(world, player, renderer, textureAtlas, inputHandler);
        Game.getInstance().setWindowDimensions(width, height);
        
        running = true;
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
            // Record the start time of this frame
            long frameStartTime = System.currentTimeMillis();

            // Prepare InputHandler for new frame (e.g., clear single-frame press states)
            if (inputHandler != null) {
                inputHandler.prepareForNewFrame();
            }
            
            // Poll for window events
            glfwPollEvents();
            
            // Update Game singleton (for delta time)
            Game.getInstance().update();
            
            // Handle input based on game state
            Game game = Game.getInstance();
            if (game.getState() == GameState.MAIN_MENU) {
                // Handle main menu input
                if (game.getMainMenu() != null) {
                    game.getMainMenu().handleInput(window);
                }
            } else if (game.getState() == GameState.PLAYING) {
                // Handle in-game input
                if (inputHandler != null) {
                    inputHandler.handleInput(player);
                }
            }
            
            // Update game state (only if playing)
            if (game.getState() == GameState.PLAYING) {
                update();
            }
            
            // Render frame
            render();
            
            // Swap the color buffers
            glfwSwapBuffers(window);
            
            // Calculate how long the frame took to process
            long frameEndTime = System.currentTimeMillis();
            long frameTime = frameEndTime - frameStartTime;
            
            // Sleep if we're running faster than the target FPS
            if (frameTime < FRAME_TIME_MILLIS) {
                try {
                    // Sleep to cap FPS
                    Thread.sleep(FRAME_TIME_MILLIS - frameTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break; // Exit loop if interrupted
                }
            }
        }
    }
      private void update() {
        // Update the player
        player.update();
        
        // Update the world
        world.update();
        
        // Display debug information
        Game.displayDebugInfo();
    }
    
    private void render() {
        // Clear the framebuffer
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        Game game = Game.getInstance();
        
        if (game.getState() == GameState.MAIN_MENU) {
            // Render main menu using NanoVG
            UIRenderer uiRenderer = game.getUIRenderer();
            if (uiRenderer != null) {
                uiRenderer.beginFrame(width, height, 1.0f);
                if (game.getMainMenu() != null) {
                    game.getMainMenu().render(width, height);
                }
                uiRenderer.endFrame();
            }
        } else if (game.getState() == GameState.PLAYING) {
            // Render the world
            renderer.renderWorld(world, player, game.getTotalTimeElapsed());
            
            // Render UI elements
            InventoryScreen inventoryScreen = game.getInventoryScreen();
            if (inventoryScreen != null) {
                UIRenderer uiRenderer = game.getUIRenderer();
                uiRenderer.beginFrame(width, height, 1.0f);
                
                if (inventoryScreen.isVisible()) {
                    // Render full inventory screen when visible
                    inventoryScreen.render(width, height);
                } else {
                    // Always render hotbar when inventory is not open
                    inventoryScreen.renderHotbar(width, height);
                }
                
                // Render chat (always visible when there are messages or chat is open)
                ChatSystem chatSystem = game.getChatSystem();
                if (chatSystem != null) {
                    uiRenderer.renderChat(chatSystem, width, height);
                }
                
                uiRenderer.endFrame();
            }
            
            // Render pause menu if paused
            PauseMenu pauseMenu = game.getPauseMenu();
            if (pauseMenu != null && pauseMenu.isVisible()) {
                UIRenderer uiRenderer = game.getUIRenderer();
                if (uiRenderer != null) {
                    uiRenderer.beginFrame(width, height, 1.0f);
                    pauseMenu.render(uiRenderer, width, height);
                    uiRenderer.endFrame();
                }
            }
        }
    }
      private void cleanup() {
        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        
        // Clean up renderer resources
        if (renderer != null) {
            renderer.cleanup();
        }
        
        // Clean up world resources
        if (world != null) {
            world.cleanup();
        }
        
        // Clean up game resources
        Game.getInstance().cleanup();
        
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
