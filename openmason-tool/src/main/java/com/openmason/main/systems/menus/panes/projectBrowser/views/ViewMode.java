package com.openmason.main.systems.menus.panes.projectBrowser.views;

/**
 * Project Browser view modes: GRID (thumbnails), LIST (table with sorting), COMPACT (dense list).
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

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
