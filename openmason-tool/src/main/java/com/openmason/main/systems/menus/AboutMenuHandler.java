package com.openmason.main.systems.menus;

import com.openmason.main.systems.stateHandling.HelpWindowVisibilityState;
import imgui.ImGui;

/**
 * Generic about menu handler that works with any tool.
 */
public class AboutMenuHandler {

    private final HelpWindowVisibilityState visibilityState;

    /**
     * Create about menu handler.
     */
    public AboutMenuHandler(HelpWindowVisibilityState visibilityState) {
        this.visibilityState = visibilityState;
    }

    /**
     * Render the about menu item.
     */
    public void render() {
        if (ImGui.menuItem("About")) {
            visibilityState.showAbout();
        }
    }
}
