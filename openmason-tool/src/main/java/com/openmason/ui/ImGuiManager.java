package com.openmason.ui;

import imgui.*;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * ImGuiManager - Core Dear ImGui integration for OpenMason.
 * 
 * Handles:
 * - GLFW window management 
 * - Dear ImGui initialization and rendering
 * - Input handling and event processing
 * - OpenGL context management
 * - UI component lifecycle
 */
public class ImGuiManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiManager.class);
    
    // Singleton instance
    private static ImGuiManager instance;
    
    // Window and rendering
    private long window;
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
    private String glslVersion = null;
    
    // UI components
    private final List<ImGuiComponent> components = new CopyOnWriteArrayList<>();
    private final List<ImGuiModal> activeModals = new ArrayList<>();
    
    // State
    private boolean initialized = false;
    private boolean shouldClose = false;
    private float deltaTime = 0.0f;
    private float lastFrameTime = 0.0f;
    
    // Configuration
    private static final int DEFAULT_WINDOW_WIDTH = 1600;
    private static final int DEFAULT_WINDOW_HEIGHT = 900;
    private static final String WINDOW_TITLE = "OpenMason - 3D Model and Texture Tool";
    
    /**
     * UI Component interface for ImGui rendering
     */
    public interface ImGuiComponent {
        void render(float deltaTime);
        String getName();
        boolean isVisible();
        void setVisible(boolean visible);
    }
    
    /**
     * Modal dialog interface
     */
    public interface ImGuiModal extends ImGuiComponent {
        boolean shouldClose();
        void onClose();
    }
    
    private ImGuiManager() {
        // Private constructor for singleton
    }
    
    public static synchronized ImGuiManager getInstance() {
        if (instance == null) {
            instance = new ImGuiManager();
        }
        return instance;
    }
    
    /**
     * Initialize the ImGui manager
     */
    public void initialize() {
        if (initialized) {
            logger.warn("ImGuiManager already initialized");
            return;
        }
        
        logger.info("Initializing ImGuiManager...");
        
        try {
            // Initialize GLFW
            initializeGLFW();
            
            // Create window
            createWindow();
            
            // Initialize OpenGL
            initializeOpenGL();
            
            // Initialize Dear ImGui
            initializeImGui();
            
            initialized = true;
            logger.info("ImGuiManager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize ImGuiManager", e);
            cleanup();
            throw new RuntimeException("ImGuiManager initialization failed", e);
        }
    }
    
    /**
     * Initialize GLFW
     */
    private void initializeGLFW() {
        GLFWErrorCallback.createPrint(System.err).set();
        
        if (!glfwInit()) {
            throw new RuntimeException("Unable to initialize GLFW");
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 4); // MSAA
        
        logger.debug("GLFW initialized");
    }
    
    /**
     * Create the main window
     */
    private void createWindow() {
        window = glfwCreateWindow(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Center window on screen
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            glfwGetWindowSize(window, pWidth, pHeight);
            
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidmode != null) {
                glfwSetWindowPos(window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2);
            }
        }
        
        // Set up callbacks
        glfwSetWindowCloseCallback(window, windowHandle -> shouldClose = true);
        
        // Make context current and show window
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // Enable V-Sync
        glfwShowWindow(window);
        
        logger.debug("GLFW window created: {}x{}", DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
    }
    
    /**
     * Initialize OpenGL
     */
    private void initializeOpenGL() {
        GL.createCapabilities();
        
        // Set viewport
        glViewport(0, 0, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        
        // Enable depth testing and blending
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Set clear color
        glClearColor(0.45f, 0.55f, 0.60f, 1.00f);
        
        logger.debug("OpenGL initialized - Version: {}", glGetString(GL_VERSION));
    }
    
    /**
     * Initialize Dear ImGui
     */
    private void initializeImGui() {
        // Initialize Dear ImGui context
        ImGui.createContext();
        
        final ImGuiIO io = ImGui.getIO();
        
        // Enable keyboard and gamepad controls
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);
        
        // Set up style
        ImGui.styleColorsDark();
        
        // When viewports are enabled, we need to adjust the style
        final ImGuiStyle style = ImGui.getStyle();
        if (io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            style.setWindowRounding(0.0f);
            style.setColor(ImGuiCol.WindowBg, ImGui.getColorU32(ImGuiCol.WindowBg, 1.0f));
        }
        
        // Determine GLSL version
        glslVersion = "#version 330 core";
        
        // Initialize platform and renderer bindings
        imGuiGlfw.init(window, true);
        imGuiGl3.init(glslVersion);
        
        logger.debug("Dear ImGui initialized with GLSL version: {}", glslVersion);
    }
    
    /**
     * Main render loop
     */
    public void run() {
        if (!initialized) {
            throw new IllegalStateException("ImGuiManager not initialized");
        }
        
        logger.info("Starting ImGui render loop");
        
        try {
            while (!shouldClose && !glfwWindowShouldClose(window)) {
                // Calculate delta time
                float currentTime = (float) glfwGetTime();
                deltaTime = currentTime - lastFrameTime;
                lastFrameTime = currentTime;
                
                // Poll events
                glfwPollEvents();
                
                // Start ImGui frame
                imGuiGlfw.newFrame();
                ImGui.newFrame();
                
                // Render UI components
                renderComponents();
                
                // Render modals
                renderModals();
                
                // Show main menu bar
                renderMainMenuBar();
                
                // Render Dear ImGui
                ImGui.render();
                
                // Clear framebuffer
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                
                // Render ImGui draw data
                imGuiGl3.renderDrawData(ImGui.getDrawData());
                
                // Handle multi-viewport rendering
                final ImGuiIO io = ImGui.getIO();
                if (io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
                    final long backupCurrentContext = glfwGetCurrentContext();
                    ImGui.updatePlatformWindows();
                    ImGui.renderPlatformWindowsDefault();
                    glfwMakeContextCurrent(backupCurrentContext);
                }
                
                // Swap buffers
                glfwSwapBuffers(window);
            }
            
        } catch (Exception e) {
            logger.error("Error in ImGui render loop", e);
            throw new RuntimeException("Render loop failed", e);
        }
        
        logger.info("ImGui render loop finished");
    }
    
    /**
     * Render all registered UI components
     */
    private void renderComponents() {
        for (ImGuiComponent component : components) {
            if (component.isVisible()) {
                try {
                    component.render(deltaTime);
                } catch (Exception e) {
                    logger.error("Error rendering component: {}", component.getName(), e);
                }
            }
        }
    }
    
    /**
     * Render active modal dialogs
     */
    private void renderModals() {
        // Process modals in reverse order so newer modals appear on top
        for (int i = activeModals.size() - 1; i >= 0; i--) {
            ImGuiModal modal = activeModals.get(i);
            
            if (modal.isVisible()) {
                try {
                    modal.render(deltaTime);
                    
                    // Check if modal should be closed
                    if (modal.shouldClose()) {
                        closeModal(modal);
                    }
                } catch (Exception e) {
                    logger.error("Error rendering modal: {}", modal.getName(), e);
                    closeModal(modal); // Close modal on error
                }
            }
        }
    }
    
    /**
     * Render the main menu bar
     */
    private void renderMainMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                if (ImGui.menuItem("New", "Ctrl+N")) {
                    // Handle new file
                }
                if (ImGui.menuItem("Open", "Ctrl+O")) {
                    // Handle open file
                }
                if (ImGui.menuItem("Save", "Ctrl+S")) {
                    // Handle save file
                }
                ImGui.separator();
                if (ImGui.menuItem("Exit")) {
                    shouldClose = true;
                }
                ImGui.endMenu();
            }
            
            if (ImGui.beginMenu("Export")) {
                if (ImGui.menuItem("Screenshot", "Ctrl+Shift+S")) {
                    // Handle screenshot export
                    openScreenshotDialog();
                }
                if (ImGui.menuItem("Batch Export", "Ctrl+Shift+B")) {
                    // Handle batch export
                    openBatchExportDialog();
                }
                if (ImGui.menuItem("Documentation", "Ctrl+Shift+D")) {
                    // Handle documentation export
                    openDocumentationDialog();
                }
                ImGui.endMenu();
            }
            
            if (ImGui.beginMenu("View")) {
                ImBoolean showPerformanceMonitor = new ImBoolean(isComponentVisible("PerformanceMonitor"));
                if (ImGui.checkbox("Performance Monitor", showPerformanceMonitor)) {
                    setComponentVisible("PerformanceMonitor", showPerformanceMonitor.get());
                }
                ImGui.endMenu();
            }
            
            if (ImGui.beginMenu("Help")) {
                if (ImGui.menuItem("About")) {
                    // Show about dialog
                }
                ImGui.endMenu();
            }
            
            ImGui.endMainMenuBar();
        }
    }
    
    /**
     * Register a UI component
     */
    public void registerComponent(ImGuiComponent component) {
        components.add(component);
        logger.debug("Registered UI component: {}", component.getName());
    }
    
    /**
     * Unregister a UI component
     */
    public void unregisterComponent(ImGuiComponent component) {
        components.remove(component);
        logger.debug("Unregistered UI component: {}", component.getName());
    }
    
    /**
     * Open a modal dialog
     */
    public void openModal(ImGuiModal modal) {
        if (!activeModals.contains(modal)) {
            activeModals.add(modal);
            modal.setVisible(true);
            logger.debug("Opened modal: {}", modal.getName());
        }
    }
    
    /**
     * Close a modal dialog
     */
    public void closeModal(ImGuiModal modal) {
        if (activeModals.remove(modal)) {
            modal.setVisible(false);
            modal.onClose();
            logger.debug("Closed modal: {}", modal.getName());
        }
    }
    
    /**
     * Check if a component is visible by name
     */
    private boolean isComponentVisible(String name) {
        return components.stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst()
            .map(ImGuiComponent::isVisible)
            .orElse(false);
    }
    
    /**
     * Set component visibility by name
     */
    private void setComponentVisible(String name, boolean visible) {
        components.stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst()
            .ifPresent(c -> c.setVisible(visible));
    }
    
    /**
     * Menu action handlers
     */
    private void openScreenshotDialog() {
        // This will be implemented by the screenshot system
        logger.debug("Opening screenshot dialog");
    }
    
    private void openBatchExportDialog() {
        // This will be implemented by the batch export system
        logger.debug("Opening batch export dialog");
    }
    
    private void openDocumentationDialog() {
        // This will be implemented by the documentation system
        logger.debug("Opening documentation dialog");
    }
    
    /**
     * Get GLFW window handle
     */
    public long getWindow() {
        return window;
    }
    
    /**
     * Get delta time for current frame
     */
    public float getDeltaTime() {
        return deltaTime;
    }
    
    /**
     * Check if manager is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Request application shutdown
     */
    public void shutdown() {
        logger.info("Shutdown requested");
        shouldClose = true;
    }
    
    /**
     * Cleanup resources
     */
    public void cleanup() {
        logger.info("Cleaning up ImGuiManager resources");
        
        try {
            // Cleanup Dear ImGui
            if (imGuiGl3 != null) {
                imGuiGl3.dispose();
            }
            if (imGuiGlfw != null) {
                imGuiGlfw.dispose();
            }
            
            // Destroy ImGui context
            ImGui.destroyContext();
            
            // Cleanup GLFW
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            
            glfwTerminate();
            
            // Cleanup error callback
            GLFWErrorCallback errorCallback = glfwSetErrorCallback(null);
            if (errorCallback != null) {
                errorCallback.free();
            }
            
            initialized = false;
            logger.info("ImGuiManager cleanup completed");
            
        } catch (Exception e) {
            logger.error("Error during ImGuiManager cleanup", e);
        }
    }
    
    // Custom application lifecycle methods
    
    /**
     * Configure application settings
     */
    public void configure() {
        // Configuration is handled in initialize() method
        logger.debug("ImGuiManager configuration complete");
    }
}