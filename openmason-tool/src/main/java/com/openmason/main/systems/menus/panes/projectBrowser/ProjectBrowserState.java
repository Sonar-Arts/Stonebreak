package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortBy;
import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortOrder;
import com.openmason.main.systems.menus.panes.projectBrowser.views.ViewMode;
import imgui.type.ImString;

/**
 * View state for the Project Browser: search text, view mode, sorting and the
 * selection status line. Navigation/sidebar state from the old Model Browser
 * is gone — the browser is rooted at the single open project folder.
 */
public class ProjectBrowserState {

    private static final int MAX_SEARCH_TEXT_LENGTH = 256;

    private final ImString searchText = new ImString("", MAX_SEARCH_TEXT_LENGTH);

    private ViewMode viewMode = ViewMode.GRID;
    private SortBy sortBy = SortBy.NAME;
    private SortOrder sortOrder = SortOrder.ASCENDING;

    private String selectedAssetInfo = "No asset selected";

    public ImString getSearchText() {
        return searchText;
    }

    public String getSearchTextValue() {
        return searchText.get();
    }

    public void setSearchTextValue(String text) {
        searchText.set(text != null ? text : "");
    }

    public boolean isSearchActive() {
        String text = searchText.get();
        return text != null && !text.trim().isEmpty();
    }

    /** True when {@code text} matches the active search (case-insensitive contains). */
    public boolean matchesSearch(String text) {
        if (!isSearchActive()) {
            return true;
        }
        return text.toLowerCase().contains(searchText.get().toLowerCase());
    }

    public ViewMode getViewMode() {
        return viewMode;
    }

    public void setViewMode(ViewMode viewMode) {
        if (viewMode != null) {
            this.viewMode = viewMode;
        }
    }

    public SortBy getSortBy() {
        return sortBy;
    }

    public void setSortBy(SortBy sortBy) {
        if (sortBy != null) {
            this.sortBy = sortBy;
        }
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(SortOrder sortOrder) {
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    public String getSelectedAssetInfo() {
        return selectedAssetInfo;
    }

    public void setSelectedAssetInfo(String info) {
        this.selectedAssetInfo = info != null ? info : "No asset selected";
    }

    public void reset() {
        searchText.set("");
        selectedAssetInfo = "No asset selected";
    }
}
