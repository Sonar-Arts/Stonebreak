package com.openmason.main.platform;

import com.openmason.main.omConfig;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The tool's main GLFW window: context hints, creation with size fallback, size limits,
 * icon, window callbacks, primary-monitor centering, GL context/capabilities and show.
 * Persists size changes to {@link omConfig}; close requests are forwarded to the owner.
 */
public final class AppWindow {

    private static final Logger logger = LoggerFactory.getLogger(AppWindow.class);

    private static final String APP_TITLE = "OpenMason - Voxel Game Engine & Toolset";
    private static final int MIN_WIDTH = 1200;
    private static final int MIN_HEIGHT = 800;
    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 1000;

    private final omConfig omConfig;
    private final Runnable closeRequestHandler;
    private long window;

    /**
     * @param omConfig            source of the last window size / vsync setting; receives size updates
     * @param closeRequestHandler invoked when the OS asks the window to close (the request is
     *                            intercepted so the owner can confirm unsaved changes)
     */
    public AppWindow(omConfig omConfig, Runnable closeRequestHandler) {
        this.omConfig = omConfig;
        this.closeRequestHandler = closeRequestHandler;
    }

    /** Native GLFW window handle ({@code NULL} before {@link #create()} / after {@link #destroy()}). */
    public long handle() {
        return window;
    }

    /**
     * Create and configure GLFW window with OpenGL context.
     */
    public void create() {
        configureWindowHints();

        int width = getValidWindowWidth();
        int height = getValidWindowHeight();

        window = createWindowWithFallback(width, height);
        glfwSetWindowSizeLimits(window, MIN_WIDTH, MIN_HEIGHT, GLFW_DONT_CARE, GLFW_DONT_CARE);
        WindowIcon.apply(window);

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
     * Setup window event callbacks.
     */
    private void setupWindowCallbacks() {
        glfwSetWindowCloseCallback(window, w -> {
            glfwSetWindowShouldClose(w, false);
            closeRequestHandler.run();
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

    /** Destroy the native window if it exists; safe to call repeatedly. */
    public void destroy() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
    }
}
