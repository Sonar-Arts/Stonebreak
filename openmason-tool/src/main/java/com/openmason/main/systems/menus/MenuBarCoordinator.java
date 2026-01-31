package com.openmason.main.systems.menus;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import imgui.ImGui;

/**
 * Menu bar coordinator - composes all menu handlers.
 */
public class MenuBarCoordinator extends BaseMenuBarRenderer {

    private final UIVisibilityState uiState;

    private final FileMenuHandler fileMenu;
    private final EditMenuHandler editMenu;
    private final ViewMenuHandler viewMenu;
    private final ToolsMenuHandler toolsMenu;
    private final AboutMenuHandler aboutMenu;

    public MenuBarCoordinator(UIVisibilityState uiState, LogoManager logoManager,
                              FileMenuHandler fileMenu, EditMenuHandler editMenu,
                              ViewMenuHandler viewMenu, ToolsMenuHandler toolsMenu,
                              AboutMenuHandler aboutMenu) {
        this.uiState = uiState;
        this.fileMenu = fileMenu;
        this.editMenu = editMenu;
        this.viewMenu = viewMenu;
        this.toolsMenu = toolsMenu;
        this.aboutMenu = aboutMenu;
    }

    /**
     * Render menu bar content.
     * Delegates to specialized menu handlers and provides toolbar restore functionality.
     */
    @Override
    protected void renderMenuBarContent() {
        // Render all menus
        fileMenu.render();
        editMenu.render();
        viewMenu.render();
        toolsMenu.render();
        aboutMenu.render();

        // Show toolbar restore button when toolbar is hidden
        renderToolbarRestoreButton();
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
