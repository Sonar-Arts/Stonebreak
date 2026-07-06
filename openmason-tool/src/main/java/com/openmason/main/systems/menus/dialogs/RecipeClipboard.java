package com.openmason.main.systems.menus.dialogs;

import java.util.List;

/**
 * App-lifetime clipboard for the SBO recipe editors. Holds two independent
 * slots:
 *
 * <ul>
 *   <li><b>ingredient</b> — one objectId, copied from a grid cell (Ctrl+C /
 *       context menu) and pasted into cells (Ctrl+V / Ctrl+Click).</li>
 *   <li><b>pattern</b> — a whole shaped recipe (3×3 working buffer + size +
 *       output count). Because the clipboard is static, a recipe copied in one
 *       SBO can be pasted into another SBO's editor.</li>
 * </ul>
 *
 * <p>Session-scoped; not the OS clipboard.</p>
 */
public final class RecipeClipboard {

    /** A copied shaped recipe: always a full 3×3 row-major slot buffer. */
    public record PatternClip(int width, int height, List<String> slots, int outputCount) {
        public PatternClip {
            if (slots == null || slots.size() != 9) {
                throw new IllegalArgumentException("pattern clip requires exactly 9 slots");
            }
            width = Math.clamp(width, 1, 3);
            height = Math.clamp(height, 1, 3);
            outputCount = Math.max(1, outputCount);
            slots = slots.stream().map(s -> s == null ? "" : s).toList();
        }
    }

    private static String ingredient;
    private static PatternClip pattern;

    private RecipeClipboard() {}

    public static void copyIngredient(String objectId) {
        ingredient = (objectId == null || objectId.isEmpty()) ? null : objectId;
    }

    /** The copied ingredient objectId, or null when empty. */
    public static String ingredient() {
        return ingredient;
    }

    public static boolean hasIngredient() {
        return ingredient != null;
    }

    public static void copyPattern(PatternClip clip) {
        pattern = clip;
    }

    /** The copied recipe pattern, or null when empty. */
    public static PatternClip pattern() {
        return pattern;
    }

    public static boolean hasPattern() {
        return pattern != null;
    }

    /** Test hook — reset both slots. */
    public static void clear() {
        ingredient = null;
        pattern = null;
    }
}
