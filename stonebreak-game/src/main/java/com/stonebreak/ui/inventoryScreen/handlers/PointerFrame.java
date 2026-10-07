package com.stonebreak.ui.inventoryScreen.handlers;

import com.stonebreak.input.InputHandler;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * One frame of pointer input as the container screens' slot rules read it: where the pointer
 * is and which button edges/holds happened. The legacy screens poll it from {@link InputHandler}
 * each frame; UI documents (#289) synthesize it at a slot's position so a slot click from a
 * document runs exactly the same pick-up / place / swap / split / gather rules.
 *
 * @param leftPressed   left button went down this frame
 * @param rightPressed  right button went down this frame
 * @param rightDown     right button is held (right-drag distribution)
 * @param middlePressed middle button went down this frame
 * @param shift         a Shift key is held
 */
public record PointerFrame(float x, float y, boolean leftPressed, boolean rightPressed, boolean rightDown,
                           boolean middlePressed, boolean shift) {

    /** Polls the real mouse and keyboard. */
    public static PointerFrame poll(InputHandler input) {
        Vector2f m = input.getMousePosition();
        boolean shift = input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || input.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
        return new PointerFrame(m.x, m.y,
            input.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            input.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            input.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            input.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE),
            shift);
    }

    /** No buttons: the frame after a click, which ends a press the way a real release frame does. */
    public static PointerFrame idle(float x, float y) {
        return new PointerFrame(x, y, false, false, false, false, false);
    }

    public Vector2f position() {
        return new Vector2f(x, y);
    }
}
