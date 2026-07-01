package com.openmason.main.systems.menus.textureCreator.utils;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

/**
 * Printf-safe wrappers around ImGui text widgets.
 *
 * <p>imgui-java's {@code ImGui.text}/{@code ImGui.textDisabled} forward the
 * string to Dear ImGui's {@code ImGui::Text(fmt, ...)} as the <em>format</em>
 * string. Any literal {@code '%'} in the text (common in this editor: zoom and
 * alpha percentages) is then read as a printf conversion directive, causing the
 * renderer to read past the string and spill adjacent native memory — other
 * window/child IDs — into the label. The symptom is garbage text after a number,
 * e.g. {@code "Zoom: 3200%torMenuBar_73846782..."}.
 *
 * <p>{@code TextUnformatted} takes no format string, so these render the text
 * verbatim regardless of its contents. Use these for any status/label text that
 * may contain a '%'.
 */
public final class SafeText {

    private SafeText() {
    }

    /** Draws {@code text} verbatim (equivalent to a format-safe {@code ImGui.text}). */
    public static void text(String text) {
        ImGui.textUnformatted(text);
    }

    /** Draws {@code text} verbatim in the disabled color (format-safe {@code ImGui.textDisabled}). */
    public static void textDisabled(String text) {
        ImVec4 disabled = ImGui.getStyleColorVec4(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, disabled.x, disabled.y, disabled.z, disabled.w);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }
}
