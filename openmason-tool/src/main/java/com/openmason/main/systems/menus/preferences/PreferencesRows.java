package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

/**
 * Row layout shared by every Preferences page: one label column, one control
 * width, one heading idiom. Pages never position controls with their own
 * {@code sameLine(n)} magic numbers.
 */
final class PreferencesRows {

    /** Width of the label column (label text plus the right-aligned "(?)" hint). */
    static final float LABEL_COL = 260.0f;
    /** Width of every slider / combo / text-field control in a row. */
    static final float CONTROL_W = 240.0f;

    private PreferencesRows() {
    }

    // ---- Headings -----------------------------------------------------

    /** Major section heading (dim uppercase caption over a rule). */
    static void section(String title) {
        ThemedWidgets.sectionLabel(title);
    }

    /** Sub-group caption inside a section (plain dim text, no fake bold). */
    static void group(String title) {
        ImGui.spacing();
        ImGui.textDisabled(title);
        ImGui.spacing();
    }

    // ---- Rows ---------------------------------------------------------

    /**
     * Starts a row: writes the label in the label column (tooltip hint pinned to the
     * column's right edge) and leaves the cursor at the control column with
     * {@link #CONTROL_W} queued as the next item width.
     */
    static void beginRow(String label, String tooltip) {
        float x0 = ImGui.getCursorPosX();
        ImGui.alignTextToFramePadding();
        ImGui.text(label);
        if (tooltip != null) {
            float hintW = ImGui.calcTextSize("(?)").x;
            ImGui.sameLine(x0 + LABEL_COL - hintW - 8.0f);
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(tooltip);
            }
        }
        ImGui.sameLine(x0 + LABEL_COL);
        ImGui.setNextItemWidth(CONTROL_W);
    }

    static void slider(String label, String tooltip, ImFloat value, float min, float max, String format) {
        beginRow(label, tooltip);
        ImGui.sliderFloat("##" + label, value.getData(), min, max, format);
    }

    static void sliderInt(String label, String tooltip, ImInt value, int min, int max) {
        beginRow(label, tooltip);
        ImGui.sliderInt("##" + label, value.getData(), min, max);
    }

    static void combo(String label, String tooltip, String[] items, ImInt selected) {
        beginRow(label, tooltip);
        ImGui.combo("##" + label, selected, items);
    }

    static void inputInt(String label, String tooltip, ImInt value, int step) {
        beginRow(label, tooltip);
        ImGui.inputInt("##" + label, value, step);
    }

    static void text(String label, String tooltip, imgui.type.ImString value, int flags) {
        beginRow(label, tooltip);
        ImGui.inputText("##" + label, value, flags);
    }

    /** Checkbox aligned with the control column, label in the label column. */
    static void checkbox(String label, String tooltip, ImBoolean value) {
        beginRow(label, tooltip);
        ImGui.checkbox("##" + label, value);
    }

    /** Places the cursor in the control column (for a button under a row). */
    static void indentToControls() {
        ImGui.setCursorPosX(ImGui.getCursorPosX() + LABEL_COL);
    }

    /** Dim static text for a key-cap style read-only value. */
    static void keycap(String text, String id, float width) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGui.getColorU32(ImGuiCol.FrameBg));
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGui.getColorU32(ImGuiCol.FrameBg));
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGui.getColorU32(ImGuiCol.FrameBg));
        ImGui.button(text + "##" + id, width, 0);
        ImGui.popStyleColor(3);
    }
}
