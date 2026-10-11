package com.openmason.main.systems.mortar.theme;

import com.openmason.main.systems.themes.utils.ThemeColors;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token resolution from the live ImGui style: which style slot feeds which token, the alpha and
 * shade derivations, the transparent-border fallback and on-accent text choice. Headless ImGui
 * context (no frame, no window); theme-driven semantic tones are reset to their built-in values.
 */
class MortarThemeCaptureTest {

    private ImGuiStyle style;

    @BeforeEach
    void context() {
        ThemeColors.useSemanticColors(null, false);
        ImGui.createContext();
        ImGui.getIO().setIniFilename(null);
        style = ImGui.getStyle();
        style.setColor(ImGuiCol.WindowBg, 0.10f, 0.10f, 0.10f, 1f);
        style.setColor(ImGuiCol.FrameBg, 0.20f, 0.20f, 0.20f, 1f);
        style.setColor(ImGuiCol.FrameBgHovered, 0.30f, 0.30f, 0.30f, 1f);
        style.setColor(ImGuiCol.HeaderActive, 0.20f, 0.40f, 0.80f, 1f);
        style.setColor(ImGuiCol.Text, 0.90f, 0.90f, 0.90f, 1f);
        style.setColor(ImGuiCol.TextDisabled, 0.60f, 0.60f, 0.60f, 1f);
        style.setColor(ImGuiCol.Border, 0.50f, 0.50f, 0.50f, 1f);
        style.setColor(ImGuiCol.Separator, 0.40f, 0.40f, 0.40f, 1f);
    }

    @AfterEach
    void destroy() {
        ImGui.destroyContext();
        ThemeColors.useSemanticColors(null, false);
    }

    @Test
    void styleSlotsMapToTokens() {
        MortarTheme t = MortarTheme.capture();
        assertEquals(Argb.of(0.10f, 0.10f, 0.10f, 1f), t.background);
        assertEquals(Argb.of(0.20f, 0.20f, 0.20f, 1f), t.surface);
        assertEquals(Argb.of(0.30f, 0.30f, 0.30f, 1f), t.surfaceHover);
        assertEquals(Argb.of(0.20f, 0.40f, 0.80f, 1f), t.accent);
        assertEquals(Argb.of(0.90f, 0.90f, 0.90f, 1f), t.text);
        assertEquals(Argb.of(0.60f, 0.60f, 0.60f, 1f), t.textDim, "textDim is ImGui's TextDisabled");
        assertEquals(Argb.of(0.50f, 0.50f, 0.50f, 1f), t.border);
        assertEquals(Argb.of(0.40f, 0.40f, 0.40f, 1f), t.separator);
        assertEquals(0x44000000, t.shadow);
    }

    @Test
    void accentIsAlwaysOpaqueWhileBorderStrongKeepsAFixedAlpha() {
        style.setColor(ImGuiCol.HeaderActive, 0.20f, 0.40f, 0.80f, 0.30f);
        MortarTheme t = MortarTheme.capture();
        assertEquals(0xFF, t.accent >>> 24, "a translucent HeaderActive still yields an opaque accent");
        assertEquals(t.accent & 0x00FFFFFF, t.borderStrong & 0x00FFFFFF);
        assertEquals(Math.round(0.85f * 255), t.borderStrong >>> 24);
    }

    @Test
    void hoverAndBadgeTokensAreLightenedShades() {
        MortarTheme t = MortarTheme.capture();
        assertEquals(Argb.shade(t.accent, 0.12f), t.accentHover);
        assertEquals(Argb.shade(t.surface, 0.10f), t.badgeBg);
        assertEquals(Argb.shade(t.danger, 0.12f), t.dangerHover);
        assertTrue(Argb.relativeLuminance(t.accentHover) > Argb.relativeLuminance(t.accent));
    }

    @Test
    void textFaintIsTextAtThirtyFivePercent() {
        MortarTheme t = MortarTheme.capture();
        assertEquals(t.text & 0x00FFFFFF, t.textFaint & 0x00FFFFFF);
        assertEquals(Math.round(0.35f * 255), t.textFaint >>> 24);
    }

    @Test
    void transparentBorderFallsBackToALightenedSurfaceHairline() {
        style.setColor(ImGuiCol.Border, 0.50f, 0.50f, 0.50f, 0f);
        MortarTheme t = MortarTheme.capture();
        int expected = Argb.withAlpha(Argb.shade(t.surface, 0.25f), 0.5f);
        assertEquals(expected, t.border);
        assertNotEquals(0, t.border >>> 24, "the fallback hairline is visible");
    }

    @Test
    void borderFallbackThresholdIsFourPercentAlpha() {
        style.setColor(ImGuiCol.Border, 0.50f, 0.50f, 0.50f, 0.039f);
        assertEquals(Argb.withAlpha(Argb.shade(MortarTheme.capture().surface, 0.25f), 0.5f),
                MortarTheme.capture().border);

        style.setColor(ImGuiCol.Border, 0.50f, 0.50f, 0.50f, 0.05f);
        assertEquals(Argb.of(0.50f, 0.50f, 0.50f, 0.05f), MortarTheme.capture().border,
                "a faint but real border is used as authored");
    }

    @Test
    void onAccentPicksTheMoreReadableTextColour() {
        style.setColor(ImGuiCol.HeaderActive, 0.10f, 0.10f, 0.35f, 1f);
        assertEquals(MortarTheme.ON_ACCENT_LIGHT, MortarTheme.capture().onAccent, "dark accent: white text");

        style.setColor(ImGuiCol.HeaderActive, 0.95f, 0.85f, 0.40f, 1f);
        assertEquals(MortarTheme.ON_ACCENT_DARK, MortarTheme.capture().onAccent, "light accent: near-black text");
    }

    @Test
    void onDangerIsReadableOnTheDangerFill() {
        MortarTheme t = MortarTheme.capture();
        assertEquals(MortarTheme.onAccentFor(t.danger), t.onDanger);
        int other = t.onDanger == MortarTheme.ON_ACCENT_LIGHT ? MortarTheme.ON_ACCENT_DARK : MortarTheme.ON_ACCENT_LIGHT;
        assertTrue(Argb.contrastRatio(t.danger, t.onDanger) >= Argb.contrastRatio(t.danger, other));
    }

    @Test
    void semanticTonesFollowTheWindowBackgroundBrightness() {
        MortarTheme dark = MortarTheme.capture();
        assertEquals(Argb.of(1.00f, 0.40f, 0.40f, 1f), dark.error, "dark background: bright error tone");
        assertEquals(Argb.of(0.40f, 0.90f, 0.40f, 1f), dark.success);

        style.setColor(ImGuiCol.WindowBg, 0.95f, 0.95f, 0.95f, 1f);
        MortarTheme light = MortarTheme.capture();
        assertEquals(Argb.of(0.72f, 0.10f, 0.10f, 1f), light.error, "light background: deep error tone");
        assertEquals(Argb.of(0.62f, 0.38f, 0.00f, 1f), light.warning);
        assertEquals(0xFF, light.danger >>> 24, "semantic tones are opaque");
    }
}
