package com.openmason.main.systems.themes.utils;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.type.ImFloat;

/**
 * Shared X/Y/Z transform-field widget — colored axis pill + drag float per axis.
 *
 * <p>Single responsibility: present a three-axis numeric input in the
 * Open Mason house style (red X, green Y, blue Z). Used by both the viewport
 * slideout and the rigging-pane inspector so transform UIs stay consistent.
 */
public final class TransformGroupWidget {

    private static final float AXIS_PILL_WIDTH = 18.0f;
    private static final float AXIS_PILL_SPACING = 4.0f;

    private TransformGroupWidget() {
    }

    /**
     * Render a labeled three-axis transform group (label + three stacked axis rows).
     *
     * @param groupLabel dimmed section label drawn above the axis rows
     * @param idScope    unique imgui id stem for this group's drag-float widgets
     * @param x          X-axis value
     * @param y          Y-axis value
     * @param z          Z-axis value
     * @param speed      drag-float sensitivity
     * @param format     printf-style format string for value display
     * @return {@code true} if any axis was edited this frame
     */
    public static boolean render(String groupLabel, String idScope,
                                 ImFloat x, ImFloat y, ImFloat z,
                                 float speed, String format) {
        label(groupLabel);
        boolean changed = false;
        changed |= renderAxisField("X", idScope + "x", x, speed, format, 0.85f, 0.25f, 0.25f);
        changed |= renderAxisField("Y", idScope + "y", y, speed, format, 0.25f, 0.72f, 0.25f);
        changed |= renderAxisField("Z", idScope + "z", z, speed, format, 0.25f, 0.45f, 0.90f);
        return changed;
    }

    /**
     * Variant operating on a primitive {@code float[3]} buffer.
     */
    public static boolean render(String groupLabel, String idScope,
                                 float[] xyz, float speed, String format) {
        ImFloat fx = new ImFloat(xyz[0]);
        ImFloat fy = new ImFloat(xyz[1]);
        ImFloat fz = new ImFloat(xyz[2]);
        boolean changed = render(groupLabel, idScope, fx, fy, fz, speed, format);
        if (changed) {
            xyz[0] = fx.get();
            xyz[1] = fy.get();
            xyz[2] = fz.get();
        }
        return changed;
    }

    /** Dimmed group label using the theme's TextDisabled color. */
    public static void label(String text) {
        ImVec4 col = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, col.x, col.y, col.z, col.w);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.spacing();
    }

    /** Width of the colored axis pill, for callers computing cell layouts. */
    public static float axisPillWidth() {
        return AXIS_PILL_WIDTH;
    }

    /** Spacing between the axis pill and its drag field. */
    public static float axisPillSpacing() {
        return AXIS_PILL_SPACING;
    }

    /**
     * Render one axis cell (colored pill + drag float) with an explicit field
     * width, so callers can lay several cells on a single line. The axis color
     * follows the house style: red X, green Y, blue Z.
     *
     * @return {@code true} if the value was edited this frame
     */
    public static boolean renderAxisCell(String axisLabel, String id, ImFloat value,
                                         float speed, String format, float fieldWidth) {
        float[] rgb = axisColor(axisLabel);
        return renderAxisField(axisLabel, id, value, speed, format,
                rgb[0], rgb[1], rgb[2], fieldWidth);
    }

    private static float[] axisColor(String axisLabel) {
        return switch (axisLabel) {
            case "Y" -> new float[]{0.25f, 0.72f, 0.25f};
            case "Z" -> new float[]{0.25f, 0.45f, 0.90f};
            default -> new float[]{0.85f, 0.25f, 0.25f};
        };
    }

    /** Render a single axis row: colored pill + drag float filling the remaining width. */
    private static boolean renderAxisField(String axisLabel, String id, ImFloat value,
                                           float speed, String format,
                                           float colorR, float colorG, float colorB) {
        float fieldWidth = ImGui.getContentRegionAvailX() - AXIS_PILL_WIDTH - AXIS_PILL_SPACING;
        return renderAxisField(axisLabel, id, value, speed, format,
                colorR, colorG, colorB, fieldWidth);
    }

    /** Render a single axis cell: colored pill + drag float of {@code fieldWidth}. */
    private static boolean renderAxisField(String axisLabel, String id, ImFloat value,
                                           float speed, String format,
                                           float colorR, float colorG, float colorB,
                                           float fieldWidth) {
        float pillHeight = ImGui.getFrameHeight();

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursor = ImGui.getCursorScreenPos();

        int pillColor = ImGui.colorConvertFloat4ToU32(colorR, colorG, colorB, 0.25f);
        int textColor = ImGui.colorConvertFloat4ToU32(colorR, colorG, colorB, 1.0f);
        drawList.addRectFilled(cursor.x, cursor.y,
                cursor.x + AXIS_PILL_WIDTH, cursor.y + pillHeight, pillColor, 3.0f);

        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, axisLabel);
        float textX = cursor.x + (AXIS_PILL_WIDTH - textSize.x) * 0.5f;
        float textY = cursor.y + (pillHeight - textSize.y) * 0.5f;
        drawList.addText(textX, textY, textColor, axisLabel);

        ImGui.dummy(AXIS_PILL_WIDTH, pillHeight);
        ImGui.sameLine(0, AXIS_PILL_SPACING);

        ImGui.pushItemWidth(fieldWidth);
        boolean changed = ImGui.dragFloat("##axis_" + id, value.getData(), speed, 0, 0, format);
        ImGui.popItemWidth();
        return changed;
    }
}
