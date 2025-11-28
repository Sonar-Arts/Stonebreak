package com.openmason.ui.modelBrowser.filters;

/**
 * Enumeration of available filter types for the Model Browser.
 */
public enum FilterType {

    /**
     * Show all models, blocks, items, and recent files.
     * This is the default filter that displays everything.
     */
    ALL_MODELS("All Models"),

    /**
     * Show only cow entity models.
     * Useful for quickly finding cow-related models.
     */
    COW_MODELS("Cow Models"),

    /**
     * Show only recent files.
     * Provides quick access to recently opened models.
     */
    RECENT_FILES("Recent Files");

    private final String displayName;

    /**
     * Creates a new filter type.
     *
     * @param displayName The human-readable display name for this filter
     */
    FilterType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Gets the display name for this filter.
     * This is the text shown to users in the filter dropdown.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Checks if this filter should show entity models.
     */
    public boolean showEntityModels() {
        return this == ALL_MODELS || this == COW_MODELS;
    }

    /**
     * Checks if this filter should show blocks.
     */
    public boolean showBlocks() {
        return this == ALL_MODELS;
    }

    /**
     * Checks if this filter should show items.
     */
    public boolean showItems() {
        return this == ALL_MODELS;
    }

    /**
     * Checks if this filter should show recent files.
     */
    public boolean showRecentFiles() {
        return this == ALL_MODELS || this == RECENT_FILES;
    }

    /**
     * Converts a display name back to a FilterType.
     */
    public static FilterType fromDisplayName(String displayName) {
        for (FilterType type : values()) {
            if (type.displayName.equals(displayName)) {
                return type;
            }
        }
        return ALL_MODELS; // Default fallback
    }

    /**
     * Gets all filter display names as a string array for ImGui combo boxes.
     */
    public static String[] getDisplayNames() {
        FilterType[] types = values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].displayName;
        }
        return names;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
