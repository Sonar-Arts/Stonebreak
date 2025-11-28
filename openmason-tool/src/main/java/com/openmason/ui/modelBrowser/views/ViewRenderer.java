package com.openmason.ui.modelBrowser.views;

/**
 * Interface for Model Browser view renderers.
 *
 * <p>Following the Strategy pattern, different view renderers can be swapped
 * based on the selected ViewMode, allowing users to switch between grid, list,
 * and compact visualizations of the same data.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Single Responsibility</strong>: Each renderer only handles rendering for its view mode</li>
 *   <li><strong>Open/Closed</strong>: New view modes can be added without modifying existing renderers</li>
 *   <li><strong>Interface Segregation</strong>: Minimal interface with only essential methods</li>
 *   <li><strong>Dependency Inversion</strong>: Depends on controller abstraction, not concrete implementation</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * ViewRenderer renderer = switch (state.getViewMode()) {
 *     case GRID -> new GridViewRenderer(controller);
 *     case LIST -> new ListViewRenderer(controller);
 *     case COMPACT -> new CompactListRenderer(controller);
 * };
 * renderer.render();
 * }</pre>
 */
public interface ViewRenderer {

    /**
     * Renders the view content.
     * Should be called every frame when the view should be visible.
     *
     * <p>Implementations should:</p>
     * <ul>
     *   <li>Retrieve data from controller (blocks, items, models)</li>
     *   <li>Apply search/filter from state</li>
     *   <li>Render items in the appropriate layout</li>
     *   <li>Handle user interactions (click, double-click, context menu)</li>
     *   <li>Notify controller of selections</li>
     * </ul>
     */
    void render();

    /**
     * Gets the view mode this renderer handles.
     *
     * @return The ViewMode enum value for this renderer
     */
    ViewMode getViewMode();

    /**
     * Performs cleanup when the view is no longer needed.
     * Optional operation for renderers that manage resources (textures, caches, etc.).
     */
    default void cleanup() {
        // Default: no cleanup needed
    }

    /**
     * Called when the view becomes active.
     * Optional operation for renderers that need initialization.
     */
    default void onActivate() {
        // Default: no activation needed
    }

    /**
     * Called when the view becomes inactive (user switches to different view).
     * Optional operation for renderers that need to preserve state.
     */
    default void onDeactivate() {
        // Default: no deactivation needed
    }
}
