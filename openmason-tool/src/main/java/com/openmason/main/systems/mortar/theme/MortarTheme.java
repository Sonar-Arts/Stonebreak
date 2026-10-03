package com.openmason.main.systems.mortar.theme;

import com.openmason.main.systems.themes.utils.ThemeColors;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

/**
 * An immutable snapshot of MortarUI's design tokens for one paint pass,
 * resolved from the <em>live</em> ImGui style ({@link ImGui#getStyle()}). The
 * live style is always fully populated and reflects the colors actually
 * applied to ImGui widgets, so MortarUI Skija parts and plain ImGui widgets
 * share one source of truth — switch the ImGui theme and both recolor together.
 *
 * <p>Color tokens are Skija <strong>ARGB</strong> ints (see {@link Argb}). Build
 * one per frame with {@link #capture()}; it is a handful of array reads.</p>
 *
 * <p>{@link #scale} is the UI density scale ImGui applies to its own text;
 * {@link com.openmason.main.systems.mortar.core.MortarRegion} applies it to the
 * whole Mortar canvas, so parts paint in logical px and text sizes come from
 * the {@link MortarType} ramp.</p>
 */
public final class MortarTheme {

    /** Light candidate for {@link #onAccent}. */
    public static final int ON_ACCENT_LIGHT = 0xFFFFFFFF;
    /** Dark candidate for {@link #onAccent}: near-black, not pure black. */
    public static final int ON_ACCENT_DARK = 0xFF15171B;

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 4f;

    /** Window/page backdrop. */
    public final int background;
    /** Raised surface (cards, buttons, nav rows at rest). */
    public final int surface;
    /** Surface under hover. */
    public final int surfaceHover;
    /** The single signature accent. */
    public final int accent;
    /** Accent under hover/press. */
    public final int accentHover;
    /**
     * Text/icon color drawn on an {@link #accent} fill (primary buttons,
     * selected tabs, accent badges): white or near-black, whichever reaches
     * the higher WCAG contrast on the accent.
     */
    public final int onAccent;
    /** Primary text. */
    public final int text;
    /** Secondary/label text — ImGui's {@code TextDisabled}, so both halves of the UI match. */
    public final int textDim;
    /** Tertiary/placeholder text. */
    public final int textFaint;
    /** Hairline border. */
    public final int border;
    /** Emphasised border (focus/selection). */
    public final int borderStrong;
    /** Separator line. */
    public final int separator;
    /** Drop-shadow color (semi-transparent black). */
    public final int shadow;
    /** Pill/badge background. */
    public final int badgeBg;
    /** Error foreground (validation messages, failed status). */
    public final int error;
    /** Warning foreground. */
    public final int warning;
    /** Success foreground. */
    public final int success;
    /** Destructive-action fill (Delete, Remove). */
    public final int danger;
    /** {@link #danger} under hover/press. */
    public final int dangerHover;
    /** Text/icon color on a {@link #danger} fill. */
    public final int onDanger;
    /** UI density scale (logical px → screen px), the same factor ImGui scales its text by. */
    public final float scale;

    private MortarTheme(ImGuiStyle style, float scale) {
        ImVec4 windowBg = style.getColor(ImGuiCol.WindowBg);
        ImVec4 frameBg = style.getColor(ImGuiCol.FrameBg);
        ImVec4 frameHover = style.getColor(ImGuiCol.FrameBgHovered);
        ImVec4 accentCol = style.getColor(ImGuiCol.HeaderActive);
        ImVec4 textCol = style.getColor(ImGuiCol.Text);
        ImVec4 textDisabledCol = style.getColor(ImGuiCol.TextDisabled);
        ImVec4 borderCol = style.getColor(ImGuiCol.Border);
        ImVec4 sepCol = style.getColor(ImGuiCol.Separator);

        this.background = Argb.of(windowBg);
        this.surface = Argb.of(frameBg);
        this.surfaceHover = Argb.of(frameHover);
        this.accent = Argb.withAlpha(accentCol, 1.0f);
        this.accentHover = Argb.shade(this.accent, 0.12f);
        this.onAccent = onAccentFor(this.accent);
        this.text = Argb.of(textCol);
        this.textDim = Argb.of(textDisabledCol);
        this.textFaint = Argb.withAlpha(textCol, 0.35f);
        this.border = borderColorOrDerived(borderCol, frameBg);
        this.borderStrong = Argb.withAlpha(accentCol, 0.85f);
        this.separator = Argb.of(sepCol);
        this.shadow = 0x44000000;
        this.badgeBg = Argb.shade(this.surface, 0.10f);
        this.error = ThemeColors.Tone.ERROR.argb();
        this.warning = ThemeColors.Tone.WARNING.argb();
        this.success = ThemeColors.Tone.SUCCESS.argb();
        this.danger = ThemeColors.Tone.DANGER.argb();
        this.dangerHover = Argb.shade(this.danger, 0.12f);
        this.onDanger = onAccentFor(this.danger);
        this.scale = scale;
    }

    /** White or near-black, whichever reads better on the opaque {@code accentArgb}. */
    public static int onAccentFor(int accentArgb) {
        return Argb.readableOn(accentArgb, ON_ACCENT_LIGHT, ON_ACCENT_DARK);
    }

    /** Resolve tokens from the current ImGui style. */
    public static MortarTheme capture() {
        return new MortarTheme(ImGui.getStyle(), currentScale());
    }

    /**
     * The scale ImGui currently renders text at relative to its atlas size:
     * density ({@code io.FontGlobalScale}, set by {@code DensityManager}) times
     * the style's main/DPI font scales. 1.0 at Normal density.
     */
    public static float currentScale() {
        ImGuiStyle style = ImGui.getStyle();
        return sanitizeScale(ImGui.getIO().getFontGlobalScale()
                * style.getFontScaleMain() * style.getFontScaleDpi());
    }

    /** Clamp a scale to a sane range; non-finite or non-positive values fall back to 1. */
    static float sanitizeScale(float scale) {
        if (!Float.isFinite(scale) || scale <= 0f) {
            return 1f;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /**
     * Some themes leave {@link ImGuiCol#Border} fully transparent. Fall back to
     * a faint lightening of the surface so MortarUI parts always have a visible
     * hairline.
     */
    private static int borderColorOrDerived(ImVec4 borderCol, ImVec4 surfaceCol) {
        if (borderCol.w < 0.04f) {
            return Argb.withAlpha(Argb.shade(Argb.of(surfaceCol), 0.25f), 0.5f);
        }
        return Argb.of(borderCol);
    }
}
