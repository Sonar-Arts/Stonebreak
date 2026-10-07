package com.stonebreak.ui.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;

/** Letter keys follow the keyboard layout's label before documents see them (C8). */
class LayoutKeysTest {

    @Test
    void lettersMapToTheirLayoutLabel() {
        assertEquals(GLFW_KEY_Z, LayoutKeys.fromName(GLFW_KEY_Y, "z"), "QWERTZ: the key labelled Z sends Y");
        assertEquals(GLFW_KEY_A, LayoutKeys.fromName(GLFW_KEY_Q, "a"), "AZERTY: the key labelled A sends Q");
        assertEquals(GLFW_KEY_M, LayoutKeys.fromName(GLFW_KEY_SEMICOLON, "M"), "upper case labels too");
    }

    @Test
    void anythingElseIsLeftAlone() {
        assertEquals(GLFW_KEY_ESCAPE, LayoutKeys.fromName(GLFW_KEY_ESCAPE, null), "non-printable keys have no name");
        assertEquals(GLFW_KEY_SEMICOLON, LayoutKeys.fromName(GLFW_KEY_SEMICOLON, "ö"), "non-ASCII labels keep the key");
        assertEquals(GLFW_KEY_SEMICOLON, LayoutKeys.fromName(GLFW_KEY_SEMICOLON, ";"));
        assertEquals(GLFW_KEY_Q, LayoutKeys.fromName(GLFW_KEY_Q, "qq"));
    }
}
