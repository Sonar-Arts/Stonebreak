package com.openmason.main.systems.menus.panes.projectBrowser.views;

/**
 * Strategy interface for Project Browser view renderers.
 * Implementations provide different visualizations (grid, list, compact) for the same data.
 */
public interface ViewRenderer {

    /**
     * Renders the view content.
     * Called every frame to display filtered items and handle user interactions.
     */
    void render();
}
