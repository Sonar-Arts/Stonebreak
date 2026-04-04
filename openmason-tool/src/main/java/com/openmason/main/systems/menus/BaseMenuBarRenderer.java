package com.openmason.main.systems.menus;

import com.openmason.main.systems.LogoManager;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for menu bar renderers providing shared styling and helper methods.
 */
public abstract class BaseMenuBarRenderer {

    /**
     * Apply professional menu bar styling with improved spacing.
     */
    protected void applyMenuBarStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6.0f, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6.0f, 4.0f);
    }

    /**
     * Remove menu bar styling (call after endMainMenuBar).
     */
    protected void popMenuBarStyle() {
        ImGui.popStyleVar(3);
    }

    /**
     * Apply styling for dropdown menu popups (wider items, more padding).
     * Call before beginMenu() content, pop with popDropdownStyle().
     */
    protected void pushDropdownStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, 6.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8.0f, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6.0f, 4.0f);
    }

    /**
     * Remove dropdown menu styling.
     */
    protected void popDropdownStyle() {
        ImGui.popStyleVar(3);
    }

    /**
     * Render logo with separator for visual separation.
     */
    protected void renderLogoWithSeparator(LogoManager logoManager) {
        if (logoManager != null) {
            logoManager.renderMenuBarLogo();
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();
        }
    }

    /**
     * Render a vertical separator between menu sections.
     * Pattern: [Item] | [Next Item]
     */
    protected void renderMenuSeparator() {
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();
    }

    /**
     * Apply transparent button styling with hover effects.
     */
    protected void pushTransparentButtonStyle() {
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 1.0f);
    }

    /**
     * Remove transparent button styling (call after button rendering).
     */
    protected void popTransparentButtonStyle() {
        ImGui.popStyleColor(3);
    }

    /**
     * Render the menu bar using Template Method pattern.
     */
    public final void render() {
        applyMenuBarStyle();

        if (!ImGui.beginMainMenuBar()) {
            popMenuBarStyle();
            return;
        }

        renderMenuBarContent();

        ImGui.endMainMenuBar();
        popMenuBarStyle();
    }

    /**
     * Render menu bar content (menus, buttons, status info, etc.).
     * Subclasses implement this to define their specific menu structure.
     */
    protected abstract void renderMenuBarContent();
}
