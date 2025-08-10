package com.openmason.ui.viewport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWErrorCallback.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.opengl.GL;

/**
 * GLFW window and OpenGL context management for Dear ImGui viewport integration.
 * 
 * This class manages GLFW window creation, OpenGL context setup, and context sharing
 * between the main application and the Dear ImGui viewport system. It provides proper
 * resource management and error handling for OpenGL operations.
 * 
 * Key features:
 * - GLFW window creation and management
 * - OpenGL context initialization and sharing
 * - Error callback handling and logging
 * - Resource cleanup and disposal
 * - Context switching for multi-window rendering
 * - Integration with Dear ImGui rendering pipeline
 */
public class GLFWContextManager {
    
    private static final Logger logger = LoggerFactory.getLogger(GLFWContextManager.class);
    
    private long primaryWindow = 0L;
    private long viewportWindow = 0L;
    private boolean glfwInitialized = false;
    private boolean contextInitialized = false;
    
    private int windowWidth = 1200;
    private int windowHeight = 800;
    private String windowTitle = "OpenMason Tool";
    
    // Error handling
    private org.lwjgl.glfw.GLFWErrorCallback errorCallback;
    
    /**
     * Initialize GLFW and create the primary window.
     */
    public void initialize() {
        logger.info("Initializing GLFW context manager");
        
        try {
            // Set up error callback
            setupErrorCallback();
            
            // Initialize GLFW
            if (!glfwInit()) {
                throw new RuntimeException("Failed to initialize GLFW");
            }
            glfwInitialized = true;
            
            // Configure GLFW window hints
            configureWindowHints();
            
            // Create primary window
            createPrimaryWindow();
            
            // Initialize OpenGL context
            initializeOpenGL();
            
            logger.info("GLFW context manager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize GLFW context manager", e);
            cleanup();
            throw new RuntimeException("GLFW initialization failed", e);
        }
    }
    
    /**
     * Set up GLFW error callback for proper error handling.
     */
    private void setupErrorCallback() {
        errorCallback = createPrint();
        glfwSetErrorCallback(errorCallback);
        logger.debug("GLFW error callback configured");
    }
    
    /**
     * Configure GLFW window hints for OpenGL context creation.
     */
    private void configureWindowHints() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        
        logger.debug("GLFW window hints configured for OpenGL 3.3 core profile");
    }
    
    /**
     * Create the primary application window.
     */
    private void createPrimaryWindow() {
        primaryWindow = glfwCreateWindow(windowWidth, windowHeight, windowTitle, NULL, NULL);
        if (primaryWindow == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Center window on screen
        centerWindow(primaryWindow);
        
        // Set up window callbacks
        setupWindowCallbacks(primaryWindow);
        
        logger.info("Primary GLFW window created: {}x{}", windowWidth, windowHeight);
    }
    
    /**
     * Center window on the primary monitor.
     */
    private void centerWindow(long window) {
        try {
            long monitor = glfwGetPrimaryMonitor();
            org.lwjgl.glfw.GLFWVidMode vidMode = glfwGetVideoMode(monitor);
            
            if (vidMode != null) {
                int x = (vidMode.width() - windowWidth) / 2;
                int y = (vidMode.height() - windowHeight) / 2;
                glfwSetWindowPos(window, x, y);
                
                logger.debug("Window centered at ({}, {})", x, y);
            }
        } catch (Exception e) {
            logger.warn("Failed to center window", e);
        }
    }
    
    /**
     * Set up window event callbacks.
     */
    private void setupWindowCallbacks(long window) {
        // Window close callback
        glfwSetWindowCloseCallback(window, windowHandle -> {
            logger.info("Window close requested");
            glfwSetWindowShouldClose(window, true);
        });
        
        // Window resize callback
        glfwSetWindowSizeCallback(window, (windowHandle, width, height) -> {
            logger.debug("Window resized to: {}x{}", width, height);
            glViewport(0, 0, width, height);
        });
        
        // Framebuffer size callback
        glfwSetFramebufferSizeCallback(window, (windowHandle, width, height) -> {
            logger.trace("Framebuffer resized to: {}x{}", width, height);
            glViewport(0, 0, width, height);
        });
        
        logger.debug("Window callbacks configured");
    }
    
    /**
     * Initialize OpenGL context and capabilities.
     */
    private void initializeOpenGL() {
        // Make context current
        glfwMakeContextCurrent(primaryWindow);
        
        // Create OpenGL capabilities
        GL.createCapabilities();
        
        // Enable v-sync
        glfwSwapInterval(1);
        
        // Log OpenGL information
        logOpenGLInfo();
        
        // Set up initial OpenGL state
        setupInitialOpenGLState();
        
        contextInitialized = true;
        logger.info("OpenGL context initialized successfully");
    }
    
    /**
     * Log OpenGL version and capabilities information.
     */
    private void logOpenGLInfo() {
        String vendor = glGetString(GL_VENDOR);
        String renderer = glGetString(GL_RENDERER);
        String version = glGetString(GL_VERSION);
        String glslVersion = glGetString(GL_SHADING_LANGUAGE_VERSION);
        
        logger.info("OpenGL Vendor: {}", vendor);
        logger.info("OpenGL Renderer: {}", renderer);
        logger.info("OpenGL Version: {}", version);
        logger.info("GLSL Version: {}", glslVersion);
    }
    
    /**
     * Set up initial OpenGL rendering state.
     */
    private void setupInitialOpenGLState() {
        // Enable depth testing
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        
        // Enable backface culling
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
        
        // Set clear color
        glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
        
        logger.debug("Initial OpenGL state configured");
    }
    
    /**
     * Create a shared context window for viewport rendering.
     */
    public long createSharedWindow(int width, int height, String title) {
        if (!contextInitialized) {
            throw new IllegalStateException("Primary context must be initialized first");
        }
        
        logger.debug("Creating shared context window: {}x{}", width, height);
        
        try {
            // Configure window hints for shared context
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            
            // Create shared window
            long sharedWindow = glfwCreateWindow(width, height, title, NULL, primaryWindow);
            if (sharedWindow == NULL) {
                throw new RuntimeException("Failed to create shared context window");
            }
            
            // Set up callbacks for shared window
            setupWindowCallbacks(sharedWindow);
            
            logger.info("Shared context window created: {}x{}", width, height);
            return sharedWindow;
            
        } catch (Exception e) {
            logger.error("Failed to create shared context window", e);
            throw new RuntimeException("Shared window creation failed", e);
        }
    }
    
    /**
     * Make the specified window's context current.
     */
    public void makeContextCurrent(long window) {
        if (window != 0L) {
            glfwMakeContextCurrent(window);
            logger.trace("Made context current for window: {}", window);
        } else {
            logger.warn("Attempted to make context current for invalid window handle");
        }
    }
    
    /**
     * Swap buffers for the specified window.
     */
    public void swapBuffers(long window) {
        if (window != 0L) {
            glfwSwapBuffers(window);
        }
    }
    
    /**
     * Poll GLFW events.
     */
    public void pollEvents() {
        glfwPollEvents();
    }
    
    /**
     * Check if the primary window should close.
     */
    public boolean shouldCloseWindow() {
        return primaryWindow != 0L && glfwWindowShouldClose(primaryWindow);
    }
    
    /**
     * Check if the specified window should close.
     */
    public boolean shouldCloseWindow(long window) {
        return window != 0L && glfwWindowShouldClose(window);
    }
    
    /**
     * Show the primary window.
     */
    public void showWindow() {
        if (primaryWindow != 0L) {
            glfwShowWindow(primaryWindow);
            logger.debug("Primary window shown");
        }
    }
    
    /**
     * Show the specified window.
     */
    public void showWindow(long window) {
        if (window != 0L) {
            glfwShowWindow(window);
            logger.debug("Window shown: {}", window);
        }
    }
    
    /**
     * Hide the specified window.
     */
    public void hideWindow(long window) {
        if (window != 0L) {
            glfwHideWindow(window);
            logger.debug("Window hidden: {}", window);
        }
    }
    
    /**
     * Get the primary window handle.
     */
    public long getPrimaryWindow() {
        return primaryWindow;
    }
    
    /**
     * Get window size.
     */
    public int[] getWindowSize(long window) {
        if (window == 0L) {
            return new int[]{0, 0};
        }
        
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetWindowSize(window, width, height);
        return new int[]{width[0], height[0]};
    }
    
    /**
     * Get framebuffer size.
     */
    public int[] getFramebufferSize(long window) {
        if (window == 0L) {
            return new int[]{0, 0};
        }
        
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        return new int[]{width[0], height[0]};
    }
    
    /**
     * Set window title.
     */
    public void setWindowTitle(long window, String title) {
        if (window != 0L && title != null) {
            glfwSetWindowTitle(window, title);
            logger.debug("Window title set: {}", title);
        }
    }
    
    /**
     * Check if GLFW is initialized.
     */
    public boolean isInitialized() {
        return glfwInitialized && contextInitialized;
    }
    
    /**
     * Destroy a window.
     */
    public void destroyWindow(long window) {
        if (window != 0L) {
            glfwDestroyWindow(window);
            logger.debug("Window destroyed: {}", window);
            
            if (window == primaryWindow) {
                primaryWindow = 0L;
            } else if (window == viewportWindow) {
                viewportWindow = 0L;
            }
        }
    }
    
    /**
     * Clean up all GLFW resources.
     */
    public void cleanup() {
        logger.info("Cleaning up GLFW context manager");
        
        // Destroy windows
        if (viewportWindow != 0L) {
            glfwDestroyWindow(viewportWindow);
            viewportWindow = 0L;
        }
        
        if (primaryWindow != 0L) {
            glfwDestroyWindow(primaryWindow);
            primaryWindow = 0L;
        }
        
        // Clean up GLFW
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
        }
        
        // Free error callback
        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
        }
        
        contextInitialized = false;
        
        logger.info("GLFW context manager cleanup completed");
    }
    
    @Override
    public String toString() {
        return String.format("GLFWContextManager{primaryWindow=%d, initialized=%s, contextReady=%s}",
            primaryWindow, glfwInitialized, contextInitialized);
    }
}