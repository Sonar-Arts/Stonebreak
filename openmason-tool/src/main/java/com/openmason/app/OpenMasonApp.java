package com.openmason.app;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import com.openmason.ui.MainImGuiInterface;
import com.openmason.ui.ViewportImGuiInterface;
import com.openmason.ui.hub.ProjectHubScreen;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.components.textureCreator.TextureCreatorImGui;
import com.openmason.ui.windows.TextureEditorWindow;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main Dear ImGui application class for OpenMason tool.
 * Provides professional voxel game engine and development toolset with 1:1 rendering parity to Stonebreak.
 */
public class OpenMasonApp {

    private static final Logger logger = LoggerFactory.getLogger(OpenMasonApp.class);

    private static final String APP_TITLE = "OpenMason - Voxel Game Engine & Toolset";
    private static final int MIN_WIDTH = 1200;
    private static final int MIN_HEIGHT = 800;
    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 1000;
    
    // GLFW window handle
    private long window;
    
    // ImGui implementation objects
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
    
    // Application components
    private AppConfig appConfig;
    private AppLifecycle appLifecycle;
    
    // UI Interfaces
    private ThemeManager themeManager;
    private ProjectHubScreen projectHubScreen;
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    private TextureCreatorImGui textureCreatorInterface;
    private TextureEditorWindow textureEditorWindow;

    // Application state - simplified to visibility flags (KISS principle)
    private boolean showHomeScreen = true;
    private boolean showModelViewer = false;
    private boolean showTextureEditor = false;

    private boolean shouldClose = false;
    private boolean shouldApplyDefaultLayout = false;
    private boolean imguiInitialized = false;
    private boolean openglContextCreated = false;
    private boolean cleanedUp = false;

    // Window resize debouncing (prevent excessive config saves)
    private long lastResizeTime = 0;
    private int pendingWidth = 0;
    private int pendingHeight = 0;
    private boolean pendingMaximized = false;
    private boolean hasPendingResize = false;
    private static final long RESIZE_SAVE_DELAY_MS = 500; // Save 500ms after resize stops
    
    /**
     * Initialize and run the Dear ImGui application.This
     */
    public void run() {
        // logger.info("Starting OpenMason application...");
        
        try {
            // Initialize application configuration
            appConfig = new AppConfig();
            appLifecycle = new AppLifecycle();
            
            // Initialize GLFW and create window
            initializeGLFW();
            createWindow();
            
            // Initialize Dear ImGui
            initializeImGui();
            imguiInitialized = true;
            
            // Initialize UI interfaces
            initializeUI();
            
            // Initialize application lifecycle
            appLifecycle.onApplicationStarted();
            
            // logger.info("OpenMason application started successfully");
            
            // Run main application loop
            runMainLoop();
            
        } catch (Exception e) {
            logger.error("Failed to start OpenMason application", e);
            // Don't call cleanup() here - finally block will handle it
            System.exit(1);
        } finally {
            // Cleanup resources
            cleanup();
        }
    }
    
    /**
     * Initialize GLFW library.
     */
    private void initializeGLFW() {
        // logger.debug("Initializing GLFW...");
        
        // Setup error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        
        // logger.debug("GLFW initialized successfully");
    }
    
    /**
     * Create GLFW window.
     */
    private void createWindow() {
        // logger.debug("Creating GLFW window...");
        
        // Configure window hints
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Window will stay hidden until ready
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        
        // Get window size from config
        int width = appConfig.getLastWindowWidth();
        int height = appConfig.getLastWindowHeight();

        // Validate dimensions - use defaults if invalid
        if (width <= 0 || height <= 0) {
            logger.warn("Invalid window dimensions from config: {}x{}, using defaults", width, height);
            width = DEFAULT_WIDTH;
            height = DEFAULT_HEIGHT;
        }

        logger.info("Attempting to create GLFW window with dimensions: {}x{}", width, height);

        // Create window
        window = glfwCreateWindow(width, height, APP_TITLE, NULL, NULL);
        if (window == NULL) {
            logger.error("Failed to create GLFW window with dimensions {}x{}, trying default size", width, height);

            // Try with default dimensions as fallback
            window = glfwCreateWindow(DEFAULT_WIDTH, DEFAULT_HEIGHT, APP_TITLE, NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create GLFW window even with default dimensions " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
            }

            logger.info("Successfully created GLFW window with default dimensions: {}x{}", DEFAULT_WIDTH, DEFAULT_HEIGHT);
        } else {
            logger.info("Successfully created GLFW window with dimensions: {}x{}", width, height);
        }
        
        // Set minimum window size
        glfwSetWindowSizeLimits(window, MIN_WIDTH, MIN_HEIGHT, GLFW_DONT_CARE, GLFW_DONT_CARE);
        
        // Setup window callbacks
        setupWindowCallbacks();
        
        // Center window on screen
        centerWindow();
        
        // Make OpenGL context current
        glfwMakeContextCurrent(window);
        
        // Enable V-Sync if configured
        if (appConfig.isVSyncEnabled()) {
            glfwSwapInterval(1);
        } else {
            glfwSwapInterval(0);
        }
        
        // Create OpenGL context
        GL.createCapabilities();
        openglContextCreated = true;
        
        // Show window
        glfwShowWindow(window);
        
        // logger.debug("GLFW window created successfully");
    }
    
    /**
     * Setup window callbacks for input handling.
     */
    private void setupWindowCallbacks() {
        // Window close callback
        glfwSetWindowCloseCallback(window, window -> {
            shouldClose = true;
        });
        
        // Window size callback for saving window state (debounced)
        glfwSetWindowSizeCallback(window, (window, width, height) -> {
            if (appConfig != null) {
                // Store pending resize values
                pendingWidth = width;
                pendingHeight = height;
                pendingMaximized = glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE;
                lastResizeTime = System.currentTimeMillis();
                hasPendingResize = true;
            }
        });
        
        // Framebuffer size callback for OpenGL viewport
        glfwSetFramebufferSizeCallback(window, (window, width, height) -> {
            glViewport(0, 0, width, height);
        });
    }
    
    /**
     * Center window on screen.
     */
    private void centerWindow() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            glfwGetWindowSize(window, pWidth, pHeight);
            
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidmode != null) {
                glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
                );
            }
        }
    }
    
    /**
     * Initialize Dear ImGui context and rendering.
     */
    private void initializeImGui() {
        // logger.debug("Initializing Dear ImGui...");

        // Initialize ImGui context
        ImGui.createContext();

        // Configure ImGui IO
        ImGuiIO io = ImGui.getIO();
        setupImGuiLayout(io);
        io.setConfigWindowsMoveFromTitleBarOnly(true);          // Restrict window dragging to title bar only
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);  // Enable keyboard navigation
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);      // Enable docking
        io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);    // Enable viewports

        // Load JetBrains Mono font
        loadCustomFonts(io);

        // Initialize platform/renderer bindings
        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 330 core");

        // logger.debug("Dear ImGui initialized successfully");
    }

    /**
     * Load custom fonts for the application (JetBrains Mono).
     */
    private void loadCustomFonts(ImGuiIO io) {
        try {
            // Define font paths (module-specific to avoid conflicts with stonebreak-game)
            String fontPath = "openmason-tool/src/main/resources/masonFonts/";
            String regularFont = fontPath + "JetBrainsMono-Regular.ttf";
            String boldFont = fontPath + "JetBrainsMono-Bold.ttf";
            String mediumFont = fontPath + "JetBrainsMono-Medium.ttf";

            // Font configuration
            float fontSize = 16.0f;

            // Load default UI font (Regular)
            java.io.File regularFontFile = new java.io.File(regularFont);
            if (regularFontFile.exists()) {
                io.getFonts().addFontFromFileTTF(regularFont, fontSize);
                logger.info("Loaded JetBrains Mono Regular font ({}px)", fontSize);
            } else {
                logger.warn("JetBrains Mono Regular font not found at: {}", regularFont);
            }

            // Load bold variant for headings (optional - can be accessed programmatically)
            java.io.File boldFontFile = new java.io.File(boldFont);
            if (boldFontFile.exists()) {
                io.getFonts().addFontFromFileTTF(boldFont, fontSize);
                logger.info("Loaded JetBrains Mono Bold font ({}px)", fontSize);
            }

            // Load medium variant (optional)
            java.io.File mediumFontFile = new java.io.File(mediumFont);
            if (mediumFontFile.exists()) {
                io.getFonts().addFontFromFileTTF(mediumFont, fontSize);
                logger.info("Loaded JetBrains Mono Medium font ({}px)", fontSize);
            }

            // Build font atlas
            io.getFonts().build();

        } catch (Exception e) {
            logger.error("Failed to load custom fonts, using ImGui default font", e);
        }
    }
    
    /**
     * Main application render loop.
     */
    private void runMainLoop() {
        // logger.debug("Starting main render loop...");
        
        // Main loop
        while (!shouldClose && !glfwWindowShouldClose(window)) {
            // Poll for window events
            glfwPollEvents();

            // Handle debounced window resize save
            if (hasPendingResize && appConfig != null) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastResizeTime >= RESIZE_SAVE_DELAY_MS) {
                    appConfig.setLastWindowSize(pendingWidth, pendingHeight, pendingMaximized);
                    appConfig.saveConfiguration();
                    hasPendingResize = false;
                }
            }

            // Clear the framebuffer
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            
            // Start Dear ImGui frame
            imGuiGlfw.newFrame();
            ImGui.newFrame();
            
            // Apply default layout if needed (first frame after init)
            applyDefaultLayout();
            
            // Render application UI
            renderUI();
            
            // Render ImGui
            ImGui.render();
            imGuiGl3.renderDrawData(ImGui.getDrawData());
            
            // Handle viewports if enabled
            if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
                long backupCurrentContext = glfwGetCurrentContext();
                ImGui.updatePlatformWindows();
                ImGui.renderPlatformWindowsDefault();
                glfwMakeContextCurrent(backupCurrentContext);
            }
            
            // Swap front and back buffers
            glfwSwapBuffers(window);
        }
        
        // logger.debug("Main render loop ended");
    }
    
    /**
     * Initialize UI interfaces.
     */
    private void initializeUI() {
        // logger.debug("Initializing UI interfaces...");

        try {
            // Create ThemeManager instance (dependency injection)
            themeManager = new ThemeManager();

            // Initialize theme system for ImGui
            themeManager.initializeForImGui();

            // Initialize Project Hub Screen with theme manager
            projectHubScreen = new ProjectHubScreen(themeManager);

            // Initialize main interface with theme manager
            mainInterface = new MainImGuiInterface(themeManager);

            // Set transition callbacks for Project Hub
            projectHubScreen.setTransitionCallbacks(
                this::transitionToMainInterface,
                this::transitionToMainInterface  // Both create and open go to main interface for Phase 1
            );

            // Wire up preferences button in Project Hub to unified preferences window
            projectHubScreen.setOnPreferencesClicked(mainInterface.getShowPreferencesCallback());

            // Initialize viewport interface and inject the shared viewport
            viewportInterface = new ViewportImGuiInterface();
            viewportInterface.setViewport3D(mainInterface.getViewport3D());

            // Initialize texture creator interface
            textureCreatorInterface = TextureCreatorImGui.createDefault();

            // Wrap texture creator in window
            textureEditorWindow = new TextureEditorWindow(textureCreatorInterface);

            // Wire up callbacks
            mainInterface.setBackToHomeCallback(this::transitionToHomeScreen);
            mainInterface.setOpenTextureEditorCallback(() -> {
                showTextureEditor = true;
                if (textureEditorWindow != null) {
                    textureEditorWindow.show();
                }
            });
            textureCreatorInterface.setBackToHomeCallback(this::transitionToHomeScreen);

            // Wire up unified preferences window for texture editor
            textureCreatorInterface.setPreferencesCallback(mainInterface.getShowPreferencesCallback());

            // Wire up texture creator interface to main interface for real-time preference updates
            mainInterface.setTextureCreatorInterface(textureCreatorInterface);

            // CRITICAL: Set window handle for mouse capture functionality
            if (window != 0L) {
                viewportInterface.setWindowHandle(window);
                textureCreatorInterface.setWindowHandle(window);
                // logger.info("Window handle passed to viewport and texture creator interfaces for mouse capture");
            } else {
                logger.error("Cannot set window handle - window not created");
            }

            // logger.debug("UI interfaces initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize UI interfaces", e);
            throw new RuntimeException("UI initialization failed", e);
        }
    }
    
    /**
     * Render the main application UI using Dear ImGui.
     * Uses visibility flags to allow simultaneous rendering of multiple interfaces.
     */
    private void renderUI() {
        // Calculate delta time for animations
        float deltaTime = ImGui.getIO().getDeltaTime();

        // Render Project Hub Screen if visible
        if (showHomeScreen && projectHubScreen != null) {
            try {
                projectHubScreen.render();
                projectHubScreen.update(deltaTime);

                // Check if there's a pending transition
                if (projectHubScreen.shouldTransition()) {
                    // Action service callback already set, just apply layout
                    shouldApplyDefaultLayout = true;
                }
            } catch (Exception e) {
                logger.error("Error rendering Project Hub Screen", e);
            }
        }

        // Render Model Viewer if visible
        if (showModelViewer) {
            // Render main interface
            if (mainInterface != null) {
                try {
                    mainInterface.render();
                    mainInterface.update(deltaTime);
                } catch (Exception e) {
                    logger.error("Error rendering main interface", e);
                }
            }

            // Render viewport interface
            if (viewportInterface != null) {
                try {
                    viewportInterface.render();
                    viewportInterface.update(deltaTime);
                } catch (Exception e) {
                    logger.error("Error rendering viewport interface", e);
                }
            }
        }

        // Render Texture Editor window if visible
        if (showTextureEditor && textureEditorWindow != null) {
            try {
                textureEditorWindow.render();

                // Sync visibility flag with window state
                showTextureEditor = textureEditorWindow.isVisible();
            } catch (Exception e) {
                logger.error("Error rendering texture editor window", e);
            }
        }

        // Render unified preferences window at app level (accessible from both hub and main interface)
        if (mainInterface != null && mainInterface.getUnifiedPreferencesWindow() != null) {
            try {
                mainInterface.getUnifiedPreferencesWindow().render();
            } catch (Exception e) {
                logger.error("Error rendering unified preferences window", e);
            }
        }
    }
    
    // setupHomeScreenTools() removed - replaced by template system in ProjectHubScreen

    /**
     * Transition from Home screen to main interface.
     */
    private void transitionToMainInterface() {
        logger.info("Transitioning to main interface");
        showHomeScreen = false;
        showModelViewer = true;
        shouldApplyDefaultLayout = true;
    }

    /**
     * Transition back to Home screen from any tool.
     * Hides all tools and shows the home screen.
     */
    private void transitionToHomeScreen() {
        logger.info("Transitioning back to Home screen");
        showHomeScreen = true;
        showModelViewer = false;
        showTextureEditor = false;

        // Hide texture editor window
        if (textureEditorWindow != null) {
            textureEditorWindow.hide();
        }
    }

    /**
     * Cleanup application resources.
     */
    private void cleanup() {
        // Prevent double-cleanup (idempotent)
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;

        // logger.info("Cleaning up OpenMason application...");

        try {
            // Only attempt OpenGL cleanup if we have a valid context
            if (openglContextCreated && window != NULL) {
                // CRITICAL: Ensure OpenGL context is current before any OpenGL cleanup
                glfwMakeContextCurrent(window);

                // Cleanup CBR resource manager FIRST (if initialized)
                // This must happen on the OpenGL thread to avoid "No context is current" errors
                try {
                    if (com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.isInitialized()) {
                        com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.getInstance().close();
                        logger.info("CBRResourceManager cleaned up successfully");
                    }
                } catch (Exception e) {
                    logger.error("Error cleaning up CBRResourceManager", e);
                }

                // Cleanup UI interfaces (while OpenGL context is still valid)
                if (viewportInterface != null) {
                    viewportInterface.dispose();
                }

                if (textureCreatorInterface != null) {
                    textureCreatorInterface.dispose();
                }

                // Note: textureEditorWindow doesn't need disposal - it just wraps textureCreatorInterface

                // Cleanup theme manager
                if (themeManager != null) {
                    themeManager.dispose();
                }

                // Cleanup ImGui OpenGL resources (while context is still valid)
                if (imguiInitialized) {
                    if (imGuiGl3 != null) {
                        imGuiGl3.dispose();
                    }
                    if (imGuiGlfw != null) {
                        imGuiGlfw.dispose();
                    }
                    ImGui.destroyContext();
                }
            } else {
                // If no OpenGL context, only cleanup non-OpenGL ImGui resources
                if (imguiInitialized) {
                    // Cleanup theme manager
                    if (themeManager != null) {
                        themeManager.dispose();
                    }

                    if (imGuiGlfw != null) {
                        imGuiGlfw.dispose();
                    }
                    ImGui.destroyContext();
                }
            }

            // Save any pending window resize before shutdown
            if (hasPendingResize && appConfig != null) {
                appConfig.setLastWindowSize(pendingWidth, pendingHeight, pendingMaximized);
                appConfig.saveConfiguration();
                hasPendingResize = false;
            }

            // Shutdown application lifecycle
            if (appLifecycle != null) {
                appLifecycle.onApplicationShutdown();
            }
            
            // Now cleanup GLFW (this destroys the OpenGL context)
            // NOTE: Do NOT call glfwFreeCallbacks() here - ImGuiImplGlfw.dispose() already freed its callbacks
            // and glfwDestroyWindow() will clean up any remaining callbacks automatically
            if (window != NULL) {
                glfwDestroyWindow(window);
                window = NULL;  // Prevent accidental reuse
            }

            glfwTerminate();
            GLFWErrorCallback callback = glfwSetErrorCallback(null);
            if (callback != null) {
                callback.free();
            }
            
        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
        
        // logger.info("OpenMason application cleanup completed");
    }
    
    /**
     * Setup ImGui layout configuration. Uses ini file if available, otherwise applies default layout.
     */
    private void setupImGuiLayout(ImGuiIO io) {
        String iniFilePath = "openmason-tool/imgui.ini";
        java.io.File iniFile = new java.io.File(iniFilePath);
        
        if (iniFile.exists()) {
            io.setIniFilename(iniFilePath);
        } else {
            // No ini file exists, use default configuration
            io.setIniFilename(iniFilePath);  // Set path for future saves
            
            // Apply default layout after first frame
            shouldApplyDefaultLayout = true;
        }
    }
    
    /**
     * Apply default ImGui layout configuration when no ini file exists.
     * This recreates the layout from the original imgui.ini file.
     */
    private void applyDefaultLayout() {
        if (!shouldApplyDefaultLayout) return;
        shouldApplyDefaultLayout = false;
        
        // Set default docking configuration
        String defaultIniContent = """
            [Window][OpenMason Dockspace]
            Pos=0,65
            Size=1752,930
            Collapsed=0
            
            [Window][Debug##Default]
            Pos=60,60
            Size=400,400
            Collapsed=0
            
            [Window][##Toolbar]
            Pos=0,38
            Size=1752,32
            Collapsed=0
            
            [Window][Model Browser]
            Pos=199,65
            Size=1259,243
            Collapsed=0
            DockId=0x00000001,0
            
            [Window][Properties]
            Pos=0,65
            Size=197,930
            Collapsed=0
            DockId=0x00000005,0
            
            [Window][3D Viewport]
            Pos=199,310
            Size=1259,685
            Collapsed=0
            DockId=0x00000002,0
            
            [Window][Viewport Controls]
            Pos=1460,65
            Size=292,930
            Collapsed=0
            DockId=0x00000004,0
            
            [Docking][Data]
            DockSpace       ID=0x4E7C661D Window=0x09D7A246 Pos=844,287 Size=1752,930 Split=X
              DockNode      ID=0x00000005 Parent=0x4E7C661D SizeRef=197,930 Selected=0xC89E3217
              DockNode      ID=0x00000006 Parent=0x4E7C661D SizeRef=1553,930 Split=X
                DockNode    ID=0x00000003 Parent=0x00000006 SizeRef=1259,962 Split=Y
                  DockNode  ID=0x00000001 Parent=0x00000003 SizeRef=1600,243 CentralNode=1 Selected=0xB333979E
                  DockNode  ID=0x00000002 Parent=0x00000003 SizeRef=1600,685 Selected=0x33D66A19
                DockNode    ID=0x00000004 Parent=0x00000006 SizeRef=292,962 Selected=0x637B2906
            """;
        
        // Write default configuration to ini file
        try {
            java.nio.file.Files.writeString(java.nio.file.Paths.get("openmason-tool/imgui.ini"), 
                                          defaultIniContent, 
                                          java.nio.charset.StandardCharsets.UTF_8);
            // logger.info("Created default ImGui layout configuration");
        } catch (Exception e) {
            logger.warn("Could not write default ImGui layout: " + e.getMessage());
        }
    }
    
    /**
     * Main method - application entry point.
     */
    public static void main(String[] args) {
        
        // logger.info("Launching OpenMason with args: {}", String.join(" ", args));
        
        try {
            // Create and run application
            OpenMasonApp app = new OpenMasonApp();
            app.run();
            
        } catch (Exception e) {
            logger.error("Failed to launch OpenMason application", e);
            System.exit(1);
        }
    }
    

}