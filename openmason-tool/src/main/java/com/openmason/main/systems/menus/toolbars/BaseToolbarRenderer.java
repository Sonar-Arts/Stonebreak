package com.openmason.main.systems.menus.toolbars;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for toolbar renderers with Blender-style flat button aesthetics.
 * Buttons are transparent until hovered, with subtle rounded highlights.
 * Separators are thin painted vertical lines, not ImGui separators.
 */
public abstract class BaseToolbarRenderer {

    private static final float TOOLBAR_HEIGHT = 28.0f;
    private static final float BUTTON_ROUNDING = 3.0f;
    private static final float SEPARATOR_VERTICAL_INSET = 5.0f;

    /**
     * Push flat toolbar button styling.
     * Buttons are invisible until hovered, matching Blender's header bar aesthetic.
     * Call {@link #popFlatButtonStyle()} after rendering buttons.
     */
    protected void pushFlatButtonStyle() {
        ImVec4 hoverColor = ImGui.getStyle().getColor(ImGuiCol.HeaderHovered);
        ImVec4 activeColor = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);

        ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, hoverColor.x, hoverColor.y, hoverColor.z, 0.5f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, activeColor.x, activeColor.y, activeColor.z, 0.7f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, BUTTON_ROUNDING);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
    }

    /**
     * Remove flat button styling.
     */
    protected void popFlatButtonStyle() {
        ImGui.popStyleVar(2);
        ImGui.popStyleColor(3);
    }

    /**
     * Push highlighted flat button style (for active/toggled states).
     * Uses a subtle tinted background to indicate selection.
     * Call {@link #popHighlightedFlatButtonStyle()} after rendering the button.
     */
    protected void pushHighlightedFlatButtonStyle() {
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImVec4 hoverColor = ImGui.getStyle().getColor(ImGuiCol.HeaderHovered);

        ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.35f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, hoverColor.x, hoverColor.y, hoverColor.z, 0.6f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.8f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, BUTTON_ROUNDING);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
    }

    /**
     * Remove highlighted flat button styling.
     */
    protected void popHighlightedFlatButtonStyle() {
        ImGui.popStyleVar(2);
        ImGui.popStyleColor(3);
    }

    /**
     * Render a thin vertical line separator between button groups.
     * Painted directly onto the draw list for precise control, not using ImGui.separator().
     */
    protected void renderThinSeparator() {
        ImGui.sameLine(0.0f, 8.0f);

        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float lineX = cursorPos.x;
        float topY = cursorPos.y + SEPARATOR_VERTICAL_INSET;
        float bottomY = cursorPos.y + TOOLBAR_HEIGHT - SEPARATOR_VERTICAL_INSET;

        ImVec4 separatorColor = ImGui.getStyle().getColor(ImGuiCol.Border);
        int color = ImGui.colorConvertFloat4ToU32(
                separatorColor.x, separatorColor.y, separatorColor.z, 0.4f);

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addLine(lineX, topY, lineX, bottomY, color, 1.0f);

        // Advance cursor past the separator
        ImGui.dummy(2.0f, TOOLBAR_HEIGHT);
        ImGui.sameLine(0.0f, 8.0f);
    }

    /**
     * Render a flat toolbar button. Transparent until hovered.
     *
     * @return true if clicked
     */
    protected boolean renderFlatButton(String label, String tooltip) {
        return renderFlatButton(label, tooltip, false);
    }

    /**
     * Render a flat toolbar button with optional active/highlighted state.
     *
     * @return true if clicked
     */
    protected boolean renderFlatButton(String label, String tooltip, boolean highlighted) {
        if (highlighted) {
            pushHighlightedFlatButtonStyle();
        }

        boolean clicked = ImGui.button(label, 0, TOOLBAR_HEIGHT);

        if (highlighted) {
            popHighlightedFlatButtonStyle();
        }

        if (tooltip != null && !tooltip.isEmpty() && ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        return clicked;
    }

    /**
     * Render a compact flat button (for small controls like +/-).
     *
     * @return true if clicked
     */
    protected boolean renderCompactFlatButton(String label, String tooltip) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6.0f, 4.0f);
        boolean clicked = ImGui.button(label, 0, TOOLBAR_HEIGHT);
        ImGui.popStyleVar(1);

        if (tooltip != null && !tooltip.isEmpty() && ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        return clicked;
    }

    /**
     * Render a flat icon button with optional highlighting.
     */
    protected boolean renderFlatIconButton(int textureId, float size, String tooltip, boolean highlighted) {
        if (highlighted) {
            pushHighlightedFlatButtonStyle();
        }

        boolean clicked = ImGui.imageButton("##icon" + textureId, textureId, size, size);

        if (highlighted) {
            popHighlightedFlatButtonStyle();
        }

        if (tooltip != null && !tooltip.isEmpty() && ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        return clicked;
    }

    /**
     * Render a subtle bottom border line to separate toolbar from content below.
     */
    protected void renderBottomBorder() {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float windowWidth = ImGui.getWindowWidth();

        ImVec4 borderColor = ImGui.getStyle().getColor(ImGuiCol.Border);
        int color = ImGui.colorConvertFloat4ToU32(
                borderColor.x, borderColor.y, borderColor.z, 0.5f);

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addLine(cursorPos.x, cursorPos.y, cursorPos.x + windowWidth, cursorPos.y, color, 1.0f);

        // Small spacing after border
        ImGui.dummy(0.0f, 2.0f);
    }

    /**
     * Get the standard toolbar height.
     */
    protected float getToolbarHeight() {
        return TOOLBAR_HEIGHT;
    }

    // ===========================
    // Legacy methods for TextureEditorToolbarRenderer compatibility
    // ===========================

    /**
     * Display a tooltip if the last item is hovered.
     */
    protected void renderTooltip(String tooltip) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }
    }

    /**
     * Apply custom button padding.
     * Call {@link #popButtonPadding()} after rendering buttons.
     */
    protected void pushButtonPadding(float horizontal, float vertical) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, horizontal, vertical);
    }

    /**
     * Remove custom button padding.
     */
    protected void popButtonPadding() {
        ImGui.popStyleVar(1);
    }

    /**
     * Render an icon button with optional highlighting.
     */
    protected boolean renderIconButton(int textureId, float size, String tooltip, boolean highlighted) {
        if (highlighted) {
            pushHighlightedFlatButtonStyle();
        }

        boolean clicked = ImGui.imageButton("##icon" + textureId, textureId, size, size);

        if (highlighted) {
            popHighlightedFlatButtonStyle();
        }

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(tooltip);
        }

        return clicked;
    }

    /**
     * Render a standard button with optional highlighting.
     */
    protected boolean renderButton(String label, String tooltip, boolean highlighted) {
        if (highlighted) {
            pushHighlightedFlatButtonStyle();
        }

        boolean clicked = ImGui.button(label);

        if (highlighted) {
            popHighlightedFlatButtonStyle();
        }

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(tooltip);
        }

        return clicked;
    }

    /**
     * Render a standard button without highlighting.
     */
    protected boolean renderButton(String label, String tooltip) {
        return renderButton(label, tooltip, false);
    }
}
