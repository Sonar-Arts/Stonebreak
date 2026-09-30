package com.openmason.main.systems.themes.utils;

import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

/**
 * Theme-derived colors for plain ImGui call sites, read from the <em>live</em>
 * style so they follow the active theme (Dark, Light, …) instead of baking in
 * dark-theme literals. The Skija/Mortar counterpart is
 * {@link com.openmason.main.systems.mortar.theme.MortarTheme}.
 *
 * <p>{@code push*} methods push exactly one style color; pop with
 * {@code ImGui.popStyleColor()} as usual. {@code *U32} methods return packed
 * colors for draw lists.</p>
 */
public final class ThemeColors {

    /**
     * Status hues no ImGui style slot carries. Each has a variant for dark and
     * for light window backgrounds, picked from the live theme.
     */
    public enum Tone {
        WARNING(new float[]{1.00f, 0.75f, 0.30f}, new float[]{0.62f, 0.38f, 0.00f}),
        SUCCESS(new float[]{0.40f, 0.90f, 0.40f}, new float[]{0.08f, 0.48f, 0.14f}),
        ERROR(new float[]{1.00f, 0.40f, 0.40f}, new float[]{0.72f, 0.10f, 0.10f});

        private final float[] onDark;
        private final float[] onLight;

        Tone(float[] onDark, float[] onLight) {
            this.onDark = onDark;
            this.onLight = onLight;
        }

        private float[] rgb() {
            return isLightTheme() ? onLight : onDark;
        }
    }

    private ThemeColors() {
    }

    /** Push {@code target} as the live color of {@code source}. */
    public static void push(int target, int source) {
        ImVec4 c = ImGui.getStyle().getColor(source);
        ImGui.pushStyleColor(target, c.x, c.y, c.z, c.w);
    }

    /** Push {@code target} as the live color of {@code source} with its alpha scaled. */
    public static void pushScaledAlpha(int target, int source, float alphaScale) {
        ImVec4 c = ImGui.getStyle().getColor(source);
        ImGui.pushStyleColor(target, c.x, c.y, c.z, c.w * alphaScale);
    }

    /**
     * Push {@code target} as a blend from the live color of {@code from} to the
     * live color of {@code to} by {@code t}, fully opaque.
     */
    public static void pushMix(int target, int from, int to, float t) {
        ImVec4 a = ImGui.getStyle().getColor(from);
        ImVec4 b = ImGui.getStyle().getColor(to);
        ImGui.pushStyleColor(target, lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t), 1.0f);
    }

    /**
     * Push {@code target} as the text color for an opaque accent fill
     * ({@link ImGuiCol#HeaderActive}): white or near-black by WCAG contrast,
     * the same rule as {@link MortarTheme#onAccent}.
     */
    public static void pushOnAccent(int target) {
        int argb = MortarTheme.onAccentFor(Argb.withAlpha(ImGui.getStyle().getColor(ImGuiCol.HeaderActive), 1.0f));
        ImGui.pushStyleColor(target, ((argb >>> 16) & 0xFF) / 255f, ((argb >>> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, 1.0f);
    }

    /** Push {@code target} as the theme-appropriate variant of {@code tone}. */
    public static void push(int target, Tone tone) {
        float[] rgb = tone.rgb();
        ImGui.pushStyleColor(target, rgb[0], rgb[1], rgb[2], 1.0f);
    }

    /**
     * Push {@code target} as a tone-tinted surface: the frame background
     * blended {@code t} of the way toward {@code tone}.
     */
    public static void pushSurface(int target, Tone tone, float t) {
        float[] c = tinted(ImGuiCol.FrameBg, tone, t);
        ImGui.pushStyleColor(target, c[0], c[1], c[2], 1.0f);
    }

    /** Live color of {@code col} with an explicit alpha, packed for draw lists. */
    public static int u32(int col, float alpha) {
        ImVec4 c = ImGui.getStyle().getColor(col);
        return ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, alpha);
    }

    /** Theme-appropriate {@code tone} with an explicit alpha, packed for draw lists. */
    public static int u32(Tone tone, float alpha) {
        float[] rgb = tone.rgb();
        return ImGui.colorConvertFloat4ToU32(rgb[0], rgb[1], rgb[2], alpha);
    }

    /** The window background blended {@code t} toward {@code tone}, packed for draw lists. */
    public static int surfaceU32(Tone tone, float t, float alpha) {
        float[] c = tinted(ImGuiCol.WindowBg, tone, t);
        return ImGui.colorConvertFloat4ToU32(c[0], c[1], c[2], alpha);
    }

    /** True when the active theme's window background is light. */
    public static boolean isLightTheme() {
        ImVec4 bg = ImGui.getStyle().getColor(ImGuiCol.WindowBg);
        return luminance(bg.x, bg.y, bg.z) > 0.5f;
    }

    /** Perceptual (Rec. 709 weights, gamma-encoded) brightness in [0,1]. */
    static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float[] tinted(int baseCol, Tone tone, float t) {
        ImVec4 bg = ImGui.getStyle().getColor(baseCol);
        float[] rgb = tone.rgb();
        return new float[]{lerp(bg.x, rgb[0], t), lerp(bg.y, rgb[1], t), lerp(bg.z, rgb[2], t)};
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
