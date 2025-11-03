package com.openmason.ui.components.modelBrowser.filters;

/**
 * Enumeration of available filter types for the Model Browser.
 *
 * <p>This enum provides type-safe filter handling, following the Open/Closed Principle
 * by making it easy to add new filter types without modifying existing filter logic.</p>
 *
 * <p>Each filter type defines what content should be visible in the Model Browser:</p>
 * <ul>
 *   <li><strong>ALL_MODELS</strong>: Shows all available models, blocks, items, and recent files</li>
 *   <li><strong>COW_MODELS</strong>: Shows only cow entity models and recent files</li>
 *   <li><strong>RECENT_FILES</strong>: Shows only recently opened files</li>
 * </ul>
 *
 * <p>Benefits of using enums over string constants:</p>
 * <ul>
 *   <li>Type safety: Compile-time checking prevents typos</li>
 *   <li>Refactoring: IDE can find all usages</li>
 *   <li>Extensibility: New filters are easy to add</li>
 *   <li>Documentation: Each filter can have detailed documentation</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * FilterType filter = state.getCurrentFilter();
 * if (filter.shouldShow(FilterType.Content.ENTITY_MODELS)) {
 *     renderEntityModels();
 * }
 * }</pre>
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
     *
     * @return The human-readable display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Checks if this filter should show entity models.
     *
     * @return true if entity models should be visible
     */
    public boolean showEntityModels() {
        return this == ALL_MODELS || this == COW_MODELS;
    }

    /**
     * Checks if this filter should show blocks.
     *
     * @return true if blocks should be visible
     */
    public boolean showBlocks() {
        return this == ALL_MODELS;
    }

    /**
     * Checks if this filter should show items.
     *
     * @return true if items should be visible
     */
    public boolean showItems() {
        return this == ALL_MODELS;
    }

    /**
     * Checks if this filter should show recent files.
     *
     * @return true if recent files should be visible
     */
    public boolean showRecentFiles() {
        return this == ALL_MODELS || this == RECENT_FILES;
    }

    /**
     * Converts a display name back to a FilterType.
     * Useful for deserializing saved preferences.
     *
     * @param displayName The display name to convert
     * @return The matching FilterType, or ALL_MODELS if not found
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
     *
     * @return Array of all filter display names
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
