package com.openmason.main.systems.menus.panes.propertyPane.inspector;

import com.openmason.main.systems.themes.utils.TransformGroupWidget;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;

/**
 * Unity/Unreal-style two-column inspector rows: a dimmed label in a fixed-width
 * left column, the editable field(s) filling the remaining width. All rows in a
 * panel share one column width so labels and fields align vertically.
 */
public final class InspectorRow {

    private static final float MIN_LABEL_WIDTH = 70f;
    private static final float MAX_LABEL_WIDTH = 160f;
    private static final float LABEL_FRACTION = 0.40f;
    private static final float CELL_SPACING = 4f;

    private InspectorRow() {
    }

    /** The label column width for the current content region. */
    public static float labelColumnWidth() {
        float avail = ImGui.getContentRegionAvailX();
        return Math.max(MIN_LABEL_WIDTH, Math.min(MAX_LABEL_WIDTH, avail * LABEL_FRACTION));
    }

    /**
     * Draw the row label in the left column and place the cursor at the field
     * column. Callers follow with their field widget(s); use
     * {@code ImGui.setNextItemWidth(-1)} for a single full-width field.
     */
    public static void label(String text) {
        float rowStartX = ImGui.getCursorPosX();
        float columnWidth = labelColumnWidth();

        ImGui.alignTextToFramePadding();
        ImVec4 dim = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, dim.x, dim.y, dim.z, dim.w);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();

        ImGui.sameLine();
        ImGui.setCursorPosX(rowStartX + columnWidth);
    }

    /** A label | drag-float row. Returns true when the value was edited. */
    public static boolean dragFloatField(String rowLabel, String id, ImFloat value,
                                         float speed, String format) {
        label(rowLabel);
        ImGui.setNextItemWidth(-1);
        return ImGui.dragFloat("##" + id, value.getData(), speed, 0, 0, format);
    }

    /** A label | checkbox row. Returns true when toggled. */
    public static boolean checkboxField(String rowLabel, String id, ImBoolean value) {
        label(rowLabel);
        return ImGui.checkbox("##" + id, value);
    }

    /**
     * A label | X|Y|Z row: the field column split into three colored-pill
     * drag-float cells (single-line, Unity-style). Returns true when any axis
     * was edited this frame.
     */
    public static boolean vector3Row(String rowLabel, String idScope,
                                     ImFloat x, ImFloat y, ImFloat z,
                                     float speed, String format) {
        label(rowLabel);

        float avail = ImGui.getContentRegionAvailX();
        float cellWidth = (avail - CELL_SPACING * 2f) / 3f;
        float fieldWidth = Math.max(24f, cellWidth
                - TransformGroupWidget.axisPillWidth() - TransformGroupWidget.axisPillSpacing());

        boolean changed = TransformGroupWidget.renderAxisCell("X", idScope + "x", x, speed, format, fieldWidth);
        ImGui.sameLine(0, CELL_SPACING);
        changed |= TransformGroupWidget.renderAxisCell("Y", idScope + "y", y, speed, format, fieldWidth);
        ImGui.sameLine(0, CELL_SPACING);
        changed |= TransformGroupWidget.renderAxisCell("Z", idScope + "z", z, speed, format, fieldWidth);
        return changed;
    }
}
