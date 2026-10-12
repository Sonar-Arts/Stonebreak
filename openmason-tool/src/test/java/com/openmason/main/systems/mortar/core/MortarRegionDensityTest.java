package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.mortar.theme.MortarTheme;
import imgui.ImGui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Logical-px conversion follows ImGui's text scale (density x style font scales), clamped the
 * same way the theme token is. Headless ImGui context, no frame.
 */
class MortarRegionDensityTest {

    @BeforeEach
    void context() {
        ImGui.createContext();
        ImGui.getIO().setIniFilename(null);
    }

    @AfterEach
    void destroy() {
        ImGui.destroyContext();
    }

    @Test
    void normalDensityIsOneToOne() {
        assertEquals(1f, MortarRegion.scale());
        assertEquals(24f, MortarRegion.toPixels(24f));
    }

    @Test
    void densityScalesLogicalLengths() {
        ImGui.getIO().setFontGlobalScale(1.25f);
        assertEquals(1.25f, MortarRegion.scale());
        assertEquals(10f, MortarRegion.toPixels(8f));
        assertEquals(MortarRegion.scale(), MortarTheme.capture().scale, "region and theme agree on density");
    }

    @Test
    void styleFontScalesCompound() {
        ImGui.getIO().setFontGlobalScale(1.5f);
        ImGui.getStyle().setFontScaleDpi(2f);
        assertEquals(3f, MortarRegion.scale());
    }

    @Test
    void absurdScalesAreClamped() {
        ImGui.getIO().setFontGlobalScale(10f);
        assertEquals(4f, MortarRegion.scale());
        ImGui.getIO().setFontGlobalScale(0f);
        assertEquals(1f, MortarRegion.scale(), "a zero scale falls back to 1, never collapses the canvas");
    }
}
