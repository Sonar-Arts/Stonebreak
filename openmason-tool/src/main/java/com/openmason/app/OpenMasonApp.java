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
public class OpenMasonApp {

    private static final Logger logger = LoggerFactory.getLogger(OpenMasonApp.class);

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
    private AppConfig appConfig;
    private AppLifecycle appLifecycle;

    // UI components
    private ThemeManager themeManager;
    private ProjectHubScreen projectHubScreen;
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    private TextureCreatorImGui textureCreatorInterface;
    private TextureEditorWindow textureEditorWindow;

    // State flags
    private boolean showHomeScreen = true;
    private boolean showModelViewer = false;
    private boolean showTextureEditor = false;
    private boolean shouldClose = false;
    private boolean cleanedUp = false;
    
    /**
     * Initialize and run the application.
     */
    public void run() {
        try {
            appConfig = new AppConfig();
            appLifecycle = new AppLifecycle();

            initializeGLFW();
            createWindow();
            initializeImGui();
            initializeUI();

            appLifecycle.onApplicationStarted();
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
        glfwSwapInterval(appConfig.isVSyncEnabled() ? 1 : 0);

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
        int width = appConfig.getLastWindowWidth();
        return width > 0 ? width : DEFAULT_WIDTH;
    }

    private int getValidWindowHeight() {
        int height = appConfig.getLastWindowHeight();
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
        glfwSetWindowCloseCallback(window, w -> shouldClose = true);

        glfwSetWindowSizeCallback(window, (w, width, height) -> {
            boolean maximized = glfwGetWindowAttrib(w, GLFW_MAXIMIZED) == GLFW_TRUE;
            appConfig.setLastWindowSize(width, height, maximized);
            appConfig.saveConfiguration();
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

            projectHubScreen.setTransitionCallbacks(this::transitionToMainInterface, this::transitionToMainInterface);
            projectHubScreen.setOnPreferencesClicked(mainInterface.getShowPreferencesCallback());

            viewportInterface = new ViewportImGuiInterface();
            viewportInterface.setViewport3D(mainInterface.getViewport3D());

            textureCreatorInterface = TextureCreatorImGui.createDefault();
            textureEditorWindow = new TextureEditorWindow(textureCreatorInterface);

            wireCallbacks();
            setWindowHandles();

        } catch (Exception e) {
            logger.error("Failed to initialize UI interfaces", e);
            throw new RuntimeException("UI initialization failed", e);
        }
    }

    private void wireCallbacks() {
        mainInterface.setBackToHomeCallback(this::transitionToHomeScreen);
        mainInterface.setOpenTextureEditorCallback(() -> {
            showTextureEditor = true;
            textureEditorWindow.show();
        });
        mainInterface.setTextureCreatorInterface(textureCreatorInterface);

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

        if (showModelViewer) {
            renderComponent(mainInterface, deltaTime, "Main Interface");
            renderComponent(viewportInterface, deltaTime, "Viewport");
        }

        if (showTextureEditor) {
            safeRender(() -> {
                textureEditorWindow.render();
                showTextureEditor = textureEditorWindow.isVisible();
            }, "Texture Editor");
        }

        if (mainInterface != null && mainInterface.getUnifiedPreferencesWindow() != null) {
            safeRender(() -> mainInterface.getUnifiedPreferencesWindow().render(), "Preferences Window");
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
     * Transition to main interface from home screen.
     */
    private void transitionToMainInterface() {
        showHomeScreen = false;
        showModelViewer = true;
    }

    /**
     * Transition back to home screen from any tool.
     */
    private void transitionToHomeScreen() {
        showHomeScreen = true;
        showModelViewer = false;
        showTextureEditor = false;
        if (textureEditorWindow != null) {
            textureEditorWindow.hide();
        }
    }

    /**
     * Cleanup all application resources (idempotent).
     */
    private void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;

        try {
            if (window != NULL) {
                glfwMakeContextCurrent(window);
                cleanupOpenGLResources();
                cleanupImGui();
            }

            if (appLifecycle != null) {
                appLifecycle.onApplicationShutdown();
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

        safeDispose(viewportInterface);
        safeDispose(textureCreatorInterface);
        safeDispose(themeManager);
    }

    private void cleanupImGui() {
        if (imGuiGl3 != null) imGuiGl3.dispose();
        if (imGuiGlfw != null) imGuiGlfw.dispose();
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
            new OpenMasonApp().run();
        } catch (Exception e) {
            logger.error("Failed to launch OpenMason application", e);
            System.exit(1);
        }
    }
}