package com.openmason.main.systems.mortar.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mortar type ramp is anchored to the ImGui body size (issue #272), and
 * the density scale token is sanitised before it scales a whole canvas.
 */
class MortarTypeTest {

    @Test
    void rampMatchesImGuiBody16() {
        assertEquals(16f, MortarType.IMGUI_BODY);
        assertEquals(16f, MortarType.TITLE);
        assertEquals(14f, MortarType.BODY);
        assertEquals(13f, MortarType.CONTROL);
        assertEquals(12f, MortarType.LABEL);
        assertEquals(11f, MortarType.CAPTION);
    }

    @Test
    void rampIsStrictlyDescending() {
        float[] ramp = {MortarType.TITLE, MortarType.BODY, MortarType.CONTROL,
                MortarType.LABEL, MortarType.CAPTION};
        for (int i = 1; i < ramp.length; i++) {
            assertTrue(ramp[i] < ramp[i - 1], "ramp step " + i + " must be smaller than the previous");
        }
    }

    @Test
    void densityScalesPassThrough() {
        assertEquals(0.8f, MortarTheme.sanitizeScale(0.8f));
        assertEquals(1.0f, MortarTheme.sanitizeScale(1.0f));
        assertEquals(1.5f, MortarTheme.sanitizeScale(1.5f));
    }

    @Test
    void invalidScalesFallBackOrClamp() {
        assertEquals(1f, MortarTheme.sanitizeScale(0f));
        assertEquals(1f, MortarTheme.sanitizeScale(-2f));
        assertEquals(1f, MortarTheme.sanitizeScale(Float.NaN));
        assertEquals(1f, MortarTheme.sanitizeScale(Float.POSITIVE_INFINITY));
        assertEquals(0.5f, MortarTheme.sanitizeScale(0.1f));
        assertEquals(4f, MortarTheme.sanitizeScale(10f));
    }
}
