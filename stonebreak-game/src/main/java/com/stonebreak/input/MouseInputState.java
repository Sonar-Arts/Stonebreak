package com.stonebreak.input;

import java.util.Arrays;

import org.joml.Vector2f;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Raw mouse state shared by every input collaborator: per-button held state,
 * "pressed this frame" edges consumed by UI screens, the UI-space cursor
 * position, and the pending scroll offset. Pure state — no routing decisions.
 */
final class MouseInputState {

    private final boolean[] buttonDown = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] pressedThisFrame = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    // Tracks which buttons saw a press so beginFrame() only clears what it must.
    private final boolean[] wasPressed = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];

    private float mouseX;
    private float mouseY;
    private final Vector2f cachedPosition = new Vector2f();

    private double scrollYOffset;

    /** Records a GLFW mouse button event. Must run before any routing so screens polling this frame see it. */
    void onButtonEvent(int button, int action) {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) {
            return;
        }
        if (action == GLFW_PRESS) {
            buttonDown[button] = true;
            pressedThisFrame[button] = true;
            wasPressed[button] = true;
        } else if (action == GLFW_RELEASE) {
            buttonDown[button] = false;
        }
    }

    /** Call at the start of each frame's input cycle to expire "pressed this frame" edges. */
    void beginFrame() {
        for (int i = 0; i < wasPressed.length; i++) {
            if (wasPressed[i]) {
                pressedThisFrame[i] = false;
                wasPressed[i] = false;
            }
        }
    }

    boolean isDown(int button) {
        return button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST && buttonDown[button];
    }

    boolean isPressed(int button) {
        return button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST && pressedThisFrame[button];
    }

    void consumePress(int button) {
        if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
            pressedThisFrame[button] = false;
        }
    }

    /**
     * Forgets all held/pressed button state. Called when the game state
     * transitions back to PLAYING so clicks consumed by a menu (e.g. the pause
     * menu's Resume button) don't leak into gameplay as attacks/block breaking.
     * Also covers releases the InputHandler never saw because Main routes mouse
     * events to dedicated menu handlers in states like SETTINGS and MAIN_MENU,
     * which otherwise left the held flag stuck true.
     */
    void clearAll() {
        Arrays.fill(buttonDown, false);
        Arrays.fill(pressedThisFrame, false);
        Arrays.fill(wasPressed, false);
    }

    void setPosition(float x, float y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    float x() {
        return mouseX;
    }

    float y() {
        return mouseY;
    }

    /** Reuses a cached vector to avoid per-call allocation. */
    Vector2f position() {
        return cachedPosition.set(mouseX, mouseY);
    }

    void setScroll(double yOffset) {
        this.scrollYOffset = yOffset;
    }

    void resetScroll() {
        this.scrollYOffset = 0.0;
    }

    double getAndResetScroll() {
        double offset = scrollYOffset;
        scrollYOffset = 0.0;
        return offset;
    }
}
