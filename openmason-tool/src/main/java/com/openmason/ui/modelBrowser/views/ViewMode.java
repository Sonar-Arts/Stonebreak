package com.openmason.ui.modelBrowser.views;

/**
 * Model Browser view modes: GRID (thumbnails), LIST (table with sorting), COMPACT (dense list).
 */
public enum ViewMode {

    /** Grid layout with thumbnails and labels */
    GRID("Grid View", "View items as a grid of thumbnails"),

    /** Table with sortable columns */
    LIST("List View", "View items in a detailed table with sortable columns"),

    /** Dense single-column list with small icons */
    COMPACT("Compact View", "View items in a compact list");

    private final String displayName;
    private final String description;

    ViewMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /** @return Human-readable view mode name */
    public String getDisplayName() {
        return displayName;
    }

    /** @return View mode description for tooltips */
    public String getDescription() {
        return description;
    }

    /** @return Next view mode in rotation */
    public ViewMode next() {
        ViewMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    @Override
    public String toString() {
        return displayName;
    }
}
