package com.openmason.main.systems.menus.toolbars;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for toolbar renderers providing shared rendering patterns.
 * Follows DRY principle by centralizing common toolbar rendering code.
 * Matches the pattern established by BaseMenuBarRenderer for consistency.
 */
public abstract class BaseToolbarRenderer {

    /**
     * Apply highlighted button style (accent background for selected state).
     * Colour is derived from the active theme's HeaderActive.
     * Call {@link #popHighlightedButtonStyle()} after rendering the button.
     */
    protected void pushHighlightedButtonStyle() {
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, accent.w);
    }

    /**
     * Remove highlighted button styling.
     */
    protected void popHighlightedButtonStyle() {
        ImGui.popStyleColor(1);
    }

    /**
     * Render a vertical separator for visual grouping.
     * Pattern: [Item] | [Next Item]
     */
    protected void renderSeparator() {
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();
    }

    /**
     * Display a tooltip if the last item is hovered.
     */
    protected void renderTooltip(String tooltip) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }
    }

    /**
     * Apply custom item spacing for toolbar layout.
     */
    protected void pushItemSpacing(float horizontal, float vertical) {
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, horizontal, vertical);
    }

    /**
     * Remove custom item spacing.
     */
    protected void popItemSpacing() {
        ImGui.popStyleVar(1);
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
            pushHighlightedButtonStyle();
        }

        boolean clicked = ImGui.imageButton("##icon" + textureId, textureId, size, size);

        if (highlighted) {
            popHighlightedButtonStyle();
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
            pushHighlightedButtonStyle();
        }

        boolean clicked = ImGui.button(label);

        if (highlighted) {
            popHighlightedButtonStyle();
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
