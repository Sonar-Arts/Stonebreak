package com.openmason.ui.modelBrowser;

import com.openmason.ui.modelBrowser.filters.FilterType;
import com.openmason.ui.modelBrowser.sorting.SortBy;
import com.openmason.ui.modelBrowser.sorting.SortOrder;
import com.openmason.ui.modelBrowser.views.ViewMode;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages all state for the Model Browser component.
 */
public class ModelBrowserState {

    // Constants
    private static final int MAX_SEARCH_TEXT_LENGTH = 256;
    private static final int MAX_RECENT_FILES = 10;
    private static final float DEFAULT_SIDEBAR_WIDTH = 200.0f;
    private static final float MIN_SIDEBAR_WIDTH = 150.0f;
    private static final float MAX_SIDEBAR_WIDTH = 400.0f;

    // Search and filter state
    private final ImString searchText;
    private final String[] filters; // Display names for ImGui combo
    private final ImInt currentFilterIndex;

    // View and layout state (NEW for file explorer)
    private ViewMode viewMode;
    private SortBy sortBy;
    private SortOrder sortOrder;
    private float sidebarWidth;

    // Navigation state (NEW for file explorer)
    private final List<String> navigationPath; // Breadcrumb trail: ["Home", "Blocks", "Terrain"]
    private String selectedCategory; // Currently selected sidebar category

    // Selection state
    private String selectedModelInfo;

    // Recent files
    private final List<String> recentFiles;

    /**
     * Creates a new Model Browser state with default values.
     */
    public ModelBrowserState() {
        // Search and filter
        this.searchText = new ImString("", MAX_SEARCH_TEXT_LENGTH);
        this.filters = FilterType.getDisplayNames();
        this.currentFilterIndex = new ImInt(0);

        // View and layout defaults
        this.viewMode = ViewMode.GRID;
        this.sortBy = SortBy.NAME;
        this.sortOrder = SortOrder.ASCENDING;
        this.sidebarWidth = DEFAULT_SIDEBAR_WIDTH;

        // Navigation defaults
        this.navigationPath = new ArrayList<>();
        this.navigationPath.add("Home");
        this.selectedCategory = "All Models";

        // Selection and recent files
        this.selectedModelInfo = "No model selected";
        this.recentFiles = new ArrayList<>();
    }

    /**
     * Gets the search text ImString for ImGui binding.
     *
     * @return The search text ImString
     */
    public ImString getSearchText() {
        return searchText;
    }

    /**
     * Gets the current search text as a String.
     *
     * @return The current search text
     */
    public String getSearchTextValue() {
        return searchText.get();
    }

    /**
     * Sets the search text.
     *
     * @param text The new search text
     */
    public void setSearchTextValue(String text) {
        searchText.set(text);
    }

    /**
     * Gets the available filter options.
     *
     * @return Array of filter option names
     */
    public String[] getFilters() {
        return filters;
    }

    /**
     * Gets the current filter index ImInt for ImGui binding.
     *
     * @return The current filter index ImInt
     */
    public ImInt getCurrentFilterIndex() {
        return currentFilterIndex;
    }

    /**
     * Gets the current filter type.
     *
     * @return The currently selected FilterType enum value
     */
    public FilterType getCurrentFilter() {
        return FilterType.values()[currentFilterIndex.get()];
    }

    /**
     * Gets the selected model information text.
     *
     * @return The selected model info text
     */
    public String getSelectedModelInfo() {
        return selectedModelInfo;
    }

    /**
     * Sets the selected model information text.
     *
     * @param info The new selected model info
     */
    public void setSelectedModelInfo(String info) {
        this.selectedModelInfo = info != null ? info : "No model selected";
    }

    /**
     * Gets the list of recent files (read-only).
     *
     * @return Unmodifiable list of recent file names
     */
    public List<String> getRecentFiles() {
        return Collections.unmodifiableList(recentFiles);
    }

    /**
     * Gets the recent files as an array for ImGui.
     *
     * @return Array of recent file names
     */
    public String[] getRecentFilesArray() {
        return recentFiles.toArray(new String[0]);
    }

    /**
     * Adds a file to the recent files list.
     * If the file already exists, it's moved to the top.
     * List is limited to 10 most recent files.
     *
     * @param fileName The file name to add
     */
    public void addRecentFile(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return;
        }

        // Remove if already exists
        recentFiles.remove(fileName);

        // Add to the beginning
        recentFiles.add(0, fileName);

        // Limit to maximum recent files
        while (recentFiles.size() > MAX_RECENT_FILES) {
            recentFiles.remove(recentFiles.size() - 1);
        }
    }

    /**
     * Resets the state to default values.
     */
    public void reset() {
        searchText.set("");
        currentFilterIndex.set(0);
        selectedModelInfo = "No model selected";
        recentFiles.clear();
    }

    /**
     * Checks if the search is active (non-empty search text).
     *
     * @return true if search text is not empty
     */
    public boolean isSearchActive() {
        String text = searchText.get();
        return text != null && !text.trim().isEmpty();
    }

    /**
     * Checks if a given text matches the current search.
     *
     * @param text The text to check
     * @return true if the text contains the search term (case-insensitive)
     */
    public boolean matchesSearch(String text) {
        if (!isSearchActive()) {
            return true; // No search active, everything matches
        }

        String searchTerm = searchText.get().toLowerCase();
        return text.toLowerCase().contains(searchTerm);
    }

    // ==================== View Mode and Layout ====================

    /**
     * Gets the current view mode.
     *
     * @return The active view mode (GRID, LIST, or COMPACT)
     */
    public ViewMode getViewMode() {
        return viewMode;
    }

    /**
     * Sets the view mode.
     *
     * @param viewMode The new view mode
     */
    public void setViewMode(ViewMode viewMode) {
        if (viewMode != null) {
            this.viewMode = viewMode;
        }
    }

    /**
     * Gets the current sort field.
     *
     * @return The field to sort by
     */
    public SortBy getSortBy() {
        return sortBy;
    }

    /**
     * Sets the sort field.
     *
     * @param sortBy The field to sort by
     */
    public void setSortBy(SortBy sortBy) {
        if (sortBy != null) {
            this.sortBy = sortBy;
        }
    }

    /**
     * Gets the current sort order.
     *
     * @return The sort order (ASCENDING or DESCENDING)
     */
    public SortOrder getSortOrder() {
        return sortOrder;
    }

    /**
     * Sets the sort order.
     *
     * @param sortOrder The new sort order
     */
    public void setSortOrder(SortOrder sortOrder) {
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    /**
     * Gets the sidebar width in pixels.
     *
     * @return The sidebar width
     */
    public float getSidebarWidth() {
        return sidebarWidth;
    }

    /**
     * Sets the sidebar width with clamping to min/max bounds.
     *
     * @param width The desired sidebar width
     */
    public void setSidebarWidth(float width) {
        this.sidebarWidth = Math.max(MIN_SIDEBAR_WIDTH, Math.min(MAX_SIDEBAR_WIDTH, width));
    }

    // ==================== Navigation ====================

    /**
     * Gets the navigation path (breadcrumb trail).
     *
     * @return Unmodifiable list of path segments
     */
    public List<String> getNavigationPath() {
        return Collections.unmodifiableList(navigationPath);
    }

    /**
     * Sets the navigation path.
     *
     * @param path The new navigation path
     */
    public void setNavigationPath(List<String> path) {
        this.navigationPath.clear();
        if (path != null && !path.isEmpty()) {
            this.navigationPath.addAll(path);
        } else {
            this.navigationPath.add("Home");
        }
    }

    /**
     * Navigates to a specific path segment.
     * Truncates the path at the specified segment.
     *
     * @param segment The path segment to navigate to
     */
    public void navigateTo(String segment) {
        int index = navigationPath.indexOf(segment);
        if (index >= 0) {
            // Remove all segments after this one
            while (navigationPath.size() > index + 1) {
                navigationPath.remove(navigationPath.size() - 1);
            }
        }
    }

    /**
     * Gets the current path as a string.
     *
     * @return Path string like "Home > Blocks > Terrain"
     */
    public String getNavigationPathString() {
        return String.join(" > ", navigationPath);
    }

    /**
     * Gets the selected category in the sidebar.
     *
     * @return The selected category name
     */
    public String getSelectedCategory() {
        return selectedCategory;
    }

    /**
     * Sets the selected category.
     *
     * @param category The category to select
     */
    public void setSelectedCategory(String category) {
        if (category != null) {
            this.selectedCategory = category;
        }
    }
}
