package com.openmason.main.systems.menus.panes.modelBrowser.views;

/**
 * Strategy interface for Model Browser view renderers.
 * Implementations provide different visualizations (grid, list, compact) for the same data.
 */
public interface ViewRenderer {

    /**
     * Renders the view content.
     * Called every frame to display filtered items and handle user interactions.
     */
    void render();

    /**
     * Cleanup when view is no longer needed. Override if managing resources.
     */
    default void cleanup() {
    }

}
