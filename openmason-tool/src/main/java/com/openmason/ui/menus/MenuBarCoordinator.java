package com.openmason.ui.menus;

import com.openmason.ui.LogoManager;
import com.openmason.ui.state.UIVisibilityState;
import imgui.ImGui;

/**
 * Menu bar coordinator - composes all menu handlers.
 * Follows Single Responsibility Principle - only coordinates menu rendering.
 * Follows Open/Closed Principle - easy to add new menus without modification.
 */
public class MenuBarCoordinator {

    private final UIVisibilityState uiState;
    private final LogoManager logoManager;

    private final FileMenuHandler fileMenu;
    private final EditMenuHandler editMenu;
    private final ViewMenuHandler viewMenu;
    private final ToolsMenuHandler toolsMenu;
    private final ThemeMenuHandler themeMenu;
    private final HelpMenuHandler helpMenu;

    public MenuBarCoordinator(UIVisibilityState uiState, LogoManager logoManager,
                              FileMenuHandler fileMenu, EditMenuHandler editMenu,
                              ViewMenuHandler viewMenu, ToolsMenuHandler toolsMenu,
                              ThemeMenuHandler themeMenu, HelpMenuHandler helpMenu) {
        this.uiState = uiState;
        this.logoManager = logoManager;
        this.fileMenu = fileMenu;
        this.editMenu = editMenu;
        this.viewMenu = viewMenu;
        this.toolsMenu = toolsMenu;
        this.themeMenu = themeMenu;
        this.helpMenu = helpMenu;
    }

    /**
     * Render the main menu bar.
     */
    public void render() {
        if (!ImGui.beginMainMenuBar()) {
            return;
        }

        // Render logo at the beginning
        if (logoManager != null) {
            logoManager.renderMenuBarLogo();
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();
        }

        // Render all menus
        fileMenu.render();
        editMenu.render();
        viewMenu.render();
        toolsMenu.render();
        themeMenu.render();
        helpMenu.render();

        // Show toolbar restore button when toolbar is hidden
        renderToolbarRestoreButton();

        ImGui.endMainMenuBar();
    }

    /**
     * Render toolbar restore button on right side when toolbar is hidden.
     */
    private void renderToolbarRestoreButton() {
        if (!uiState.getShowToolbar().get()) {
            float availWidth = ImGui.getContentRegionAvailX();
            float buttonWidth = ImGui.calcTextSize("Show Toolbar").x + 16.0f;
            ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - buttonWidth);

            if (ImGui.button("Show Toolbar")) {
                uiState.getShowToolbar().set(true);
            }
        }
    }
}
