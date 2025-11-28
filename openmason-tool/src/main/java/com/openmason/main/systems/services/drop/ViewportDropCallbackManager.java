package com.openmason.main.systems.services.drop;

import imgui.ImGui;
import imgui.ImGuiPlatformIO;
import imgui.ImGuiViewport;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Manages GLFW drop callbacks for all ImGui platform windows.
 */
public class ViewportDropCallbackManager {

    private static final Logger logger = LoggerFactory.getLogger(ViewportDropCallbackManager.class);
    private static final Set<Long> registeredWindows = new HashSet<>();

    /**
     * Check all ImGui viewports and register drop callbacks on any new platform windows.
     * Should be called each frame after ImGui.updatePlatformWindows().
     */
    public static void updateDropCallbacks() {
        ImGuiPlatformIO platformIO = ImGui.getPlatformIO();
        int viewportCount = platformIO.getViewportsSize();

        for (int i = 0; i < viewportCount; i++) {
            ImGuiViewport viewport = platformIO.getViewports(i);
            long windowHandle = viewport.getPlatformHandle();

            if (windowHandle != 0 && !registeredWindows.contains(windowHandle)) {
                registerDropCallback(windowHandle);
                registeredWindows.add(windowHandle);
                logger.debug("Registered drop callback on viewport window: {}", windowHandle);
            }
        }
    }

    /**
     * Register a drop callback on the given GLFW window.
     * The callback queues dropped files via PendingFileDrops.
     */
    private static void registerDropCallback(long windowHandle) {
        GLFW.glfwSetDropCallback(windowHandle, (window, count, names) -> {
            String[] filePaths = new String[count];
            for (int i = 0; i < count; i++) {
                filePaths[i] = GLFWDropCallback.getName(names, i);
            }
            logger.debug("File drop received on window {}: {} file(s)", window, count);
            PendingFileDrops.queue(filePaths);
        });
    }

    /**
     * Clear the set of registered windows.
     * Should be called during cleanup to allow proper re-registration if needed.
     */
    public static void cleanup() {
        registeredWindows.clear();
        logger.debug("Cleared viewport drop callback registrations");
    }
}
