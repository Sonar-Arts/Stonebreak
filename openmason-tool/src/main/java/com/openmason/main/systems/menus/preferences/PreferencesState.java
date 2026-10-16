package com.openmason.main.systems.menus.preferences;

import java.util.Objects;

/**
 * Manages the state of the unified preferences window: which page (by contributed page id,
 * #379) is selected.
 */
public class PreferencesState {

    /** The page shown first. */
    public static final String DEFAULT_PAGE = PreferencesPageRenderer.MODEL_EDITOR;

    private String currentPage = DEFAULT_PAGE;

    /** The selected page's id (it may name a page that is not registered; the window falls back). */
    public String getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(String pageId) {
        this.currentPage = Objects.requireNonNull(pageId, "pageId");
    }

    /**
     * Resets the state to default values.
     */
    public void reset() {
        currentPage = DEFAULT_PAGE;
    }
}
