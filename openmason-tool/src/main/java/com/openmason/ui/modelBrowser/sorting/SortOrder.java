package com.openmason.ui.modelBrowser.sorting;

/**
 * Enumeration of sort order directions.
 */
public enum SortOrder {

    /**
     * Ascending order (A-Z, 0-9, oldest to newest).
     */
    ASCENDING("Ascending", "↑"),

    /**
     * Descending order (Z-A, 9-0, newest to oldest).
     */
    DESCENDING("Descending", "↓");

    private final String displayName;
    private final String symbol;

    SortOrder(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Toggles between ascending and descending.
     *
     * @return The opposite sort order
     */
    public SortOrder toggle() {
        return this == ASCENDING ? DESCENDING : ASCENDING;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
