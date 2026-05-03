package com.openmason.main;

import com.openmason.main.systems.mcp.McpServerBootstrap;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.threading.MainThreadExecutor;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.viewport.ViewportImGuiInterface;
import com.openmason.main.systems.menus.mainHub.ProjectHubScreen;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.menus.textureCreator.FaceEditorBridge;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TexturePreviewPipeline;
import com.openmason.main.systems.services.drop.ViewportDropCallbackManager;
import com.openmason.main.systems.menus.windows.TextureEditorWindow;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main ImGui application for OpenMason tool.
 * Orchestrates window lifecycle, ImGui initialization, and UI rendering.
 * Follows SOLID principles with focused responsibilities.
 */
public class mainOpenMason {

    private static final Logger logger = LoggerFactory.getLogger(mainOpenMason.class);

    private static final String APP_TITLE = "OpenMason - Voxel Game Engine & Toolset";
    private static final int MIN_WIDTH = 1200;
    private static final int MIN_HEIGHT = 800;
    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 1000;
    private static final String FONT_PATH = "openmason-tool/src/main/resources/masonFonts/";
    private static final float FONT_SIZE = 16.0f;
    private static final String INI_FILE_PATH = "openmason-tool/imgui.ini";

    // Core components
    private long window;
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
    private omConfig omConfig;
    private omLifecycle omLifecycle;

    // UI components
    private ThemeManager themeManager;
    private ProjectHubScreen projectHubScreen;
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    private TextureCreatorImGui textureCreatorInterface;
    private TextureEditorWindow textureEditorWindow;
    private AnimationEditorImGui animationEditor;
    private TexturePreviewPipeline texturePreviewPipeline;
    private final McpServerBootstrap mcpServer = new McpServerBootstrap();

    // State flags
    private boolean showHomeScreen = true;
    private boolean showModelEditor = false;
    private boolean showTextureEditor = false;
    private boolean shouldClose = false;
    private boolean cleanedUp = false;
    
    /**
     * Initialize and run the application.
     */
    public void run() {
        try {
            MainThreadExecutor.bindToCurrentThread();
            omConfig = new omConfig();
            omLifecycle = new omLifecycle();

            initializeGLFW();
            createWindow();
            initializeImGui();
            initializeUI();

            omLifecycle.onApplicationStarted();
            mcpServer.start(mainInterface);
            runMainLoop();

        } catch (Exception e) {
            logger.error("Failed to start OpenMason application", e);
            System.exit(1);
        } finally {
            cleanup();
        }
    }
    
    /**
     * Initialize GLFW library with error callback.
     */
    private void initializeGLFW() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
    }
    
    /**
     * Create and configure GLFW window with OpenGL context.
     */
    private void createWindow() {
        configureWindowHints();

        int width = getValidWindowWidth();
        int height = getValidWindowHeight();

        window = createWindowWithFallback(width, height);
        glfwSetWindowSizeLimits(window, MIN_WIDTH, MIN_HEIGHT, GLFW_DONT_CARE, GLFW_DONT_CARE);

        setupWindowCallbacks();
        centerWindow();

        glfwMakeContextCurrent(window);
        glfwSwapInterval(omConfig.isVSyncEnabled() ? 1 : 0);

        GL.createCapabilities();
        glfwShowWindow(window);
    }

    private void configureWindowHints() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    }

    private int getValidWindowWidth() {
        int width = omConfig.getLastWindowWidth();
        return width > 0 ? width : DEFAULT_WIDTH;
    }

    private int getValidWindowHeight() {
        int height = omConfig.getLastWindowHeight();
        return height > 0 ? height : DEFAULT_HEIGHT;
    }

    private long createWindowWithFallback(int width, int height) {
        long win = glfwCreateWindow(width, height, APP_TITLE, NULL, NULL);
        if (win == NULL) {
            logger.warn("Failed to create window with {}x{}, using defaults", width, height);
            win = glfwCreateWindow(DEFAULT_WIDTH, DEFAULT_HEIGHT, APP_TITLE, NULL, NULL);
            if (win == NULL) {
                throw new RuntimeException("Failed to create GLFW window");
            }
        }
        return win;
    }
    
    /**
     * Setup window event callbacks.
     */
    private void setupWindowCallbacks() {
        glfwSetWindowCloseCallback(window, w -> {
            glfwSetWindowShouldClose(w, false);
            requestApplicationExit();
        });

        glfwSetWindowSizeCallback(window, (w, width, height) -> {
            boolean maximized = glfwGetWindowAttrib(w, GLFW_MAXIMIZED) == GLFW_TRUE;
            omConfig.setLastWindowSize(width, height, maximized);
            omConfig.saveConfiguration();
        });

        glfwSetFramebufferSizeCallback(window, (w, width, height) -> glViewport(0, 0, width, height));
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
     * Initialize ImGui context and rendering backend.
     */
    private void initializeImGui() {
        ImGui.createContext();

        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(INI_FILE_PATH);
        io.setConfigWindowsMoveFromTitleBarOnly(true);
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);

        loadFonts(io);

        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 330 core");

        // In imgui-java 1.87+, OpenGL device objects are lazily created on the first
        // newFrame() call rather than during init(). Trigger creation before main loop.
        imGuiGl3.newFrame();
    }

    /**
     * Load JetBrains Mono fonts (Regular, Bold, Medium).
     * Fails fast if fonts are not found - no fallback to defaults.
     */
    private void loadFonts(ImGuiIO io) {
        String[] fontVariants = {"JetBrainsMono-Regular.ttf", "JetBrainsMono-Bold.ttf", "JetBrainsMono-Medium.ttf"};

        for (String fontFile : fontVariants) {
            File font = new File(FONT_PATH + fontFile);
            if (!font.exists()) {
                throw new IllegalStateException("Required font not found: " + font.getAbsolutePath());
            }
            io.getFonts().addFontFromFileTTF(font.getPath(), FONT_SIZE);
        }

        io.getFonts().build();
    }
    
    /**
     * Main render loop.
     */
    private void runMainLoop() {
        while (!shouldClose && !glfwWindowShouldClose(window)) {
            glfwPollEvents();
            MainThreadExecutor.drain();
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            imGuiGlfw.newFrame();
            ImGui.newFrame();

            renderUI();

            ImGui.render();
            imGuiGl3.renderDrawData(ImGui.getDrawData());

            handleMultiViewport();

            glfwSwapBuffers(window);
        }
    }

    private void handleMultiViewport() {
        if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            long backupContext = glfwGetCurrentContext();
            ImGui.updatePlatformWindows();

            // Register drop callbacks on any new platform windows (for floating ImGui windows)
            ViewportDropCallbackManager.updateDropCallbacks();

            ImGui.renderPlatformWindowsDefault();
            glfwMakeContextCurrent(backupContext);
        }
    }
    
    /**
     * Initialize UI components and wire up callbacks.
     */
    private void initializeUI() {
        try {
            themeManager = new ThemeManager();
            themeManager.initializeForImGui();

            projectHubScreen = new ProjectHubScreen(themeManager);
            mainInterface = new MainImGuiInterface(themeManager);

            projectHubScreen.setTransitionCallbacks(this::transitionToMainInterface, this::openRecentProject);
            projectHubScreen.setOnPreferencesClicked(mainInterface.getShowPreferencesCallback());

            // Wire recent projects service from hub into main interface for project tracking
            mainInterface.setRecentProjectsService(projectHubScreen.getRecentProjectsService());

            // Initialize keybind system BEFORE creating viewport and texture editor
            initializeKeybindSystem();

            viewportInterface = new ViewportImGuiInterface(themeManager, new PreferencesManager());
            viewportInterface.setViewport3D(mainInterface.getViewport3D());

            // Wire slideouts: property panel ↔ viewport tool pane (Add Part, Part Transform)
            if (mainInterface.getPropertyPanel() != null) {
                mainInterface.getPropertyPanel().wireSlideouts(
                        viewportInterface.getViewportUIState(), mainInterface.getViewport3D());
            }

            textureCreatorInterface = TextureCreatorImGui.createDefault();
            textureEditorWindow = new TextureEditorWindow(textureCreatorInterface);
            animationEditor = new AnimationEditorImGui();
            animationEditor.setFileDialogService(mainInterface.getFileDialogService());

            // Load custom keybinds AFTER both viewport and texture editor are initialized
            loadCustomKeybinds();

            wireCallbacks();
            setWindowHandles();

            // Real-time texture preview: canvas edits → 3D viewport
            texturePreviewPipeline = new TexturePreviewPipeline(
                textureCreatorInterface.getController(),
                mainInterface.getViewport3D().getModelRenderer()
            );

            // Wire face editor bridge: property panel "Edit Texture" → texture editor
            FaceEditorBridge faceEditorBridge = new FaceEditorBridge(textureCreatorInterface.getController());
            mainInterface.getPropertyPanel().setFaceEditorBridge(faceEditorBridge);
            mainInterface.getPropertyPanel().setOnEditTextureRequested(() -> {
                showTextureEditor = true;
                textureEditorWindow.show();
            });

        } catch (Exception e) {
            logger.error("Failed to initialize UI interfaces", e);
            throw new RuntimeException("UI initialization failed", e);
        }
    }

    /**
     * Initialize the keybind system.
     * Called before creating viewport and texture editor interfaces.
     */
    private void initializeKeybindSystem() {
        logger.info("Initializing keybind system...");

        // Get the keybind registry singleton
        com.openmason.main.systems.keybinds.KeybindRegistry registry =
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

        // Note: Viewport actions will be registered when ViewportImGuiInterface is created
        // Note: Texture editor actions will be registered when TextureCreatorImGui.createDefault() is called

        logger.info("Keybind registry initialized successfully");
    }

    /**
     * Load custom keybinds from preferences.
     * Called after both viewport and texture editor are created and have registered their actions.
     */
    private void loadCustomKeybinds() {
        logger.info("Loading custom keybinds from preferences...");

        com.openmason.main.systems.menus.preferences.PreferencesManager preferencesManager =
                new com.openmason.main.systems.menus.preferences.PreferencesManager();
        com.openmason.main.systems.keybinds.KeybindRegistry registry =
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

        preferencesManager.loadKeybindsIntoRegistry(registry);

        logger.info("Custom keybinds loaded successfully");
    }

    private void wireCallbacks() {
        mainInterface.setBackToHomeCallback(this::transitionToHomeScreen);
        mainInterface.setExitCallback(() -> shouldClose = true);
        mainInterface.setOpenTextureEditorCallback(() -> {
            // Standalone open: reset to a fresh blank canvas so previous
            // per-face edits don't leak into the standalone session
            textureCreatorInterface.getController().resetAll();
            showTextureEditor = true;
            textureEditorWindow.show();
        });
        mainInterface.setOpenAnimationEditorCallback(() -> {
            if (animationEditor == null) return;
            // Bind the animation editor to whatever model is currently loaded so
            // the timeline drives this viewport's parts.
            if (mainInterface.getViewport3D() != null) {
                animationEditor.bindViewport(mainInterface.getViewport3D().getPartManager());
            }
            animationEditor.show();
        });
        mainInterface.setTextureCreatorInterface(textureCreatorInterface);

        // Reset texture editor when a new/different model is loaded
        mainInterface.getModelOperations().setOnModelChangedCallback(() ->
                textureCreatorInterface.getController().resetAll());

        textureCreatorInterface.setBackToHomeCallback(this::transitionToHomeScreen);
        textureCreatorInterface.setPreferencesCallback(mainInterface.getShowPreferencesCallback());
    }

    private void setWindowHandles() {
        if (window == 0L) {
            throw new IllegalStateException("Window not created");
        }
        viewportInterface.setWindowHandle(window);
        textureCreatorInterface.setWindowHandle(window);
    }
    
    /**
     * Render UI components based on visibility flags.
     */
    private void renderUI() {
        float deltaTime = ImGui.getIO().getDeltaTime();

        if (showHomeScreen) {
            renderComponent(projectHubScreen, deltaTime, "Project Hub");
        }

        if (showModelEditor) {
            renderComponent(mainInterface, deltaTime, "Main Interface");
            renderComponent(viewportInterface, deltaTime, "Viewport");
        }

        if (showTextureEditor) {
            safeRender(() -> {
                textureEditorWindow.render();
                boolean stillVisible = textureEditorWindow.isVisible();
                if (!stillVisible) {
                    boolean wasFaceEdit = textureCreatorInterface.getController().isFaceRegionActive();

                    // Flush pending canvas edits to the face's GPU texture BEFORE
                    // closing the region — closeFaceRegion clears the material ID,
                    // so a later flush would target the wrong texture.
                    if (texturePreviewPipeline != null) {
                        texturePreviewPipeline.flush();
                    }
                    textureCreatorInterface.getController().closeFaceRegion();
                    mainInterface.getPropertyPanel().clearEditingFace();

                    // Auto-save the .OMO so per-face texture edits are persisted
                    if (wasFaceEdit && mainInterface.getModelOperations() != null) {
                        mainInterface.getModelOperations().saveModel();
                    }
                }
                showTextureEditor = stillVisible;
            }, "Texture Editor");
        }

        if (animationEditor != null && animationEditor.isVisible()) {
            safeRender(() -> animationEditor.render(deltaTime), "Animation Editor");
        }

        if (mainInterface != null && mainInterface.getUnifiedPreferencesWindow() != null) {
            safeRender(() -> mainInterface.getUnifiedPreferencesWindow().render(), "Preferences Window");
        }

        if (mainInterface != null && mainInterface.getSBOExportWindow() != null) {
            safeRender(() -> mainInterface.getSBOExportWindow().render(), "SBO Export Window");
        }

        if (mainInterface != null && mainInterface.getSBEExportWindow() != null) {
            safeRender(() -> mainInterface.getSBEExportWindow().render(), "SBE Export Window");
        }

        if (mainInterface != null && mainInterface.getSBTExportWindow() != null) {
            safeRender(() -> mainInterface.getSBTExportWindow().render(), "SBT Export Window");
        }

        // Render unsaved changes dialog (must be rendered outside other windows for modal to work)
        if (mainInterface != null && mainInterface.getFileMenuHandler() != null) {
            safeRender(() -> mainInterface.getFileMenuHandler().getUnsavedChangesDialog().render(),
                    "Unsaved Changes Dialog");
        }

        // Flush pending texture preview updates to the 3D viewport
        if (texturePreviewPipeline != null) {
            texturePreviewPipeline.flush();
        }
    }

    private void renderComponent(Object component, float deltaTime, String name) {
        if (component == null) return;

        safeRender(() -> {
            if (component instanceof ProjectHubScreen hub) {
                hub.render();
                hub.update(deltaTime);
            } else if (component instanceof MainImGuiInterface main) {
                main.render();
                main.update(deltaTime);
            } else if (component instanceof ViewportImGuiInterface viewport) {
                viewport.render();
                viewport.update(deltaTime);
            }
        }, name);
    }

    private void safeRender(Runnable renderAction, String componentName) {
        try {
            renderAction.run();
        } catch (Exception e) {
            logger.error("Error rendering {}", componentName, e);
        }
    }
    
    /**
     * Request application exit. Shows the unsaved changes dialog if the model editor
     * is active and has unsaved changes, otherwise exits immediately.
     */
    private void requestApplicationExit() {
        if (showModelEditor && mainInterface != null) {
            mainInterface.requestExit();
        } else {
            shouldClose = true;
        }
    }

    /**
     * Transition to main interface from home screen.
     */
    private void transitionToMainInterface() {
        showHomeScreen = false;
        showModelEditor = true;

        // Reset all editor state for a clean session.
        // For blank template: this gives a fresh workspace.
        // For openRecentProject(): the subsequent openProjectFromHub() call
        // overwrites this reset with the saved project state.
        if (mainInterface != null) {
            mainInterface.resetEditorState();
        }
    }

    /**
     * Open a recent project from the Project Hub.
     * Transitions to the main interface and loads the .OMP project file.
     */
    private void openRecentProject(RecentProject project) {
        transitionToMainInterface();

        if (project != null && project.getPath() != null && !project.getPath().isBlank()) {
            // Delegate to MainImGuiInterface to load the project via ProjectService
            mainInterface.openProjectFromHub(project.getPath());
            logger.info("Opening project from hub: {}", project.getPath());
        }
    }

    /**
     * Transition back to home screen from any tool.
     */
    private void transitionToHomeScreen() {
        showHomeScreen = true;
        showModelEditor = false;
        showTextureEditor = false;
        if (textureEditorWindow != null) {
            textureEditorWindow.hide();
        }
        if (animationEditor != null) {
            animationEditor.hide();
        }
    }

    /**
     * Cleanup all application resources (idempotent).
     */
    private void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;

        try {
            mcpServer.stop();

            if (window != NULL) {
                glfwMakeContextCurrent(window);
                cleanupOpenGLResources();
                cleanupImGui();
            }

            if (omLifecycle != null) {
                omLifecycle.onApplicationShutdown();
            }

            cleanupGLFW();

        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
    }

    private void cleanupOpenGLResources() {
        try {
            if (com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.isInitialized()) {
                com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.getInstance().close();
            }
        } catch (Exception e) {
            logger.error("Error cleaning up CBRResourceManager", e);
        }

        if (texturePreviewPipeline != null) {
            texturePreviewPipeline.dispose();
        }
        safeDispose(viewportInterface);
        safeDispose(textureCreatorInterface);
        safeDispose(themeManager);

        try {
            com.openmason.main.systems.menus.icons.MenuBarIconManager.getInstance().dispose();
        } catch (Exception e) {
            logger.error("Error cleaning up MenuBarIconManager", e);
        }

        try {
            com.openmason.main.systems.menus.dialogs.icons.PartShapeIconManager.getInstance().dispose();
        } catch (Exception e) {
            logger.error("Error cleaning up PartShapeIconManager", e);
        }
    }

    private void cleanupImGui() {
        imGuiGl3.shutdown();
        imGuiGlfw.shutdown();
        ImGui.destroyContext();
    }

    private void cleanupGLFW() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }

        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }

    private void safeDispose(Object resource) {
        if (resource == null) return;
        try {
            if (resource instanceof ViewportImGuiInterface v) v.dispose();
            else if (resource instanceof TextureCreatorImGui t) t.dispose();
            else if (resource instanceof ThemeManager tm) tm.dispose();
        } catch (Exception e) {
            logger.error("Error disposing resource: {}", resource.getClass().getSimpleName(), e);
        }
    }
    
    /**
     * Application entry point.
     */
    public static void main(String[] args) {
        try {
            new mainOpenMason().run();
        } catch (Exception e) {
            logger.error("Failed to launch OpenMason application", e);
            System.exit(1);
        }
    }
}