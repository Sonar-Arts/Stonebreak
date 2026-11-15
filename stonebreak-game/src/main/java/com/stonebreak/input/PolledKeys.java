package com.stonebreak.input;

import com.openmason.engine.platform.input.GlfwKeys;
import com.stonebreak.ui.runtime.GameUiInput;

/**
 * The one way game code polls a held key outside {@link InputHandler} (#288 review). Callback-driven
 * input stops at the UI document that consumes it, but a key poll would still see the key: Escape
 * that closed a document's dialog would also toggle pause, and typing "e" in a document's text field
 * would open the inventory. A key is reported up only while no open document owns it
 * ({@link GameUiInput#masksKey}). The polling itself is the engine's ({@link GlfwKeys}, #96).
 */
public final class PolledKeys {

    private PolledKeys() {
    }

    /** True while {@code key} is physically held and no UI document owns it. */
    public static boolean isDown(long window, int key) {
        return physicallyDown(window, key) && !GameUiInput.get().masksKey(key);
    }

    /** The raw key state, ignoring documents; only for modifier bookkeeping. */
    static boolean physicallyDown(long window, int key) {
        return GlfwKeys.isDown(window, key);
    }
}
