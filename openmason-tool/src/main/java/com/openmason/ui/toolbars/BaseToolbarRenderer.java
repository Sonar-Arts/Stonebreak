package com.openmason.ui.toolbars;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for toolbar renderers providing shared rendering patterns.
 * Follows DRY principle by centralizing common toolbar rendering code.
 * Matches the pattern established by BaseMenuBarRenderer for consistency.
 *
 * <p>Benefits:</p>
 * <ul>
 *   <li>Single source of truth for toolbar styling patterns</li>
 *   <li>Consistent button highlighting across all toolbars</li>
 *   <li>Shared separator and tooltip rendering</li>
 *   <li>Easy to maintain and extend</li>
 * </ul>
 *
 * @author Open Mason Team
 */
public abstract class BaseToolbarRenderer {

    // Highlight color for selected/active items (blue)
    protected static final float HIGHLIGHT_R = 0.3f;
    protected static final float HIGHLIGHT_G = 0.5f;
    protected static final float HIGHLIGHT_B = 0.7f;
    protected static final float HIGHLIGHT_A = 1.0f;

    // Transparent button colors (for flat buttons that highlight on hover)
    protected static final float TRANSPARENT_R = 0.0f;
    protected static final float TRANSPARENT_G = 0.0f;
    protected static final float TRANSPARENT_B = 0.0f;
    protected static final float TRANSPARENT_A = 0.0f;

    // Hover and active colors (standard ImGui blue)
    protected static final float HOVER_R = 0.26f;
    protected static final float HOVER_G = 0.59f;
    protected static final float HOVER_B = 0.98f;
    protected static final float HOVER_A = 0.40f;
    protected static final float ACTIVE_R = 0.26f;
    protected static final float ACTIVE_G = 0.59f;
    protected static final float ACTIVE_B = 0.98f;
    protected static final float ACTIVE_A = 1.0f;

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
     * Apply transparent button styling with hover effects.
     * Creates a flat button that highlights on hover with professional blue accent.
     * - Button: Fully transparent (invisible until hovered)
     * - Hovered: Semi-transparent blue (40% opacity)
     * - Active: Solid blue (100% opacity)
     *
     * Call {@link #popTransparentButtonStyle()} after rendering the button.
     */
    protected void pushTransparentButtonStyle() {
        ImGui.pushStyleColor(ImGuiCol.Button, TRANSPARENT_R, TRANSPARENT_G, TRANSPARENT_B, TRANSPARENT_A);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, HOVER_R, HOVER_G, HOVER_B, HOVER_A);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ACTIVE_R, ACTIVE_G, ACTIVE_B, ACTIVE_A);
    }

    /**
     * Remove transparent button styling.
     */
    protected void popTransparentButtonStyle() {
        ImGui.popStyleColor(3);
    }

    /**
     * Render a vertical separator for visual grouping.
     * Pattern: [Item] | [Next Item]
     *
     * Use {@code sameLine()} before and after to keep items on the same line.
     */
    protected void renderSeparator() {
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();
    }

    /**
     * Display a tooltip if the last item is hovered.
     *
     * @param tooltip the tooltip text to display
     */
    protected void renderTooltip(String tooltip) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }
    }

    /**
     * Apply custom item spacing for toolbar layout.
     * Call {@link #popItemSpacing()} after rendering toolbar items.
     *
     * @param horizontal horizontal spacing between items
     * @param vertical vertical spacing between items
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
     *
     * @param horizontal horizontal padding
     * @param vertical vertical padding
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
     *
     * @param textureId the OpenGL texture ID for the icon
     * @param size the display size for the icon button
     * @param tooltip the tooltip to show on hover (can be null)
     * @param highlighted whether to apply blue highlight styling
     * @return true if the button was clicked
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
     * Render an icon button without highlighting.
     *
     * @param textureId the OpenGL texture ID for the icon
     * @param size the display size for the icon button
     * @param tooltip the tooltip to show on hover (can be null)
     * @return true if the button was clicked
     */
    protected boolean renderIconButton(int textureId, float size, String tooltip) {
        return renderIconButton(textureId, size, tooltip, false);
    }

    /**
     * Render a standard button with optional highlighting.
     *
     * @param label the button label
     * @param tooltip the tooltip to show on hover (can be null)
     * @param highlighted whether to apply blue highlight styling
     * @return true if the button was clicked
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
     *
     * @param label the button label
     * @param tooltip the tooltip to show on hover (can be null)
     * @return true if the button was clicked
     */
    protected boolean renderButton(String label, String tooltip) {
        return renderButton(label, tooltip, false);
    }
}
