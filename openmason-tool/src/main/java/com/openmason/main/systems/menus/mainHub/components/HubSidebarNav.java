package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

import java.util.ArrayList;
import java.util.List;

/**
 * Left sidebar navigation panel.
 * Single Responsibility: Render navigation menu and handle selection.
 */
public class HubSidebarNav {

    private static final float NAV_ITEM_HEIGHT = 40.0f;
    private static final float LOGO_SIZE = 100.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final LogoManager logoManager;
    private final List<NavigationItem> navItems;

    public HubSidebarNav(ThemeManager themeManager, HubState hubState, LogoManager logoManager) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.logoManager = logoManager;
        this.navItems = createNavigationItems();

        // Select first item by default
        if (!navItems.isEmpty()) {
            NavigationItem firstItem = navItems.get(0);
            hubState.setSelectedNavItem(firstItem);
            hubState.setCurrentView(firstItem.getViewType());
        }
    }

    /**
     * Render the sidebar navigation.
     */
    public void render() {
        // Logo at top
        renderLogo();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Navigation items
        for (NavigationItem item : navItems) {
            renderNavigationItem(item);
        }
    }

    /**
     * Render the Open Mason logo.
     */
    private void renderLogo() {
        float sidebarWidth = ImGui.getWindowWidth();
        ImVec2 scaledSize = logoManager.getScaledLogoSize(LOGO_SIZE, LOGO_SIZE);

        // Center logo horizontally
        float cursorX = (sidebarWidth - scaledSize.x) * 0.5f;
        ImGui.setCursorPosX(cursorX);

        logoManager.renderLogo(scaledSize.x, scaledSize.y);

        ImGui.spacing();

        // Project Hub title
        String title = "Project Hub";
        ImVec2 titleSize = ImGui.calcTextSize(title);
        float titleX = (sidebarWidth - titleSize.x) * 0.5f;
        ImGui.setCursorPosX(titleX);

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 textColor = theme.getColor(ImGuiCol.Text);
        if (textColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textColor.x, textColor.y, textColor.z, textColor.w);
        }
        ImGui.text(title);
        if (textColor != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Render a single navigation item.
     */
    private void renderNavigationItem(NavigationItem item) {
        ThemeDefinition theme = themeManager.getCurrentTheme();
        boolean isSelected = hubState.getSelectedNavItem() == item;

        // Selection highlight
        if (isSelected) {
            ImVec4 selectedBg = theme.getColor(ImGuiCol.Header);
            if (selectedBg != null) {
                ImGui.pushStyleColor(ImGuiCol.Button, selectedBg.x, selectedBg.y, selectedBg.z, selectedBg.w);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, selectedBg.x, selectedBg.y, selectedBg.z, selectedBg.w);
            } else {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 0.4f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.4f);
            }
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0); // Transparent
            ImVec4 hoverColor = theme.getColor(ImGuiCol.ButtonHovered);
            if (hoverColor != null) {
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, hoverColor.x, hoverColor.y, hoverColor.z, 0.3f);
            } else {
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.2f);
            }
        }

        // Render button with label
        String buttonLabel = item.getLabel();
        if (ImGui.button(buttonLabel, -1, NAV_ITEM_HEIGHT)) {
            item.select();
            hubState.setSelectedNavItem(item);
            hubState.clearSelection(); // Clear template/project selection when switching views
        }

        ImGui.popStyleColor(2);

        // Spacing between items
        ImGui.spacing();
    }

    /**
     * Create navigation items.
     */
    private List<NavigationItem> createNavigationItems() {
        List<NavigationItem> items = new ArrayList<>();

        items.add(new NavigationItem(
                "templates",
                "Templates",
                NavigationItem.ViewType.TEMPLATES,
                () -> hubState.setCurrentView(NavigationItem.ViewType.TEMPLATES)
        ));

        items.add(new NavigationItem(
                "recent-projects",
                "Projects",
                NavigationItem.ViewType.RECENT_PROJECTS,
                () -> hubState.setCurrentView(NavigationItem.ViewType.RECENT_PROJECTS)
        ));

        items.add(new NavigationItem(
                "learn",
                "Learn",
                NavigationItem.ViewType.LEARN,
                () -> hubState.setCurrentView(NavigationItem.ViewType.LEARN)
        ));

        return items;
    }
}
