package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.masonry.MKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Letter keys follow the keyboard layout's label before documents see them (C8). */
class LayoutKeysTest {

    // GLFW key tokens (US layout positions)
    private static final int KEY_M = 77;
    private static final int KEY_Q = 81;
    private static final int KEY_SEMICOLON = 59;

    @Test
    void lettersMapToTheirLayoutLabel() {
        assertEquals(MKeys.KEY_Z, LayoutKeys.fromName(MKeys.KEY_Y, "z"), "QWERTZ: the key labelled Z sends Y");
        assertEquals(MKeys.KEY_A, LayoutKeys.fromName(KEY_Q, "a"), "AZERTY: the key labelled A sends Q");
        assertEquals(KEY_M, LayoutKeys.fromName(KEY_SEMICOLON, "M"), "upper case labels too");
    }

    @Test
    void anythingElseIsLeftAlone() {
        assertEquals(MKeys.KEY_ESCAPE, LayoutKeys.fromName(MKeys.KEY_ESCAPE, null), "non-printable keys have no name");
        assertEquals(KEY_SEMICOLON, LayoutKeys.fromName(KEY_SEMICOLON, "ö"), "non-ASCII labels keep the key");
        assertEquals(KEY_SEMICOLON, LayoutKeys.fromName(KEY_SEMICOLON, ";"));
        assertEquals(KEY_Q, LayoutKeys.fromName(KEY_Q, "qq"));
    }

    @Test
    void translateUsesTheHostsNamesAndSurvivesNoGlfw() {
        assertEquals(MKeys.KEY_Z, LayoutKeys.translate(MKeys.KEY_Y, 0, (k, s) -> "z"));
        assertEquals(MKeys.KEY_Y, LayoutKeys.translate(MKeys.KEY_Y, 0, (k, s) -> {
            throw new IllegalStateException("GLFW is not initialized");
        }));
    }
}
