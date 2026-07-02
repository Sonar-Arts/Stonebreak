package com.openmason.main.systems.menus.animationEditor.panels;

import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * ImGui-space colors for the animation editor's custom-drawn widgets
 * (timeline, ruler, marquee). All values come from the live ImGui style via
 * {@link ImGui#getColorU32(int)} so the editor recolors with the active theme
 * — the same style snapshot Mortar's theme captures, keeping the two visually
 * unified.
 *
 * <p><b>Color space warning:</b> these are ImGui/ImDrawList u32 colors
 * (ABGR). Never mix them with {@code MortarTheme}/{@code Argb} ints — those
 * are Skija ARGB and the channels will swap.
 */
final class AnimEditorTheme {

    private AnimEditorTheme() {}

    static int trackBg() { return ImGui.getColorU32(ImGuiCol.FrameBg); }
    static int trackAxis() { return ImGui.getColorU32(ImGuiCol.Separator); }
    static int rulerBg() { return ImGui.getColorU32(ImGuiCol.MenuBarBg); }
    static int rulerTick() { return ImGui.getColorU32(ImGuiCol.Border); }
    static int rulerText() { return ImGui.getColorU32(ImGuiCol.TextDisabled); }
    static int labelText() { return ImGui.getColorU32(ImGuiCol.Text); }
    static int labelTextDim() { return ImGui.getColorU32(ImGuiCol.TextDisabled); }

    /** Playhead line — plot-hover hue reads as a warm accent in every built-in theme. */
    static int playhead() { return ImGui.getColorU32(ImGuiCol.PlotLinesHovered); }

    /** Keyframe diamonds take the theme accent. */
    static int keyframe() { return ImGui.getColorU32(ImGuiCol.CheckMark); }

    /** Selected keyframes: histogram hue (yellow-ish) contrasts with the accent. */
    static int keyframeSelected() { return ImGui.getColorU32(ImGuiCol.PlotHistogram); }

    static int ghost() { return withAlpha(keyframe(), 0.45f); }
    static int marqueeFill() { return withAlpha(ImGui.getColorU32(ImGuiCol.CheckMark), 0.15f); }
    static int marqueeBorder() { return withAlpha(ImGui.getColorU32(ImGuiCol.CheckMark), 0.8f); }

    /** Replace the alpha channel of an ImGui-space (ABGR) u32 color. */
    static int withAlpha(int abgr, float alpha) {
        int a = (int) (Math.min(Math.max(alpha, 0f), 1f) * 255f);
        return (abgr & 0x00FFFFFF) | (a << 24);
    }
}
