package com.openmason.main.systems.menus.toolbars;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for toolbar renderers providing shared rendering patterns.
 * Follows DRY principle by centralizing common toolbar rendering code.
 * Matches the pattern established by BaseMenuBarRenderer for consistency.
 */
public abstract class BaseToolbarRenderer {

    // Highlight color for selected/active items (blue)
    protected static final float HIGHLIGHT_R = 0.3f;
    protected static final float HIGHLIGHT_G = 0.5f;
    protected static final float HIGHLIGHT_B = 0.7f;
    protected static final float HIGHLIGHT_A = 1.0f;

    /**
     * Apply highlighted button style (blue background for selected state).
     * Use this for buttons representing the currently selected tool/option.
     * Call {@link #popHighlightedButtonStyle()} after rendering the button.
     */
    protected void pushHighlightedButtonStyle() {
        ImGui.pushStyleColor(ImGuiCol.Button, HIGHLIGHT_R, HIGHLIGHT_G, HIGHLIGHT_B, HIGHLIGHT_A);
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

        boolean clicked = ImGui.imageButton(textureId, size, size);

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
