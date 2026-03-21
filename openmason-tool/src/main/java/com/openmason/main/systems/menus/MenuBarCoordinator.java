package com.openmason.main.systems.menus;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.icons.MenuBarIconManager;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

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

    private static final float HOME_ICON_DISPLAY_SIZE = 16.0f;

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

        // Spacer + right-aligned items
        renderRightAlignedItems();
    }

    /**
     * Render right-aligned items: Home button and toolbar restore.
     */
    private void renderRightAlignedItems() {
        float availWidth = ImGui.getContentRegionAvailX();

        // Calculate total width of right-aligned items
        // imageButton adds FramePadding on each side
        float framePadX = ImGui.getStyle().getFramePaddingX();
        float homeButtonWidth = HOME_ICON_DISPLAY_SIZE + framePadX * 2;
        float toolbarButtonWidth = 0.0f;
        boolean showToolbarRestore = !uiState.getShowToolbar().get();
        if (showToolbarRestore) {
            toolbarButtonWidth = ImGui.calcTextSize("Show Toolbar").x + framePadX * 2 + 8.0f;
        }

        float totalRightWidth = homeButtonWidth + toolbarButtonWidth;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - totalRightWidth);

        // Toolbar restore button
        if (showToolbarRestore) {
            if (ImGui.button("Show Toolbar")) {
                uiState.getShowToolbar().set(true);
            }
            ImGui.sameLine(0, 8.0f);
        }

        // Home button with SVG icon
        renderHomeButton();
    }

    /**
     * Render the Home Screen button on the menu bar using the SVG icon.
     * Falls back to text if the icon failed to load.
     */
    private void renderHomeButton() {
        int textureId = MenuBarIconManager.getInstance().getHomeIconTexture();

        pushTransparentButtonStyle();

        boolean clicked;
        if (textureId != -1) {
            clicked = ImGui.imageButton("##homeScreen", textureId,
                    HOME_ICON_DISPLAY_SIZE, HOME_ICON_DISPLAY_SIZE);
        } else {
            // Fallback to text if SVG failed to load
            clicked = ImGui.button("Home");
        }

        popTransparentButtonStyle();

        if (clicked) {
            fileMenu.requestHomeScreen();
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Home Screen");
        }
    }
}
