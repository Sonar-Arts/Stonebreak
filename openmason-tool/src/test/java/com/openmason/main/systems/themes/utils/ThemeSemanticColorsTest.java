package com.openmason.main.systems.themes.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.registry.ColorPalette;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Semantic status colors (#274): carried by {@link ThemeDefinition}, installed
 * into {@link ThemeColors} when a theme is applied, and resolved per tone.
 */
class ThemeSemanticColorsTest {

    @AfterEach
    void clearThemeTones() {
        ThemeColors.useSemanticColors(null, false);
    }

    @Test
    void builtInThemesDefineEveryTone() {
        for (ThemeDefinition theme : List.of(ColorPalette.createMasonSpectrumDarkTheme(),
                ColorPalette.createMasonSpectrumLightTheme())) {
            for (Tone tone : Tone.values()) {
                assertNotNull(theme.getSemanticColors().get(tone.name()),
                        theme.getId() + " is missing " + tone);
            }
        }
    }

    @Test
    void appliedThemeTonesWinOnTheirOwnBackground() {
        ThemeColors.useSemanticColors(Map.of("ERROR", new float[]{1f, 0f, 0f}), true);

        assertArrayEquals(new float[]{1f, 0f, 0f}, Tone.ERROR.resolve(true));
    }

    @Test
    void acrossTheLightDarkLineTheFallbackVariantWins() {
        // e.g. the texture editor's fixed dark panels under a light theme
        ThemeColors.useSemanticColors(Map.of("ERROR", new float[]{0.72f, 0.10f, 0.10f}), true);

        assertArrayEquals(new float[]{1.00f, 0.40f, 0.40f}, Tone.ERROR.resolve(false));
    }

    @Test
    void keysMatchCaseInsensitively() {
        ThemeColors.useSemanticColors(Map.of("danger", new float[]{0.5f, 0.1f, 0.1f}), false);

        assertArrayEquals(new float[]{0.5f, 0.1f, 0.1f}, ThemeColors.themeTone(Tone.DANGER));
    }

    @Test
    void malformedAndUnknownEntriesFallBack() {
        Map<String, float[]> semantic = new HashMap<>();
        semantic.put("WARNING", new float[]{1f, 1f});
        semantic.put("SUCCESS", null);
        semantic.put("NOT_A_TONE", new float[]{1f, 1f, 1f});
        ThemeColors.useSemanticColors(semantic, false);

        for (Tone tone : Tone.values()) {
            assertNull(ThemeColors.themeTone(tone), tone + " should fall back");
        }
    }

    @Test
    void applyingAnotherThemeReplacesPreviousTones() {
        ThemeColors.useSemanticColors(Map.of("ERROR", new float[]{1f, 0f, 0f}), false);
        ThemeColors.useSemanticColors(Map.of("WARNING", new float[]{1f, 0.5f, 0f}), false);

        assertNull(ThemeColors.themeTone(Tone.ERROR));
        assertNotNull(ThemeColors.themeTone(Tone.WARNING));
    }

    @Test
    void copyKeepsSemanticColors() {
        ThemeDefinition copy = ColorPalette.createMasonSpectrumDarkTheme().copy();

        assertEquals(Tone.values().length, copy.getSemanticColors().size());
    }

    @Test
    void semanticColorsSurviveJsonRoundTrip() throws Exception {
        ThemeDefinition theme = new ThemeDefinition("t", "T", "", ThemeDefinition.ThemeType.USER_CUSTOM);
        theme.setSemanticColor("DANGER", 0.7f, 0.2f, 0.2f);

        ObjectMapper mapper = new ObjectMapper();
        ThemeDefinition read = mapper.readValue(mapper.writeValueAsString(theme), ThemeDefinition.class);

        assertArrayEquals(new float[]{0.7f, 0.2f, 0.2f}, read.getSemanticColors().get("DANGER"));
    }
}
