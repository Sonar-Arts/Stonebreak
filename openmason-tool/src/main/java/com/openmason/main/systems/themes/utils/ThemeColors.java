package com.openmason.main.systems.themes.utils;

import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

import java.util.EnumMap;
import java.util.Map;

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
     * Semantic hues no ImGui style slot carries. The active theme supplies them
     * ({@code ThemeDefinition#getSemanticColors}, installed by
     * {@link #useSemanticColors}); a theme that omits one — or a scope that
     * repaints the window background across the light/dark line (the texture
     * editor's fixed dark panels under the Light theme) — falls back to the
     * tone's variant for a dark or light window background.
     *
     * <p>{@link #ERROR}, {@link #WARNING} and {@link #SUCCESS} are foreground
     * (text/icon) colors; {@link #DANGER} is the fill of a destructive button.</p>
     */
    public enum Tone {
        WARNING(new float[]{1.00f, 0.75f, 0.30f}, new float[]{0.62f, 0.38f, 0.00f}),
        SUCCESS(new float[]{0.40f, 0.90f, 0.40f}, new float[]{0.08f, 0.48f, 0.14f}),
        ERROR(new float[]{1.00f, 0.40f, 0.40f}, new float[]{0.72f, 0.10f, 0.10f}),
        DANGER(new float[]{0.66f, 0.22f, 0.22f}, new float[]{0.78f, 0.20f, 0.20f});

        private final float[] onDark;
        private final float[] onLight;

        Tone(float[] onDark, float[] onLight) {
            this.onDark = onDark;
            this.onLight = onLight;
        }

        private float[] rgb() {
            return resolve(isLightTheme());
        }

        /** This tone's {@code {r, g, b}} on a light ({@code true}) or dark live background. */
        float[] resolve(boolean lightBackground) {
            float[] themed = themeTones.get(this);
            if (themed != null && lightBackground == themeTonesForLight) {
                return themed;
            }
            return lightBackground ? onLight : onDark;
        }

        /** This tone for the active theme as an opaque Skija ARGB int (for {@code MortarTheme}). */
        public int argb() {
            float[] rgb = rgb();
            return Argb.of(rgb[0], rgb[1], rgb[2], 1.0f);
        }
    }

    /** Hover lift toward white for opaque filled buttons; press lifts further. */
    private static final float FILL_HOVER_SHADE = 0.12f;
    private static final float FILL_PRESS_SHADE = 0.20f;
    /** Rest/hover/press alphas of the subtle destructive tint used by list-row Remove buttons. */
    private static final float[] DANGER_SOFT_ALPHAS = {0.45f, 0.65f, 0.80f};
    /** Rest/hover/press alphas of an accent-tinted toggle that is on (hover sits between rest and press). */
    private static final float[] TOGGLE_ON_ALPHAS = {0.60f, 0.78f, 0.90f};
    /** Rest/hover/press alphas of a soft accent button (accent-colored text on a faint accent wash). */
    private static final float[] ACCENT_SOFT_ALPHAS = {0.20f, 0.40f, 0.55f};

    /** Tones supplied by the active theme; empty until a theme is applied. */
    private static volatile Map<Tone, float[]> themeTones = new EnumMap<>(Tone.class);
    /** Whether {@link #themeTones} were designed for a light window background. */
    private static volatile boolean themeTonesForLight;

    private ThemeColors() {
    }

    /**
     * Install the active theme's semantic colors (keyed by {@link Tone} name,
     * opaque {@code {r, g, b}}). Unknown keys and malformed entries are ignored;
     * tones the theme omits keep their dark/light fallback. Called by
     * {@code StyleApplicator} whenever a theme is applied.
     *
     * @param forLightBackground whether the theme's own window background is
     *                           light; the tones apply only while the live
     *                           background is on the same side
     */
    public static void useSemanticColors(Map<String, float[]> semantic, boolean forLightBackground) {
        EnumMap<Tone, float[]> resolved = new EnumMap<>(Tone.class);
        if (semantic != null) {
            for (Map.Entry<String, float[]> e : semantic.entrySet()) {
                float[] rgb = e.getValue();
                if (rgb == null || rgb.length < 3) {
                    continue;
                }
                for (Tone tone : Tone.values()) {
                    if (tone.name().equalsIgnoreCase(e.getKey())) {
                        resolved.put(tone, new float[]{rgb[0], rgb[1], rgb[2]});
                    }
                }
            }
        }
        themeTonesForLight = forLightBackground;
        themeTones = resolved;
    }

    /** The active theme's {@code {r, g, b}} for {@code tone}, or null when it falls back (tests). */
    static float[] themeTone(Tone tone) {
        return themeTones.get(tone);
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
        pushArgb(target, MortarTheme.onAccentFor(Argb.withAlpha(ImGui.getStyle().getColor(ImGuiCol.HeaderActive), 1.0f)));
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

    /**
     * Push the three button colors of a subtle destructive tint (list-row
     * "Remove"). Pops: {@code ImGui.popStyleColor(3)}.
     */
    public static void pushDangerSoftButton() {
        float[] rgb = Tone.DANGER.rgb();
        ImGui.pushStyleColor(ImGuiCol.Button, rgb[0], rgb[1], rgb[2], DANGER_SOFT_ALPHAS[0]);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, rgb[0], rgb[1], rgb[2], DANGER_SOFT_ALPHAS[1]);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, rgb[0], rgb[1], rgb[2], DANGER_SOFT_ALPHAS[2]);
    }

    /**
     * Push an opaque destructive button: {@link Tone#DANGER} fill, lifted on
     * hover/press, with readable text. Pops: {@code ImGui.popStyleColor(4)}.
     */
    public static void pushDangerButton() {
        pushFilledButton(Tone.DANGER.argb());
    }

    /**
     * Push an opaque confirm button in {@link Tone#SUCCESS} (e.g. "Accept" of a
     * live preview), with readable text. Pops: {@code ImGui.popStyleColor(4)}.
     */
    public static void pushSuccessButton() {
        pushFilledButton(Tone.SUCCESS.argb());
    }

    /**
     * Push {@code target} as {@code tone} shaded toward white ({@code factor>0})
     * or black ({@code factor<0}), opaque.
     */
    public static void pushShaded(int target, Tone tone, float factor) {
        pushArgb(target, Argb.shade(tone.argb(), factor));
    }

    /**
     * Push an opaque primary (accent) button: theme accent fill, lifted on
     * hover/press, with {@link MortarTheme#onAccent} text. Pops:
     * {@code ImGui.popStyleColor(4)}.
     */
    public static void pushAccentButton() {
        pushFilledButton(Argb.withAlpha(ImGui.getStyle().getColor(ImGuiCol.HeaderActive), 1.0f));
    }

    /**
     * Push the three button colors of an accent-tinted toggle that is
     * <em>on</em> (active tool, selected mode). Pops: {@code ImGui.popStyleColor(3)}.
     */
    public static void pushToggleOn() {
        ImVec4 a = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, a.x, a.y, a.z, TOGGLE_ON_ALPHAS[0]);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, a.x, a.y, a.z, TOGGLE_ON_ALPHAS[1]);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, a.x, a.y, a.z, TOGGLE_ON_ALPHAS[2]);
    }

    /**
     * Push a soft accent button: accent-colored text on a faint accent wash
     * (secondary "+ Add" actions). Pops: {@code ImGui.popStyleColor(4)}.
     */
    public static void pushAccentSoftButton() {
        ImVec4 a = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, a.x, a.y, a.z, ACCENT_SOFT_ALPHAS[0]);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, a.x, a.y, a.z, ACCENT_SOFT_ALPHAS[1]);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, a.x, a.y, a.z, ACCENT_SOFT_ALPHAS[2]);
        ImGui.pushStyleColor(ImGuiCol.Text, a.x, a.y, a.z, 1.0f);
    }

    /** Live color of {@code col} (its own alpha), packed for draw lists. */
    public static int u32(int col) {
        return ImGui.getColorU32(col);
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

    private static void pushFilledButton(int fill) {
        pushArgb(ImGuiCol.Button, fill);
        pushArgb(ImGuiCol.ButtonHovered, Argb.shade(fill, FILL_HOVER_SHADE));
        pushArgb(ImGuiCol.ButtonActive, Argb.shade(fill, FILL_PRESS_SHADE));
        pushArgb(ImGuiCol.Text, MortarTheme.onAccentFor(fill));
    }

    /** Push {@code target} as a Skija ARGB color (channel order converted for ImGui). */
    public static void pushArgb(int target, int argb) {
        ImGui.pushStyleColor(target, ((argb >>> 16) & 0xFF) / 255f, ((argb >>> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
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
