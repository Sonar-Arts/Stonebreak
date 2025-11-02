package com.openmason.ui.menus;

import com.openmason.ui.LogoManager;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Base class for menu bar renderers providing shared styling and helper methods.
 * Follows DRY principle by centralizing common menu bar rendering patterns.
 * Uses Template Method pattern for consistent rendering lifecycle.
 *
 * <p>Benefits:</p>
 * <ul>
 *   <li>Single source of truth for menu bar styling</li>
 *   <li>Consistent professional appearance across all menu bars</li>
 *   <li>Shared helper methods for common patterns (logo, separators, styled buttons)</li>
 *   <li>Easy to maintain and extend</li>
 * </ul>
 *
 * @author Open Mason Team
 */
public abstract class BaseMenuBarRenderer {

    /**
     * Apply professional menu bar styling.
     * - Tight horizontal padding (4px) with no vertical padding for compact layout
     * - Minimal item spacing (4px horizontal, 0px vertical) for clean alignment
     * - Standard frame padding (4px horizontal, 2px vertical) for clickable items
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
     * Pattern: [Logo] | [Menu Items...]
     *
     * @param logoManager the logo manager, or null to skip logo rendering
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
     * Creates a flat button that highlights on hover with professional blue accent.
     * - Button: Fully transparent (invisible until hovered)
     * - Hovered: Semi-transparent blue (40% opacity)
     * - Active: Solid blue (100% opacity)
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
     * Manages the complete lifecycle:
     * 1. Apply professional styling
     * 2. Begin main menu bar
     * 3. Delegate content rendering to subclass
     * 4. End main menu bar
     * 5. Clean up styling
     *
     * Subclasses implement {@link #renderMenuBarContent()} to define menu items.
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
     *
     * <p>Available helper methods:</p>
     * <ul>
     *   <li>{@link #renderLogoWithSeparator(LogoManager)} - Render logo with separator</li>
     *   <li>{@link #renderMenuSeparator()} - Render vertical separator</li>
     *   <li>{@link #pushTransparentButtonStyle()} / {@link #popTransparentButtonStyle()} - Styled button pattern</li>
     * </ul>
     */
    protected abstract void renderMenuBarContent();
}
