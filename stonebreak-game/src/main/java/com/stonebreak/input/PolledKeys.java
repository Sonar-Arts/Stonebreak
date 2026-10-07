package com.stonebreak.input;

import com.stonebreak.ui.runtime.GameUiInput;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * The one way game code polls a held key (#288 review). Callback-driven input stops at the UI
 * document that consumes it, but a {@code glfwGetKey} poll would still see the key: Escape that
 * closed a document's dialog would also toggle pause, and typing "e" in a document's text field
 * would open the inventory. A key is reported up only while no open document owns it
 * ({@link GameUiInput#masksKey}).
 */
public final class PolledKeys {

    private PolledKeys() {
    }

    /** True while {@code key} is physically held and no UI document owns it. */
    public static boolean isDown(long window, int key) {
        return physicallyDown(window, key) && !GameUiInput.get().masksKey(key);
    }

    /** The raw GLFW state, ignoring documents; only for edge bookkeeping. */
    static boolean physicallyDown(long window, int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }
}
