package com.openmason.main.platform;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW library lifecycle and platform-backend policy for the tool.
 *
 * <p>Owns the error callback, the Linux X11-vs-Wayland backend selection (with the
 * init-time fallback when a pinned X11 backend has no reachable display), and the final
 * {@code glfwTerminate}. Window creation lives in {@link AppWindow}.
 */
public final class GlfwPlatform {

    private static final Logger logger = LoggerFactory.getLogger(GlfwPlatform.class);

    /**
     * Initialize GLFW library with error callback.
     */
    public void initialize() {
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
     * Terminate GLFW and release the error callback. The application window must
     * already have been destroyed (see {@link AppWindow#destroy()}).
     */
    public void terminate() {
        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }
}
