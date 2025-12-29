package com.openmason.main.systems.menus.preferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the state of the unified preferences window, including current page selection.
 */
public class PreferencesState {

    /**
     * Available preference pages organized by tool.
     */
    public enum PreferencePage {
        MODEL_EDITOR("Model Editor"),
        TEXTURE_EDITOR("Texture Editor"),
        KEYBINDS("Keybinds"),
        COMMON("Common");

        private final String displayName;

        PreferencePage(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Listener interface for state changes.
     */
    @FunctionalInterface
    public interface StateChangeListener {
        void onPageChanged(PreferencePage oldPage, PreferencePage newPage);
    }

    private PreferencePage currentPage = PreferencePage.MODEL_EDITOR;
    private final List<StateChangeListener> listeners = new ArrayList<>();

    /**
     * Gets the currently selected page.
     *
     * @return the current page
     */
    public PreferencePage getCurrentPage() {
        return currentPage;
    }

    /**
     * Sets the current page and notifies listeners if changed.
     */
    public void setCurrentPage(PreferencePage newPage) {
        if (newPage == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }

        if (currentPage != newPage) {
            PreferencePage oldPage = currentPage;
            currentPage = newPage;
            notifyListeners(oldPage, newPage);
        }
    }

    /**
     * Notifies all listeners of a page change.
     */
    private void notifyListeners(PreferencePage oldPage, PreferencePage newPage) {
        for (StateChangeListener listener : listeners) {
            listener.onPageChanged(oldPage, newPage);
        }
    }

    /**
     * Resets the state to default values.
     */
    public void reset() {
        setCurrentPage(PreferencePage.MODEL_EDITOR);
    }
}
