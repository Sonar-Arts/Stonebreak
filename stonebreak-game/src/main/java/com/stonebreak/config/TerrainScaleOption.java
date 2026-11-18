package com.stonebreak.config;

import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;

import java.util.Locale;

/**
 * How the game names a world's terrain scale ({@link TerrainScalePreset}, chosen at world creation, saved in the
 * world's {@code WorldData}): the label the UI shows ({@code 1:4}, {@code 1:2}) and the spellings dev hooks and
 * commands accept.
 */
public final class TerrainScaleOption {

    private TerrainScaleOption() {
    }

    /** The preset new worlds get unless the player picks another (1:4 until 1:2 has been looked at). */
    public static final TerrainScalePreset DEFAULT = TerrainScalePreset.ONE_TO_FOUR;

    /** {@code "1:4"} / {@code "1:2"}. */
    public static String label(TerrainScalePreset scale) {
        TerrainScalePreset s = scale == null ? DEFAULT : scale;
        return switch (s) {
            case ONE_TO_FOUR -> "1:4";
            case ONE_TO_TWO -> "1:2";
        };
    }

    /** A longer description for menus and logs. */
    public static String describe(TerrainScalePreset scale) {
        TerrainScalePreset s = scale == null ? DEFAULT : scale;
        return label(s) + " (" + Math.round(s.horizontalMetresPerBlock()) + " m blocks)";
    }

    /**
     * {@code 1:4}, {@code 1:2}, {@code 4}, {@code 2}, or a preset name ({@code ONE_TO_TWO}); null for anything else
     * (including null/blank).
     */
    public static TerrainScalePreset parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            if (p.name().equals(t) || label(p).equals(t) || label(p).substring(2).equals(t)) {
                return p;
            }
        }
        return null;
    }

    /** The preset after {@code scale}, wrapping (for a single cycling control). */
    public static TerrainScalePreset next(TerrainScalePreset scale) {
        TerrainScalePreset[] all = TerrainScalePreset.values();
        TerrainScalePreset s = scale == null ? DEFAULT : scale;
        return all[(s.ordinal() + 1) % all.length];
    }
}
