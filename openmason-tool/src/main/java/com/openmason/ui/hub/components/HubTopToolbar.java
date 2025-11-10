package com.openmason.ui.hub.components;

import com.openmason.ui.hub.state.HubState;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;

/**
 * Top toolbar with version, search, preferences, and user area.
 * Single Responsibility: Render and manage top toolbar UI.
 */
public class HubTopToolbar {

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final ImString searchBuffer = new ImString(256);
    private String editorVersion = "Open Mason v0.0.2";
    private Runnable onPreferencesClicked;

    public HubTopToolbar(ThemeManager themeManager, HubState hubState) {
        this.themeManager = themeManager;
        this.hubState = hubState;
    }

    /**
     * Render the top toolbar.
     */
    public void render() {
        ThemeDefinition theme = themeManager.getCurrentTheme();

        // Version info (left side)
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
        float cursorX = ImGui.getCursorPosX();
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

    /**
     * Set editor version text.
     */
    public void setEditorVersion(String version) {
        this.editorVersion = version != null ? version : "Open Mason";
    }
}
