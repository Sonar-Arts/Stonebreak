package com.openmason.main.systems.mortar.theme;

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
 * <p>All tokens are Skija <strong>ARGB</strong> ints (see {@link Argb}). Build
 * one per frame with {@link #capture()}; it is a handful of array reads.</p>
 */
public final class MortarTheme {

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
    /** Primary text. */
    public final int text;
    /** Secondary/label text. */
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

    private MortarTheme(ImGuiStyle style) {
        ImVec4 windowBg = style.getColor(ImGuiCol.WindowBg);
        ImVec4 frameBg = style.getColor(ImGuiCol.FrameBg);
        ImVec4 frameHover = style.getColor(ImGuiCol.FrameBgHovered);
        ImVec4 accentCol = style.getColor(ImGuiCol.HeaderActive);
        ImVec4 textCol = style.getColor(ImGuiCol.Text);
        ImVec4 borderCol = style.getColor(ImGuiCol.Border);
        ImVec4 sepCol = style.getColor(ImGuiCol.Separator);

        this.background = Argb.of(windowBg);
        this.surface = Argb.of(frameBg);
        this.surfaceHover = Argb.of(frameHover);
        this.accent = Argb.withAlpha(accentCol, 1.0f);
        this.accentHover = Argb.shade(this.accent, 0.12f);
        this.text = Argb.of(textCol);
        this.textDim = Argb.withAlpha(textCol, 0.62f);
        this.textFaint = Argb.withAlpha(textCol, 0.35f);
        this.border = borderColorOrDerived(borderCol, frameBg);
        this.borderStrong = Argb.withAlpha(accentCol, 0.85f);
        this.separator = Argb.of(sepCol);
        this.shadow = 0x44000000;
        this.badgeBg = Argb.shade(this.surface, 0.10f);
    }

    /** Resolve tokens from the current ImGui style. */
    public static MortarTheme capture() {
        return new MortarTheme(ImGui.getStyle());
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
