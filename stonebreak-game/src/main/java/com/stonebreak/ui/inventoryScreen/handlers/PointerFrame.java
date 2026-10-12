package com.stonebreak.ui.inventoryScreen.handlers;

import org.joml.Vector2f;

/**
 * One frame of pointer input as the container screens' slot rules read it: where the pointer
 * is and which button edges/holds happened. UI documents (#289) synthesize it at a slot's position,
 * so a slot click from a document runs the pick-up / place / swap / split / gather rules.
 *
 * @param leftPressed   left button went down this frame
 * @param rightPressed  right button went down this frame
 * @param rightDown     right button is held (right-drag distribution)
 * @param middlePressed middle button went down this frame
 * @param shift         a Shift key is held
 */
public record PointerFrame(float x, float y, boolean leftPressed, boolean rightPressed, boolean rightDown,
                           boolean middlePressed, boolean shift) {

    /** No buttons: the frame after a click, which ends a press the way a real release frame does. */
    public static PointerFrame idle(float x, float y) {
        return new PointerFrame(x, y, false, false, false, false, false);
    }

    public Vector2f position() {
        return new Vector2f(x, y);
    }
}
