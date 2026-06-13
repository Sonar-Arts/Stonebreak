package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.menus.mainHub.state.HubState;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.type.ImString;

/**
 * The Home landing: a slim search header over a scrolling body that shows
 * Templates and then Recent Projects. Owns layout only — it composes
 * {@link TemplatesPanel} and {@link RecentProjectsPanel} (which keep their own
 * data, regions and dialogs). The "New Project" action lives in the sidebar.
 */
public class HubLandingPanel {

    private static final float SECTION_GAP = 16f;

    private final HubState hubState;
    private final RecentProjectsPanel recentProjectsPanel;
    private final TemplatesPanel templatesPanel;

    private final ImString searchBuffer = new ImString(256);

    public HubLandingPanel(HubState hubState,
                           RecentProjectsPanel recentProjectsPanel, TemplatesPanel templatesPanel) {
        this.hubState = hubState;
        this.recentProjectsPanel = recentProjectsPanel;
        this.templatesPanel = templatesPanel;
    }

    public void render() {
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        if (ImGui.inputTextWithHint("##hub_search", "Search projects and templates",
                searchBuffer, ImGuiInputTextFlags.None)) {
            hubState.setSearchQuery(searchBuffer.get());
        }
        ImGui.dummy(0, 12f);

        if (ImGui.beginChild("##hub_body", 0, 0, false)) {
            templatesPanel.render();
            ImGui.dummy(0, SECTION_GAP);
            recentProjectsPanel.render();

            // Click empty body space (not on a card) to clear the selection,
            // sliding the contextual preview shut.
            if (ImGui.isWindowHovered() && !ImGui.isAnyItemHovered()
                    && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                hubState.clearSelection();
            }
        }
        ImGui.endChild();
    }

    public void update(float deltaTime) {
        recentProjectsPanel.update(deltaTime);
        templatesPanel.update(deltaTime);
    }

    public void dispose() {
        recentProjectsPanel.dispose();
        templatesPanel.dispose();
    }
}
