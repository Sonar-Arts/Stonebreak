package com.openmason.ui;

import com.openmason.ui.themes.ThemeManager;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import imgui.ImVec4;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main ImGui application class that integrates the converted UI controllers.
 * Replaces the JavaFX application with a native LWJGL + Dear ImGui implementation.
 */
public class ImGuiApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiApplication.class);
    
    private static final int WINDOW_WIDTH = 1200;
    private static final int WINDOW_HEIGHT = 800;
    private static final String WINDOW_TITLE = "OpenMason Tool - Dear ImGui";
    
    // GLFW window handle
    private long windowHandle;
    
    // ImGui implementation
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    // Theme management
    private ThemeManager themeManager;

    // UI Interfaces
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    
    // Application state
    private boolean running = true;
    private float deltaTime = 0.0f;
    private float lastFrameTime = 0.0f;
    
    public ImGuiApplication() {
        logger.info("Initializing ImGui OpenMason Application...");
    }
    
    /**
     * Main application entry point.
     */
    public static void main(String[] args) {
        new ImGuiApplication().run();
    }
    
    /**
     * Main application run loop.
     */
    public void run() {
        try {
            initializeGLFW();
            createWindow();
            initializeOpenGL();
            initializeImGui();
            initializeUIInterfaces();
            
            logger.info("Application initialized successfully");
            
            // Main application loop
            mainLoop();
            
        } catch (Exception e) {
            logger.error("Application error", e);
        } finally {
            cleanup();
        }
    }
    
    /**
     * Initialize GLFW library.
     */
    private void initializeGLFW() {
        logger.info("Initializing GLFW...");
        
        // Setup error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        
        // macOS compatibility
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        
        logger.info("GLFW initialized successfully");
    }
    
    /**
     * Create the main application window.
     */
    private void createWindow() {
        logger.info("Creating application window...");
        
        // Create window
        windowHandle = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (windowHandle == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Setup window callbacks
        setupWindowCallbacks();
        
        // Center window on screen
        centerWindow();
        
        // Make OpenGL context current
        glfwMakeContextCurrent(windowHandle);
        
        // Enable V-Sync
        glfwSwapInterval(1);
        
        // Show window
        glfwShowWindow(windowHandle);
        
        logger.info("Application window created successfully");
    }
    
    /**
     * Setup window event callbacks.
     */
    private void setupWindowCallbacks() {
        // Window size callback
        glfwSetWindowSizeCallback(windowHandle, (window, width, height) -> {
            glViewport(0, 0, width, height);
            logger.debug("Window resized to {}x{}", width, height);
        });
        
        // Window close callback
        glfwSetWindowCloseCallback(windowHandle, window -> {
            running = false;
            logger.info("Window close requested");
        });
        
        // Key callback for global shortcuts
        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            handleGlobalKeyboard(key, scancode, action, mods);
        });
    }
    
    /**
     * Center window on the screen.
     */
    private void centerWindow() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            glfwGetWindowSize(windowHandle, pWidth, pHeight);
            
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidmode != null) {
                glfwSetWindowPos(
                    windowHandle,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
                );
            }
        }
    }
    
    /**
     * Initialize OpenGL context.
     */
    private void initializeOpenGL() {
        logger.info("Initializing OpenGL...");
        
        // Create OpenGL capabilities
        GL.createCapabilities();
        
        // Set viewport
        glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        
        // Enable depth testing
        glEnable(GL_DEPTH_TEST);
        
        // Enable blending for UI
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        logger.info("OpenGL initialized - Version: {}", glGetString(GL_VERSION));
    }
    
    /**
     * Initialize Dear ImGui.
     */
    private void initializeImGui() {
        logger.info("Initializing Dear ImGui...");
        
        // Create ImGui context
        ImGui.createContext();
        
        // Get IO
        ImGuiIO io = ImGui.getIO();
        
        // Enable docking and viewports
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);
        
        // Setup style
        ImGui.styleColorsDark();
        
        // When viewports are enabled we tweak WindowRounding/WindowBg so platform windows can look identical to regular ones.
        if ((io.getConfigFlags() & ImGuiConfigFlags.ViewportsEnable) != 0) {
            ImGui.getStyle().setWindowRounding(0.0f);
            // Get the current window background color and make it fully opaque
            ImVec4 windowBgColor = ImGui.getStyle().getColor(imgui.flag.ImGuiCol.WindowBg);
            windowBgColor.w = 1.0f; // Set alpha to fully opaque
            ImGui.getStyle().setColor(imgui.flag.ImGuiCol.WindowBg, windowBgColor.x, windowBgColor.y, windowBgColor.z, windowBgColor.w);
        }
        
        // Initialize ImGui GLFW
        imGuiGlfw.init(windowHandle, true);
        
        // Initialize ImGui OpenGL3
        imGuiGl3.init("#version 330 core");
        
        logger.info("Dear ImGui initialized successfully");
    }
    
    /**
     * Initialize UI interface components.
     */
    private void initializeUIInterfaces() {
        logger.info("Initializing UI interfaces...");

        // Create ThemeManager instance
        themeManager = new ThemeManager();

        // Create main interface with theme manager
        mainInterface = new MainImGuiInterface(themeManager);

        // Create viewport interface
        viewportInterface = new ViewportImGuiInterface();

        logger.info("UI interfaces initialized successfully");
    }
    
    /**
     * Main application loop.
     */
    private void mainLoop() {
        logger.info("Starting main application loop...");
        
        while (running && !glfwWindowShouldClose(windowHandle)) {
            // Calculate delta time
            float currentTime = (float) glfwGetTime();
            deltaTime = currentTime - lastFrameTime;
            lastFrameTime = currentTime;
            
            // Poll events
            glfwPollEvents();
            
            // Update interfaces
            updateInterfaces();
            
            // Render frame
            renderFrame();
            
            // Swap buffers
            glfwSwapBuffers(windowHandle);
        }
        
        logger.info("Main application loop ended");
    }
    
    /**
     * Update all UI interfaces.
     */
    private void updateInterfaces() {
        if (mainInterface != null) {
            mainInterface.update(deltaTime);
        }
        
        if (viewportInterface != null) {
            viewportInterface.update(deltaTime);
        }
    }
    
    /**
     * Render the application frame.
     */
    private void renderFrame() {
        // Start new ImGui frame
        imGuiGlfw.newFrame();
        ImGui.newFrame();
        
        // Clear background
        glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        // Render main interface
        if (mainInterface != null) {
            mainInterface.render();
        }
        
        // Render viewport interface (integrated into main interface)
        // viewportInterface.render() is called from within mainInterface
        
        // Render ImGui
        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
        
        // Handle multi-viewport rendering
        ImGuiIO io = ImGui.getIO();
        if ((io.getConfigFlags() & ImGuiConfigFlags.ViewportsEnable) != 0) {
            long backupCurrentContext = glfwGetCurrentContext();
            ImGui.updatePlatformWindows();
            ImGui.renderPlatformWindowsDefault();
            glfwMakeContextCurrent(backupCurrentContext);
        }
    }
    
    /**
     * Handle global keyboard shortcuts.
     */
    private void handleGlobalKeyboard(int key, int scancode, int action, int mods) {
        if (action == GLFW_PRESS || action == GLFW_REPEAT) {
            // Check for Ctrl modifier
            boolean ctrlPressed = (mods & GLFW_MOD_CONTROL) != 0;
            boolean shiftPressed = (mods & GLFW_MOD_SHIFT) != 0;
            
            if (ctrlPressed) {
                switch (key) {
                    case GLFW_KEY_N -> {
                        logger.debug("Global shortcut: New Model (Ctrl+N)");
                        // Trigger new model action
                    }
                    case GLFW_KEY_O -> {
                        if (shiftPressed) {
                            logger.debug("Global shortcut: Open Project (Ctrl+Shift+O)");
                            // Trigger open project action
                        } else {
                            logger.debug("Global shortcut: Open Model (Ctrl+O)");
                            // Trigger open model action
                        }
                    }
                    case GLFW_KEY_S -> {
                        if (shiftPressed) {
                            logger.debug("Global shortcut: Save As (Ctrl+Shift+S)");
                            // Trigger save as action
                        } else {
                            logger.debug("Global shortcut: Save Model (Ctrl+S)");
                            // Trigger save model action
                        }
                    }
                    case GLFW_KEY_Z -> {
                        logger.debug("Global shortcut: Undo (Ctrl+Z)");
                        // Trigger undo action
                    }
                    case GLFW_KEY_Y -> {
                        logger.debug("Global shortcut: Redo (Ctrl+Y)");
                        // Trigger redo action
                    }
                    case GLFW_KEY_R -> {
                        logger.debug("Global shortcut: Reset View (Ctrl+R)");
                        if (viewportInterface != null) {
                            // viewportInterface.resetView();
                        }
                    }
                    case GLFW_KEY_G -> {
                        logger.debug("Global shortcut: Toggle Grid (Ctrl+G)");
                        if (viewportInterface != null) {
                            // viewportInterface.toggleGrid();
                        }
                    }
                    case GLFW_KEY_1, GLFW_KEY_2, GLFW_KEY_3, GLFW_KEY_4 -> {
                        int variantIndex = key - GLFW_KEY_1;
                        logger.debug("Global shortcut: Switch to texture variant {} (Ctrl+{})", 
                                   variantIndex + 1, variantIndex + 1);
                        if (mainInterface != null) {
                            String[] variants = {"Default", "Angus", "Highland", "Jersey"};
                            if (variantIndex < variants.length) {
                                // mainInterface.switchToVariant(variants[variantIndex]);
                            }
                        }
                    }
                }
            }
            
            // ESC to close application
            if (key == GLFW_KEY_ESCAPE) {
                running = false;
            }
        }
    }
    
    /**
     * Cleanup resources when shutting down.
     */
    private void cleanup() {
        logger.info("Cleaning up application resources...");
        
        try {
            // Dispose UI interfaces
            if (viewportInterface != null) {
                viewportInterface.dispose();
            }
            
            // Dispose ImGui
            imGuiGl3.dispose();
            imGuiGlfw.dispose();
            ImGui.destroyContext();
            
            // Cleanup GLFW
            glfwSetErrorCallback(null);
            if (windowHandle != NULL) {
                glfwDestroyWindow(windowHandle);
            }
            glfwTerminate();
            
            logger.info("Application cleanup completed");
            
        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
    }
    
    /**
     * Get the main UI interface.
     */
    public MainImGuiInterface getMainInterface() {
        return mainInterface;
    }
    
    /**
     * Get the viewport interface.
     */
    public ViewportImGuiInterface getViewportInterface() {
        return viewportInterface;
    }
    
    /**
     * Check if the application is running.
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Request application shutdown.
     */
    public void shutdown() {
        logger.info("Application shutdown requested");
        running = false;
    }
}