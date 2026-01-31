package com.openmason.main.systems.menus.panes.propertyPane.interfaces;

/**
 * Interface for property panel sections following SOLID principles.
 * Each section is responsible for rendering a specific part of the properties panel.
 */
public interface IPanelSection {

    /**
     * Render this section using ImGui.
     * Called once per frame during the panel's render cycle.
     */
    void render();

    /**
     * Check if this section should be visible.
     *
     * @return true if the section should be rendered, false otherwise
     */
    boolean isVisible();

    /**
     * Get the section's display name.
     *
     * @return The name displayed in the section header
     */
    String getSectionName();

}
