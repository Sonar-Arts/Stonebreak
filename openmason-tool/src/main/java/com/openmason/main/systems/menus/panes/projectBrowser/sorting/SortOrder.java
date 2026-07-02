package com.openmason.main.systems.menus.panes.projectBrowser.sorting;

/** Sort direction for the Project Browser. */
public enum SortOrder {
    ASCENDING,
    DESCENDING;

    public SortOrder toggle() {
        return this == ASCENDING ? DESCENDING : ASCENDING;
    }
}
