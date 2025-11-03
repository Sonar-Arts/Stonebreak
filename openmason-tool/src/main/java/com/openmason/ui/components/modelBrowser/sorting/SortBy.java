package com.openmason.ui.components.modelBrowser.sorting;

/**
 * Enumeration of available sort fields for the Model Browser.
 *
 * <p>Defines which property to use when sorting items in list view.</p>
 */
public enum SortBy {

    /**
     * Sort alphabetically by display name.
     */
    NAME("Name"),

    /**
     * Sort by type (Block, Item, Model).
     */
    TYPE("Type"),

    /**
     * Sort by category (Terrain, Ore, Tools, etc.).
     */
    CATEGORY("Category"),

    /**
     * Sort by recent usage (most recent first).
     */
    RECENT("Recent");

    private final String displayName;

    SortBy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
