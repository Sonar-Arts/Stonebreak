package com.openmason.main.systems.viewport.input;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.DoubleBuffer;

/**
 * Manages GLFW mouse capture for endless dragging in the viewport.
 * Handles cursor visibility, position restoration, and raw mouse motion.
 *
 * Responsibilities:
 * - Capture mouse (disable cursor, enable unlimited movement)
 * - Release mouse (restore cursor visibility and position)
 * - Track capture state
 * - Support raw mouse motion if available
 */
public class MouseCaptureManager {

    private static final Logger logger = LoggerFactory.getLogger(MouseCaptureManager.class);

    // Mouse capture state
    private boolean isMouseCaptured = false;
    private double savedCursorX = 0.0;
    private double savedCursorY = 0.0;
    private long windowHandle = 0L;
    private boolean rawMouseMotionSupported = false;

    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This should be called from the main application.
     */
    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        if (windowHandle != 0L) {
            // Check if raw mouse motion is supported
            this.rawMouseMotionSupported = GLFW.glfwRawMouseMotionSupported();
        }
    }

    /**
     * Capture the mouse cursor for endless dragging.
     * Hides cursor and enables unlimited mouse movement.
     */
    public void captureMouse() {
        if (windowHandle == 0L || isMouseCaptured) {
            return;
        }

        try {
            // Save current cursor position
            DoubleBuffer xPos = BufferUtils.createDoubleBuffer(1);
            DoubleBuffer yPos = BufferUtils.createDoubleBuffer(1);
            GLFW.glfwGetCursorPos(windowHandle, xPos, yPos);
            savedCursorX = xPos.get(0);
            savedCursorY = yPos.get(0);

            // Disable cursor (hides it and enables infinite movement)
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);

            // Enable raw mouse motion if supported for better camera control
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
            }

            isMouseCaptured = true;

        } catch (Exception e) {
            logger.error("Failed to capture mouse", e);
        }
    }

    /**
     * Release mouse capture and restore cursor visibility and position.
     */
    public void releaseMouse() {
        if (windowHandle == 0L || !isMouseCaptured) {
            return;
        }

        try {
            // Restore normal cursor mode
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);

            // Disable raw mouse motion
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            }

            // Restore cursor position
            GLFW.glfwSetCursorPos(windowHandle, savedCursorX, savedCursorY);

            isMouseCaptured = false;

        } catch (Exception e) {
            logger.error("Failed to release mouse", e);
        }
    }

    // Getters

    public boolean isMouseCaptured() {
        return isMouseCaptured;
    }

    public boolean isRawMouseMotionSupported() {
        return rawMouseMotionSupported;
    }

    public double getSavedCursorX() {
        return savedCursorX;
    }

    public double getSavedCursorY() {
        return savedCursorY;
    }

    public long getWindowHandle() {
        return windowHandle;
    }
}
