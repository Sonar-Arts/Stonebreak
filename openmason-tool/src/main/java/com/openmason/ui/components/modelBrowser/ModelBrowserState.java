package com.openmason.ui.components.modelBrowser;

import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages all state for the Model Browser component.
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * state management. It provides a clean interface for accessing and modifying
 * model browser state, making it easy to serialize for preferences persistence.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Single Responsibility: Only manages model browser state</li>
 *   <li>Open/Closed: New state fields can be added without modifying existing code</li>
 * </ul>
 *
 * <p>Thread-safe through defensive copying where appropriate.</p>
 */
public class ModelBrowserState {

    // Search and filter state
    private final ImString searchText;
    private final String[] filters;
    private final ImInt currentFilterIndex;

    // Selection state
    private String selectedModelInfo;

    // Recent files
    private final List<String> recentFiles;

    /**
     * Creates a new Model Browser state with default values.
     */
    public ModelBrowserState() {
        this.searchText = new ImString("", 256);
        this.filters = new String[]{"All Models", "Cow Models", "Recent Files"};
        this.currentFilterIndex = new ImInt(0);
        this.selectedModelInfo = "No model selected";
        this.recentFiles = new ArrayList<>();

        // Initialize with default recent files
        this.recentFiles.add("standard_cow.json");
        this.recentFiles.add("example_model.json");
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
     * Gets the current filter index value.
     *
     * @return The current filter index
     */
    public int getCurrentFilterValue() {
        return currentFilterIndex.get();
    }

    /**
     * Sets the current filter index.
     *
     * @param index The new filter index
     */
    public void setCurrentFilterIndex(int index) {
        if (index >= 0 && index < filters.length) {
            currentFilterIndex.set(index);
        }
    }

    /**
     * Gets the current filter name.
     *
     * @return The name of the currently selected filter
     */
    public String getCurrentFilterName() {
        int index = currentFilterIndex.get();
        if (index >= 0 && index < filters.length) {
            return filters[index];
        }
        return filters[0];
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

        // Limit to 10 most recent files
        while (recentFiles.size() > 10) {
            recentFiles.remove(recentFiles.size() - 1);
        }
    }

    /**
     * Clears the recent files list.
     */
    public void clearRecentFiles() {
        recentFiles.clear();
    }

    /**
     * Resets the state to default values.
     */
    public void reset() {
        searchText.set("");
        currentFilterIndex.set(0);
        selectedModelInfo = "No model selected";
        recentFiles.clear();
        recentFiles.add("standard_cow.json");
        recentFiles.add("example_model.json");
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
}
