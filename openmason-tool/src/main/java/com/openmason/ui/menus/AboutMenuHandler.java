package com.openmason.ui.menus;

import com.openmason.ui.state.HelpWindowVisibilityState;
import imgui.ImGui;

/**
 * Generic about menu handler that works with any tool.
 * Uses interface-based design to support multiple tools (Model Viewer, Texture Creator, etc.).
 * Follows Single Responsibility Principle - only handles about menu operations.
 * Follows Dependency Inversion Principle - depends on abstraction (interface) not concrete classes.
 */
public class AboutMenuHandler {

    private final HelpWindowVisibilityState visibilityState;

    /**
     * Create about menu handler.
     *
     * @param visibilityState the visibility state interface for managing about window
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
