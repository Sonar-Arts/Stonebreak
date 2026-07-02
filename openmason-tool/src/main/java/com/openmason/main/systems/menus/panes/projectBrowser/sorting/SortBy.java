package com.openmason.main.systems.menus.panes.projectBrowser.sorting;

/** Fields the Project Browser can sort assets by. */
public enum SortBy {
    NAME("Name"),
    TYPE("Type");

    private final String displayName;

    SortBy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
