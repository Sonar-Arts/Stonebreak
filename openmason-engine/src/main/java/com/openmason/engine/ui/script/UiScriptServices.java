package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.UiValue;

/**
 * What a host lets scripts ask for beyond the document and its {@code UiHost} actions (#292):
 * sounds, navigation and closing the screen. Each is a request the host may refuse; none gives a
 * script a game object. The editor preview logs them; the game plays and navigates.
 */
public interface UiScriptServices {

    UiScriptServices NONE = new UiScriptServices() {
    };

    /** {@code ui.sound(id, opts)}: a sound asset or event id. */
    default void playSound(String id, UiValue.Obj options) {
    }

    /**
     * {@code ui.navigate(target, args)}.
     *
     * @return false when this host has no such target (the script gets an error)
     */
    default boolean navigate(String target, UiValue.Obj args) {
        return false;
    }

    /** {@code ui.close()}: the screen asks to be closed; the host decides when. */
    default void requestClose() {
    }
}
