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
import com.openmason.main.systems.menus.textureCreator.FaceTextureResizeDialog;
import com.openmason.main.systems.menus.textureCreator.IFaceTextureGPUService;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TexturePreviewPipeline;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.services.drop.ViewportDropCallbackManager;
import com.openmason.main.systems.menus.windows.TextureEditorWindow;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaTestPanel;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

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
    private static final String LOGO_RESOURCE_PATH = "/icons/Logo/Open Mason Logo.png";
    private static final String APP_USER_MODEL_ID = "OpenMason.VoxelToolset";

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
    private SkijaContext skijaContext;
    private SkijaTestPanel skijaTestPanel;

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
            setWindowsAppUserModelId();
            omConfig = new omConfig();
            omLifecycle = new omLifecycle();

            initializeGLFW();
            createWindow();
            initializeImGui();
            initializeSkija();
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
        boolean pinnedX11 = preferX11PlatformForViewports();
        if (!glfwInit()) {
            // glfwPlatformSupported() only reports compile-time support, so pinning
            // X11 can still fail at init if no X display / XWayland is reachable.
            // Recover by letting GLFW pick any available platform (e.g. Wayland).
            if (pinnedX11) {
                logger.warn("GLFW init with the X11 backend failed (no reachable X display?); retrying with the default platform. Viewport pop-out may be limited.");
                glfwInitHint(GLFW_PLATFORM, GLFW_ANY_PLATFORM);
                if (glfwInit()) {
                    return;
                }
            }
            throw new IllegalStateException("Unable to initialize GLFW");
        }
    }

    /**
     * ImGui multi-viewport lets panels (e.g. the Texture Editor) be dragged out
     * of the main window into their own floating OS windows. That requires the
     * windowing backend to create top-level windows and position them at
     * arbitrary screen coordinates via {@code glfwSetWindowPos}. Wayland forbids
     * clients from positioning their own windows, so under a native Wayland
     * session detached viewports stay clamped inside the main window — the
     * "stuck inside" behavior seen on Linux versus Windows.
     *
     * <p>To match the Windows experience we prefer the X11 backend (served by
     * XWayland on Wayland desktops), which supports the needed window
     * positioning. This is a no-op on Windows/macOS and when X11 is unavailable
     * (GLFW then falls back to its default platform). Users who explicitly want
     * native Wayland can opt out with {@code -Dopenmason.glfw.platform=wayland}
     * (or {@code =any} to let GLFW choose).
     *
     * @return {@code true} if the X11 backend was pinned (so the caller can
     *         recover if {@code glfwInit} then fails), {@code false} otherwise.
     */
    private boolean preferX11PlatformForViewports() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return false; // X11/Wayland selection only matters on Linux
        }

        String requested = System.getProperty("openmason.glfw.platform", "x11").trim().toLowerCase();
        switch (requested) {
            case "wayland" -> {
                if (glfwPlatformSupported(GLFW_PLATFORM_WAYLAND)) {
                    glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_WAYLAND);
                    logger.info("GLFW platform pinned to Wayland by request; multi-viewport pop-out windows may be constrained.");
                }
                return false;
            }
            case "any" -> {
                logger.info("GLFW platform selection left to GLFW default (openmason.glfw.platform=any).");
                return false;
            }
            default -> { // "x11" and anything unrecognized: prefer X11 for working viewports
                if (glfwPlatformSupported(GLFW_PLATFORM_X11)) {
                    glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
                    logger.info("Preferring GLFW X11 backend so ImGui viewports (e.g. Texture Editor) can pop out into their own windows.");
                    return true;
                }
                logger.warn("GLFW X11 backend unavailable; detached ImGui windows may stay clamped inside the main window under Wayland.");
                return false;
            }
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
        setWindowIcon();

        setupWindowCallbacks();
        centerWindow();

        glfwMakeContextCurrent(window);
        glfwSwapInterval(omConfig.isVSyncEnabled() ? 1 : 0);

        GL.createCapabilities();
        glfwShowWindow(window);

        // GLFW posts the Windows taskbar-icon update as a window message. If the event loop is
        // not pumped within ~500ms of glfwSetWindowIcon, Windows drops the taskbar update (the
        // synchronous title-bar icon still applies). Heavy init (ImGui/Skija/UI) runs before the
        // main loop's first poll, so pump events now to flush the taskbar update. See GLFW #2753.
        glfwPollEvents();
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
     * Set the window/taskbar icon from the Open Mason logo PNG.
     *
     * <p>GLFW does not use {@code .ico} files: {@link GLFW#glfwSetWindowIcon} takes raw
     * RGBA pixels via {@link GLFWImage}. We supply several downscaled candidates so the OS
     * (and the Windows taskbar) can pick the crispest size for each context. Failure here is
     * non-fatal — the app simply falls back to the default GLFW icon.</p>
     */
    private void setWindowIcon() {
        try (InputStream logoStream = mainOpenMason.class.getResourceAsStream(LOGO_RESOURCE_PATH)) {
            if (logoStream == null) {
                logger.warn("Window icon resource not found: {}", LOGO_RESOURCE_PATH);
                return;
            }

            BufferedImage source = ImageIO.read(logoStream);
            if (source == null) {
                logger.warn("Failed to decode window icon image: {}", LOGO_RESOURCE_PATH);
                return;
            }

            // Trim the transparent margin so the logo fills the fixed taskbar slot edge-to-edge
            // (it otherwise renders smaller than the slot due to the PNG's empty border).
            source = trimTransparentBorder(source);

            int[] sizes = {16, 24, 32, 48, 64, 128, 256};
            List<ByteBuffer> pixelBuffers = new ArrayList<>(sizes.length);
            try (GLFWImage.Buffer icons = GLFWImage.malloc(sizes.length)) {
                for (int i = 0; i < sizes.length; i++) {
                    int s = sizes[i];
                    ByteBuffer pixels = toRgbaBuffer(scale(source, s, s));
                    pixelBuffers.add(pixels);
                    icons.position(i).width(s).height(s).pixels(pixels);
                }
                icons.position(0);
                glfwSetWindowIcon(window, icons);
            } finally {
                pixelBuffers.forEach(MemoryUtil::memFree);
            }
            logger.info("Window icon set from {}", LOGO_RESOURCE_PATH);
        } catch (IOException e) {
            logger.warn("Failed to load window icon", e);
        } catch (Exception e) {
            logger.warn("Unexpected error setting window icon", e);
        }
    }

    /**
     * Crop fully-transparent rows/columns from the image border so the visible artwork fills the
     * frame. Returns the original image if it has no alpha or is already tight. A small uniform
     * margin is preserved so antialiased/rounded edges aren't clipped.
     */
    private static BufferedImage trimTransparentBorder(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        final int alphaThreshold = 8; // treat near-transparent pixels as empty

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (source.getRGB(x, y) >> 24) & 0xFF;
                if (alpha > alphaThreshold) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return source; // fully transparent — nothing to trim
        }

        // Keep a 2% margin so rounded corners / antialiasing aren't shaved off.
        int margin = Math.round(Math.max(w, h) * 0.02f);
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(w - 1, maxX + margin);
        maxY = Math.min(h - 1, maxY + margin);

        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        if (cropW >= w && cropH >= h) {
            return source; // already edge-to-edge
        }
        return source.getSubimage(minX, minY, cropW, cropH);
    }

    /** Scale a source image to the given dimensions with smooth interpolation. */
    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /** Convert an image to a tightly-packed RGBA byte buffer (native-freed by the caller). */
    private static ByteBuffer toRgbaBuffer(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) ((argb >> 16) & 0xFF)); // R
                buffer.put((byte) ((argb >> 8) & 0xFF));  // G
                buffer.put((byte) (argb & 0xFF));         // B
                buffer.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Give this process its own Windows taskbar identity (AppUserModelID).
     *
     * <p>Without an explicit AppUserModelID, a Java app's taskbar button is grouped under the
     * host {@code java.exe}/{@code javaw.exe} process, so Windows shows the launcher's icon there
     * even though {@link #setWindowIcon()} correctly sets the window's title-bar / Alt-Tab icon.
     * Calling shell32 {@code SetCurrentProcessExplicitAppUserModelID} early — before the window
     * is created — makes the taskbar adopt our window icon instead.</p>
     *
     * <p>Uses the Java FFM API (Java 22+, stable in 25); no-op and non-fatal off Windows or if
     * the symbol is unavailable.</p>
     */
    private void setWindowsAppUserModelId() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
            java.lang.foreign.SymbolLookup shell32 =
                    java.lang.foreign.SymbolLookup.libraryLookup("shell32", arena);
            java.lang.foreign.MemorySegment fn = shell32
                    .find("SetCurrentProcessExplicitAppUserModelID")
                    .orElseThrow(() -> new IllegalStateException("SetCurrentProcessExplicitAppUserModelID not found"));

            java.lang.invoke.MethodHandle handle = linker.downcallHandle(fn,
                    java.lang.foreign.FunctionDescriptor.of(
                            java.lang.foreign.ValueLayout.JAVA_INT,   // HRESULT
                            java.lang.foreign.ValueLayout.ADDRESS));  // PCWSTR appId

            // UTF-16LE, null-terminated wide string.
            byte[] utf16 = APP_USER_MODEL_ID.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            java.lang.foreign.MemorySegment appId = arena.allocate(utf16.length + 2L);
            java.lang.foreign.MemorySegment.copy(utf16, 0, appId, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, utf16.length);

            int hr = (int) handle.invoke(appId);
            if (hr != 0) {
                logger.warn("SetCurrentProcessExplicitAppUserModelID returned HRESULT 0x{}", Integer.toHexString(hr));
            } else {
                logger.info("Windows AppUserModelID set to '{}'", APP_USER_MODEL_ID);
            }
        } catch (Throwable t) {
            logger.warn("Could not set Windows AppUserModelID (taskbar icon may use host process icon)", t);
        }
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
     * Center the window on the primary ("priority") monitor.
     *
     * <p>The primary monitor is not necessarily at virtual-screen origin (0,0): on multi-monitor
     * setups a display placed to the left/above the primary gives the primary a positive/negative
     * virtual offset. Centering with just the primary's {@code width/height} (as if it started at
     * 0,0) therefore lands the window on whichever monitor occupies the origin — often the wrong
     * screen on Linux. We anchor to the primary monitor's virtual position via
     * {@link GLFW#glfwGetMonitorWorkarea} (which also excludes panels/taskbars) so the window is
     * always centered on the primary display. Falls back to {@link GLFW#glfwGetVideoMode} +
     * {@link GLFW#glfwGetMonitorPos} if the work area is unavailable.</p>
     */
    private void centerWindow() {
        long monitor = glfwGetPrimaryMonitor();
        if (monitor == NULL) {
            logger.warn("No primary monitor reported; leaving window at default position");
            return;
        }

        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            int winW = pWidth.get(0);
            int winH = pHeight.get(0);

            // Primary monitor's usable area in virtual-screen coordinates (origin + size).
            IntBuffer areaX = stack.mallocInt(1);
            IntBuffer areaY = stack.mallocInt(1);
            IntBuffer areaW = stack.mallocInt(1);
            IntBuffer areaH = stack.mallocInt(1);
            glfwGetMonitorWorkarea(monitor, areaX, areaY, areaW, areaH);

            int originX = areaX.get(0);
            int originY = areaY.get(0);
            int monW = areaW.get(0);
            int monH = areaH.get(0);

            // Some drivers/Wayland-via-XWayland report a zero work area; fall back to the video
            // mode for size and the raw monitor position for the virtual-screen origin.
            if (monW <= 0 || monH <= 0) {
                GLFWVidMode vidmode = glfwGetVideoMode(monitor);
                if (vidmode == null) {
                    logger.warn("Primary monitor has no video mode; leaving window at default position");
                    return;
                }
                monW = vidmode.width();
                monH = vidmode.height();
                IntBuffer monX = stack.mallocInt(1);
                IntBuffer monY = stack.mallocInt(1);
                glfwGetMonitorPos(monitor, monX, monY);
                originX = monX.get(0);
                originY = monY.get(0);
            }

            glfwSetWindowPos(
                window,
                originX + (monW - winW) / 2,
                originY + (monH - winH) / 2
            );
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
     * Initialize the shared Skija DirectContext for high-quality 2D widget
     * rendering. Non-fatal on failure — Skija-backed widgets fall back to
     * ImGui draw-list rendering when no context is available.
     */
    private void initializeSkija() {
        try {
            skijaContext = SkijaContext.initialize();
            if (SkijaTestPanel.ENABLED) {
                skijaTestPanel = new SkijaTestPanel();
                logger.info("Skija test panel enabled (-Dopenmason.skija.test=true)");
            }
        } catch (Throwable t) {
            logger.error("Skija initialization failed — Skija widgets will fall back to ImGui", t);
            skijaContext = null;
        }
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

            projectHubScreen.setTransitionCallbacks(this::createNewProjectFile, this::openRecentProject);
            projectHubScreen.setFolderPicker(onChosen ->
                    mainInterface.getFileDialogService().showPickFolderDialog(onChosen::accept));
            projectHubScreen.setOnPreferencesClicked(mainInterface.getShowPreferencesCallback());

            // Wire recent projects service from hub into main interface for project tracking
            mainInterface.setRecentProjectsService(projectHubScreen.getRecentProjectsService());

            // Initialize keybind system BEFORE creating viewport and texture editor
            initializeKeybindSystem();

            viewportInterface = new ViewportImGuiInterface(themeManager, new PreferencesManager());
            viewportInterface.setViewport3D(mainInterface.getViewport3D());

            // Wire slideouts: rigging pane ↔ viewport tool pane (Add Part, Part Transform)
            if (mainInterface.getRiggingPane() != null) {
                mainInterface.getRiggingPane().wireSlideouts(
                        viewportInterface.getViewportUIState(), mainInterface.getViewport3D());
            }

            textureCreatorInterface = TextureCreatorImGui.createDefault();
            textureEditorWindow = new TextureEditorWindow(textureCreatorInterface);
            animationEditor = new AnimationEditorImGui();
            animationEditor.setFileDialogService(mainInterface.getFileDialogService());
            mainInterface.setAnimationEditorInterface(animationEditor);

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

            // Wire per-face texture resize dialog into texture editor's Edit menu.
            // The dialog reads/writes GPU textures via the viewport connector and
            // uploads new textures via OMTTextureLoader. Both are accessed through
            // the property panel's existing viewport adapter.
            OMTTextureLoader resizeTextureLoader = new OMTTextureLoader();
            IFaceTextureGPUService gpuService = new IFaceTextureGPUService() {
                @Override
                public int[] getTextureDimensions(int gpuTextureId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    return c != null ? c.getTextureDimensions(gpuTextureId) : null;
                }
                @Override
                public byte[] readTexturePixels(int gpuTextureId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    return c != null ? c.readTexturePixels(gpuTextureId) : null;
                }
                @Override
                public void setFaceTexture(int faceId, int materialId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    if (c != null) c.setFaceTexture(faceId, materialId);
                }
                @Override
                public float[][] computeFacePolygon2D(int faceId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    return c != null ? c.computeFacePolygon2D(faceId) : null;
                }
                @Override
                public int uploadPixelCanvasToGPU(
                        com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas canvas) {
                    return resizeTextureLoader.uploadPixelCanvasToGPU(canvas);
                }
            };
            FaceTextureResizeDialog resizeDialog = new FaceTextureResizeDialog(
                    () -> {
                        var c = mainInterface.getPropertyPanel().getViewportConnector();
                        return c != null ? c.getFaceTextureManager() : null;
                    },
                    gpuService,
                    faceEditorBridge);
            textureCreatorInterface.setFaceTextureResizeDialog(resizeDialog);

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

        if (mainInterface != null && mainInterface.getSBOTextureExportWindow() != null) {
            safeRender(() -> mainInterface.getSBOTextureExportWindow().render(), "SBO Texture Export Window");
        }

        if (mainInterface != null && mainInterface.getSBOEditorWindow() != null) {
            safeRender(() -> mainInterface.getSBOEditorWindow().render(), "SBO Editor Window");
        }

        if (mainInterface != null && mainInterface.getSBEEditorWindow() != null) {
            safeRender(() -> mainInterface.getSBEEditorWindow().render(), "SBE Editor Window");
        }

        // Render unsaved changes dialog (must be rendered outside other windows for modal to work)
        if (mainInterface != null && mainInterface.getFileMenuHandler() != null) {
            safeRender(() -> mainInterface.getFileMenuHandler().getUnsavedChangesDialog().render(),
                    "Unsaved Changes Dialog");
        }

        if (skijaTestPanel != null) {
            safeRender(() -> skijaTestPanel.render(), "Skija Test Panel");
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
     * Create a new blank project at {@code directory}/{@code name}.omp, pre-save
     * it so the file exists immediately, record it in recent projects, then open
     * the editor on the fresh project.
     */
    private void createNewProjectFile(String name, String directory) {
        String safeName = (name == null || name.isBlank()) ? "Untitled" : name.trim();
        if (directory == null || directory.isBlank()) {
            // No directory chosen — just open a fresh (unsaved) editor session.
            transitionToMainInterface();
            return;
        }

        String fileName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".omp";
        String path = java.nio.file.Path.of(directory, fileName).toString();

        transitionToMainInterface();
        boolean saved = mainInterface.saveNewProject(safeName, path);
        if (saved && projectHubScreen != null) {
            projectHubScreen.getRecentProjectsService().addProject(safeName, path);
        }
        logger.info("Created new project '{}' at {}", safeName, path);
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

        if (skijaTestPanel != null) {
            skijaTestPanel.close();
            skijaTestPanel = null;
        }
        // Hub owns Skija regions (FBOs/textures) — release them before the
        // SkijaContext that backs them is closed.
        if (projectHubScreen != null) {
            try {
                projectHubScreen.dispose();
            } catch (Exception e) {
                logger.error("Error disposing Project Hub", e);
            }
        }
        if (skijaContext != null) {
            skijaContext.close();
            skijaContext = null;
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