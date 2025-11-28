package com.openmason.main.systems.menus;

import com.openmason.main.systems.LogoManager;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for menu bar renderers providing shared styling and helper methods.
 */
public abstract class BaseMenuBarRenderer {

    /**
     * Apply professional menu bar styling.
     */
    protected void applyMenuBarStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4.0f, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4.0f, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f, 2.0f);
    }

    /**
     * Remove menu bar styling (call after endMainMenuBar).
     */
    protected void popMenuBarStyle() {
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
        ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.26f, 0.59f, 0.98f, 1.0f);
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
