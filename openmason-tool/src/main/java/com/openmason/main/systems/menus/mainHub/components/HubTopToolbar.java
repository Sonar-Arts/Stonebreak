package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.menus.mainHub.state.HubState;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;

/**
 * Top toolbar with version, search, preferences, and user area.
 * Single Responsibility: Render and manage top toolbar UI.
 */
public class HubTopToolbar {

    private final HubState hubState;
    private final ImString searchBuffer = new ImString(256);
    private Runnable onPreferencesClicked;

    public HubTopToolbar(HubState hubState) {
        this.hubState = hubState;
    }

    /**
     * Render the top toolbar.
     */
    public void render() {
        // Version info (left side)
        String editorVersion = "Open Mason v0.0.2";
        ImGui.text(editorVersion);
        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        // Search bar (center-ish)
        ImGui.setNextItemWidth(300.0f);
        if (ImGui.inputText("##search", searchBuffer, ImGuiInputTextFlags.None)) {
            hubState.setSearchQuery(searchBuffer.get());
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Search templates and projects");
        }

        ImGui.sameLine();

        // Push to right side
        float windowWidth = ImGui.getWindowWidth();
        float itemWidth = 100.0f; // Approximate width for preferences button
        ImGui.setCursorPosX(windowWidth - itemWidth);

        // Preferences button
        if (ImGui.button("Preferences")) {
            if (onPreferencesClicked != null) {
                onPreferencesClicked.run();
            }
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open Preferences");
        }
    }

    /**
     * Set callback for preferences button.
     */
    public void setOnPreferencesClicked(Runnable callback) {
        this.onPreferencesClicked = callback;
    }

}
