package com.openmason.ui.components.modelBrowser.views;

/**
 * Enumeration of available view modes for the Model Browser.
 *
 * <p>Following the Windows Explorer pattern, users can switch between different
 * visualization modes to browse models, blocks, and items.</p>
 *
 * <h3>View Modes</h3>
 * <ul>
 *   <li><strong>GRID</strong>: Thumbnail grid with labels, ideal for visual browsing</li>
 *   <li><strong>LIST</strong>: Multi-column table with detailed information and sorting</li>
 *   <li><strong>COMPACT</strong>: Dense list view for quick scanning and keyboard navigation</li>
 * </ul>
 *
 * <p>Each view mode has different strengths:</p>
 * <ul>
 *   <li>Grid: Best for recognizing items visually by texture/appearance</li>
 *   <li>List: Best for comparing properties and sorting by attributes</li>
 *   <li>Compact: Best for quickly finding items by name with keyboard</li>
 * </ul>
 */
public enum ViewMode {

    /**
     * Grid view with thumbnails and labels.
     * Items displayed in a grid layout with 64x64 thumbnails and name labels.
     * Similar to Windows Explorer "Large Icons" or "Medium Icons" view.
     */
    GRID("Grid View", "View items as a grid of thumbnails"),

    /**
     * List view with multi-column table.
     * Items displayed in a table with sortable columns: thumbnail, name, type, category, properties.
     * Similar to Windows Explorer "Details" view.
     */
    LIST("List View", "View items in a detailed table with sortable columns"),

    /**
     * Compact list view.
     * Dense single-column list with small icons and minimal spacing.
     * Similar to Windows Explorer "List" or VSCode file explorer.
     */
    COMPACT("Compact View", "View items in a compact list");

    private final String displayName;
    private final String description;

    /**
     * Creates a new view mode.
     *
     * @param displayName The human-readable name for UI display
     * @param description Brief description of the view mode
     */
    ViewMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Gets the display name for UI rendering.
     *
     * @return The human-readable view mode name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the description for tooltips.
     *
     * @return The view mode description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets the next view mode in the rotation (for toggle button).
     *
     * @return The next view mode in sequence
     */
    public ViewMode next() {
        ViewMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /**
     * Checks if this view mode supports thumbnails.
     *
     * @return true if thumbnails are displayed
     */
    public boolean supportsThumbnails() {
        return this == GRID || this == LIST;
    }

    /**
     * Checks if this view mode supports sorting.
     *
     * @return true if sorting controls should be shown
     */
    public boolean supportsSorting() {
        return this == LIST;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
