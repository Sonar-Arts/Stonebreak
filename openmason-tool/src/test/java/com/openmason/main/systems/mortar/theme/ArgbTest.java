package com.openmason.main.systems.mortar.theme;

import imgui.ImVec4;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgbTest {

    @Test
    void packsComponents() {
        assertEquals(0xFFFFFFFF, Argb.of(1f, 1f, 1f, 1f));
        assertEquals(0xFF000000, Argb.of(0f, 0f, 0f, 1f));
        assertEquals(0xFFFF0000, Argb.of(1f, 0f, 0f, 1f));
        assertEquals(0x0000FF00, Argb.of(0f, 1f, 0f, 0f));
    }

    @Test
    void packsImVec4() {
        assertEquals(0xFFFF0000, Argb.of(new ImVec4(1f, 0f, 0f, 1f)));
    }

    @Test
    void clampsOutOfRangeComponents() {
        assertEquals(0xFFFFFFFF, Argb.of(2f, 2f, 2f, 2f));
        assertEquals(0x00000000, Argb.of(-1f, -1f, -1f, -1f));
    }

    @Test
    void withAlphaReplacesAlphaByte() {
        assertEquals(0xFFFF0000, Argb.withAlpha(0x00FF0000, 1f));
        assertEquals(0x80FF0000, Argb.withAlpha(0xFFFF0000, 0.5019608f));
        assertEquals(0xFF112233, Argb.withAlpha(new ImVec4(0x11 / 255f, 0x22 / 255f, 0x33 / 255f, 0f), 1f));
    }

    @Test
    void shadeTowardWhiteAndBlackPreservesAlpha() {
        assertEquals(0xFFFFFFFF, Argb.shade(0xFF808080, 1f));
        assertEquals(0xFF000000, Argb.shade(0xFF808080, -1f));
        // Zero factor is a no-op.
        assertEquals(0xAB123456, Argb.shade(0xAB123456, 0f));
    }

    @Test
    void lerpBlendsAllChannels() {
        assertEquals(0xFF000000, Argb.lerp(0xFF000000, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, Argb.lerp(0xFF000000, 0xFFFFFFFF, 1f));
        assertEquals(0xFF7F7F7F, Argb.lerp(0xFF000000, 0xFFFFFFFF, 0.5f));
    }
}
