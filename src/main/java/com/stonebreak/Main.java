package com.stonebreak;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main class for the Stonebreak game - a voxel-based sandbox.
 */
public class Main {
    
    // Window handle
    private long window;
    
    // Game settings
    private int width = 1280;
    private int height = 720;
    private String title = "Stonebreak";
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
        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }
          // Setup key callback
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            // Escape key is now handled in InputHandler for toggling pause menu
        });
          // Setup window resize callback
        glfwSetFramebufferSizeCallback(window, (window, width, height) -> {
            this.width = width;
            this.height = height;
            glViewport(0, 0, width, height);
            if (renderer != null) {
                renderer.updateProjectionMatrix(width, height);
            }
            // Update the Game singleton with new dimensions
            Game.getInstance().setWindowDimensions(width, height);
        });
        // Setup mouse button callback
        // This now directly calls InputHandler's processMouseButton method.
        // InputHandler will then decide how to process the click based on game state (paused, inventory open, etc.)
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (inputHandler != null) {
                inputHandler.processMouseButton(button, action, mods);
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
            glfwSetWindowPos(
                window,
                (vidmode.width() - pWidth.get(0)) / 2,
                (vidmode.height() - pHeight.get(0)) / 2
            );
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
            
            // Handle input
            if (inputHandler != null) { // Ensure inputHandler is not null
                inputHandler.handleInput(player);
            }
            
            // Update game state
            update();
            
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
                    Thread.sleep(FRAME_TIME_MILLIS - frameTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
        
        // Render the world
        renderer.renderWorld(world, player, Game.getInstance().getTotalTimeElapsed()); // Pass totalTimeElapsed
        
        // Render UI elements
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        if (inventoryScreen != null) {
            if (inventoryScreen.isVisible()) {
                // Render full inventory screen when visible
                inventoryScreen.render(width, height);
            } else {
                // Always render hotbar when inventory is not open
                inventoryScreen.renderHotbar(width, height);
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
        glfwSetErrorCallback(null).free();
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
