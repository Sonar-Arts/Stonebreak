package com.stonebreak.core.window;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stonebreak.config.Settings;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_CCW;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFrontFace;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * The game's GLFW window: creation, the GL context, and the size/refresh-rate bookkeeping that
 * everything else measures against.
 *
 * <p>Two coordinate spaces matter here and are easy to confuse. {@code glfwGetCursorPos} reports in
 * window (screen) coordinates, while the UI is laid out in framebuffer pixels — the same on Windows
 * and non-HiDPI X11, different on Wayland/HiDPI. {@link #width()}/{@link #height()} are always
 * framebuffer pixels, and {@link #uiCursorPos} converts into that space.</p>
 */
public final class GameWindow {

    private static final Logger logger = LoggerFactory.getLogger(GameWindow.class);

    /** Notified after the framebuffer size changes, once the viewport has been updated. */
    @FunctionalInterface
    public interface ResizeListener {
        void onFramebufferResized(int width, int height);
    }

    private long handle;
    private int width;
    private int height;

    /**
     * Framebuffer-pixels per window-coordinate: 1.0 when the two spaces match, different on
     * Wayland/HiDPI where UI input must be scaled to line up with rendered UI.
     */
    private double cursorScaleX = 1.0;
    private double cursorScaleY = 1.0;

    /**
     * True once the GL context is current and LWJGL capabilities are created. GLFW may fire the
     * framebuffer-size callback while the window is being shown/positioned (notably on Linux) before
     * GL is usable, so GL calls in that callback must be gated on this or the JVM aborts.
     */
    private volatile boolean glReady = false;

    /**
     * Detected monitor refresh rate, used as the target FPS when VSync is enabled. Capping at the
     * display rate gives the same tear suppression as driver VSync without the half-rate fallback
     * that double-buffered swap-interval=1 imposes when frames miss vblank.
     */
    private int monitorRefreshHz = 60;

    private ResizeListener resizeListener = (w, h) -> { };

    public GameWindow(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public long handle() {
        return handle;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void setResizeListener(ResizeListener listener) {
        this.resizeListener = listener != null ? listener : (w, h) -> { };
    }

    /**
     * Creates the window, makes its GL context current and shows it. GLFW must already be initialized
     * (see {@link DisplayBackend#initialize()}); callbacks are installed separately.
     */
    public void create(String title) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // Request a compatible profile — this allows OpenGL 3.2 features when available
        // but falls back to the compatibility profile if needed.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);

        // Wayland identifies applications by app_id (taskbar grouping, icon, window
        // rules); ignored on other platforms.
        glfwWindowHintString(GLFW_WAYLAND_APP_ID, "stonebreak");

        handle = glfwCreateWindow(width, height, title, 0, 0);
        if (handle == 0) {
            throw new IllegalStateException("Failed to create the GLFW window");
        }

        center();

        // Capture the VSync target from the monitor the window actually sits on (not always the
        // primary), and keep it current as monitors are hot-plugged.
        refreshMonitorHz();
        glfwSetMonitorCallback((monitor, event) -> refreshMonitorHz());

        glfwMakeContextCurrent(handle);
        applyVsyncSetting();
        glfwShowWindow(handle);

        // Critical for LWJGL's interoperation with a GLFW-managed OpenGL context: it detects the
        // context current on this thread, creates GLCapabilities and makes the bindings usable.
        GL.createCapabilities();
        glReady = true;

        adoptActualFramebufferSize();
        updateCursorScale();
        applyInitialGlState();
    }

    /** Places the window centred on the primary monitor; a no-op on Wayland, which forbids it. */
    private void center() {
        // Wayland clients cannot position their own windows (glfwSetWindowPos would only emit
        // GLFW_FEATURE_UNAVAILABLE); the compositor decides placement there.
        if (DisplayBackend.isWayland()) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer windowWidth = stack.mallocInt(1);
            IntBuffer windowHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, windowWidth, windowHeight);

            GLFWVidMode videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (videoMode == null) {
                logger.warn("Could not get video mode for primary monitor. Window will not be centered.");
                return;
            }
            glfwSetWindowPos(handle,
                    (videoMode.width() - windowWidth.get(0)) / 2,
                    (videoMode.height() - windowHeight.get(0)) / 2);
        }
    }

    /**
     * Adopts the framebuffer size GLFW actually gave us, which may differ from the requested size on
     * HiDPI displays and covers resize events skipped while GL was not yet ready.
     */
    private void adoptActualFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferWidth = stack.mallocInt(1);
            IntBuffer framebufferHeight = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
            width = framebufferWidth.get(0);
            height = framebufferHeight.get(0);
            glViewport(0, 0, width, height);
            resizeListener.onFramebufferResized(width, height);

            IntBuffer windowWidth = stack.mallocInt(1);
            IntBuffer windowHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, windowWidth, windowHeight);
            logger.info("[Display] window={}x{} framebuffer={}x{}",
                    windowWidth.get(0), windowHeight.get(0), width, height);
        }
    }

    private static void applyInitialGlState() {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
    }

    /**
     * Records a framebuffer resize reported by GLFW. GL calls are gated on {@link #glReady} because
     * this can fire before the context is current; the stored size is re-applied by
     * {@link #adoptActualFramebufferSize()} once GL is up.
     */
    public void onFramebufferResized(int newWidth, int newHeight) {
        width = newWidth;
        height = newHeight;
        if (glReady) {
            glViewport(0, 0, newWidth, newHeight);
        }
        updateCursorScale();
        resizeListener.onFramebufferResized(newWidth, newHeight);
    }

    /**
     * Re-synchronizes render/UI state from the window's ACTUAL framebuffer size. Call after
     * programmatically changing the window size (e.g. applying a resolution setting): GLFW/Wayland may
     * clamp or ignore the requested size and may not deliver the framebuffer-size callback
     * synchronously, so we read the real size back to prevent a stale-viewport glitch.
     */
    public void syncFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferWidth = stack.mallocInt(1);
            IntBuffer framebufferHeight = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
            width = framebufferWidth.get(0);
            height = framebufferHeight.get(0);
        }
        if (glReady) {
            glViewport(0, 0, width, height);
        }
        updateCursorScale();
        resizeListener.onFramebufferResized(width, height);
        // The window may now sit on a different monitor; keep the VSync cap in sync.
        refreshMonitorHz();
    }

    /**
     * Recomputes the window-coordinate to framebuffer-pixel scale used to map cursor positions into
     * UI space. Called whenever the window or framebuffer size changes.
     */
    public void updateCursorScale() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer windowWidth = stack.mallocInt(1);
            IntBuffer windowHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, windowWidth, windowHeight);
            cursorScaleX = windowWidth.get(0) > 0 ? (double) width / windowWidth.get(0) : 1.0;
            cursorScaleY = windowHeight.get(0) > 0 ? (double) height / windowHeight.get(0) : 1.0;
        }
    }

    /** Converts a raw cursor position into framebuffer-pixel (UI) space. */
    public double toUiX(double windowX) {
        return windowX * cursorScaleX;
    }

    /** Converts a raw cursor position into framebuffer-pixel (UI) space. */
    public double toUiY(double windowY) {
        return windowY * cursorScaleY;
    }

    /**
     * Reads the cursor position converted into framebuffer-pixel (UI) space. Use for all UI
     * hit-testing so clicks line up with rendered UI on Wayland/HiDPI where the spaces differ.
     */
    public void uiCursorPos(DoubleBuffer x, DoubleBuffer y) {
        glfwGetCursorPos(handle, x, y);
        x.put(0, toUiX(x.get(0)));
        y.put(0, toUiY(y.get(0)));
    }

    /**
     * Sets {@link #monitorRefreshHz} from the monitor the window currently occupies (largest
     * window/monitor overlap) rather than always the primary, so the VSync frame cap matches the
     * display the game is shown on.
     *
     * <p>On Wayland the compositor never exposes window positions ({@code glfwGetWindowPos} would emit
     * GLFW_FEATURE_UNAVAILABLE and return 0,0), so the occupied monitor cannot be determined. There we
     * cap at the fastest connected display instead: an over-cap is harmless, while capping a 144 Hz
     * display at a slower primary's 60 Hz would visibly degrade.</p>
     */
    public void refreshMonitorHz() {
        if (handle == 0) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            long bestMonitor = DisplayBackend.isWayland()
                    ? fastestMonitor()
                    : monitorUnderWindow(stack);

            GLFWVidMode bestMode = glfwGetVideoMode(bestMonitor);
            if (bestMode != null && bestMode.refreshRate() > 0 && bestMode.refreshRate() != monitorRefreshHz) {
                monitorRefreshHz = bestMode.refreshRate();
                logger.info("[Display] Using monitor refresh rate: {} Hz", monitorRefreshHz);
            }
        }
    }

    private static long fastestMonitor() {
        long best = glfwGetPrimaryMonitor();
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors == null) {
            return best;
        }
        int bestHz = -1;
        for (int i = 0; i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode != null && mode.refreshRate() > bestHz) {
                bestHz = mode.refreshRate();
                best = monitor;
            }
        }
        return best;
    }

    private long monitorUnderWindow(MemoryStack stack) {
        long best = glfwGetPrimaryMonitor();
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors == null) {
            return best;
        }
        IntBuffer windowX = stack.mallocInt(1);
        IntBuffer windowY = stack.mallocInt(1);
        IntBuffer windowWidth = stack.mallocInt(1);
        IntBuffer windowHeight = stack.mallocInt(1);
        glfwGetWindowPos(handle, windowX, windowY);
        glfwGetWindowSize(handle, windowWidth, windowHeight);
        int winX = windowX.get(0);
        int winY = windowY.get(0);
        int winW = windowWidth.get(0);
        int winH = windowHeight.get(0);

        IntBuffer monitorX = stack.mallocInt(1);
        IntBuffer monitorY = stack.mallocInt(1);
        long bestArea = -1;
        for (int i = 0; i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode == null) {
                continue;
            }
            glfwGetMonitorPos(monitor, monitorX, monitorY);
            int monX = monitorX.get(0);
            int monY = monitorY.get(0);
            // Overlap area between the window rect and this monitor rect.
            int overlapX = Math.max(0, Math.min(winX + winW, monX + mode.width()) - Math.max(winX, monX));
            int overlapY = Math.max(0, Math.min(winY + winH, monY + mode.height()) - Math.max(winY, monY));
            long area = (long) overlapX * overlapY;
            if (area > bestArea) {
                bestArea = area;
                best = monitor;
            }
        }
        return best;
    }

    /**
     * VSync here means "cap to monitor refresh rate via the manual limiter", not the driver's
     * swap-interval=1. The cap delivers the same anti-tear benefit on G-Sync/FreeSync displays without
     * the half-rate fallback that double-buffered swap-interval=1 forces when a frame misses vblank.
     *
     * <p>swapInterval is left at 0 unconditionally so the driver never blocks us — the frame-budget
     * sleep in the game loop does the work.</p>
     */
    public void applyVsyncSetting() {
        glfwSwapInterval(0);
        Settings settings = Settings.getInstance();
        String fpsCap = settings.isMaxFpsUnlimited() ? "unlimited" : settings.getMaxFps() + " FPS";
        logger.info("[Display] VSync {}, Max FPS {}",
                settings.isVsyncEnabled() ? "enabled (cap " + monitorRefreshHz + " Hz)" : "disabled",
                fpsCap);
    }

    /**
     * The per-frame nanosecond budget for the manual FPS limiter, or {@code 0} for fully uncapped (no
     * sleep). The effective cap is the lowest of the active limits: the monitor refresh rate when
     * VSync is on, and the user's Max FPS setting when it is not Unlimited.
     */
    public long frameBudgetNanos() {
        Settings settings = Settings.getInstance();
        int cap = Integer.MAX_VALUE;
        if (settings.isVsyncEnabled() && monitorRefreshHz > 0) {
            cap = Math.min(cap, monitorRefreshHz);
        }
        if (!settings.isMaxFpsUnlimited()) {
            cap = Math.min(cap, settings.getMaxFps());
        }
        if (cap == Integer.MAX_VALUE || cap <= 0) {
            return 0L; // no active cap — skip the sleep entirely
        }
        return 1_000_000_000L / cap;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    /** Re-asserts our GL context if something else made a different one current. */
    public boolean isContextCurrent() {
        return glfwGetCurrentContext() == handle;
    }

    public void makeContextCurrent() {
        glfwMakeContextCurrent(handle);
    }

    public void destroy() {
        if (handle != 0) {
            glfwDestroyWindow(handle);
            handle = 0;
        }
    }
}
