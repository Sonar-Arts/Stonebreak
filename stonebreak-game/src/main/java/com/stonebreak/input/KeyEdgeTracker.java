package com.stonebreak.input;

import com.stonebreak.ui.runtime.GameUiInput;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;

/**
 * Edge-detects polled keys: {@link #pressedOnce(int)} fires only on the frame a
 * key transitions from released to held, replacing the dozen per-key
 * {@code xxxKeyPressed} boolean fields that used to accumulate on InputHandler.
 * Callers must poll the same key every frame for the edge to track correctly.
 *
 * <p>A key an open UI document owns ({@link GameUiInput#masksKey}) reads as up, and its edge
 * is spent while masked: a key held through a document's text field does not fire as a fresh
 * press once the field lets go of it.
 */
final class KeyEdgeTracker {

    private final long window;
    private final boolean[] previouslyDown = new boolean[GLFW_KEY_LAST + 1];

    KeyEdgeTracker(long window) {
        this.window = window;
    }

    /** True only on the poll where the key goes down; false while held or released. */
    boolean pressedOnce(int key) {
        boolean physical = PolledKeys.physicallyDown(window, key);
        if (GameUiInput.get().masksKey(key)) {
            previouslyDown[key] = physical;
            return false;
        }
        boolean fired = physical && !previouslyDown[key];
        previouslyDown[key] = physical;
        return fired;
    }

    /**
     * Forgets the held state so the next poll fires even if the key never
     * released in between — used by handlers that suppress a key in some game
     * states but want a held key to act immediately on re-entry.
     */
    void reset(int key) {
        previouslyDown[key] = false;
    }

    boolean isDown(int key) {
        return PolledKeys.isDown(window, key);
    }
}
