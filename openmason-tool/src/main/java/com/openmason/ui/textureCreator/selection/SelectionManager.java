package com.openmason.ui.textureCreator.selection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized manager for selection state in the texture creator.
 * Provides single source of truth for selection and coordinates updates across components.
 */
public class SelectionManager {

    private static final Logger logger = LoggerFactory.getLogger(SelectionManager.class);

    /**
     * Listener interface for selection change events.
     */
    public interface SelectionChangeListener {
        /**
         * Called when the selection changes.
         * @param oldSelection The previous selection (may be null)
         * @param newSelection The new selection (may be null)
         */
        void onSelectionChanged(SelectionRegion oldSelection, SelectionRegion newSelection);
    }

    // Current active selection (null if no selection)
    private SelectionRegion activeSelection;

    // Selection change listeners
    private final List<SelectionChangeListener> listeners;

    /**
     * Creates a new SelectionManager with no active selection.
     */
    public SelectionManager() {
        this.activeSelection = null;
        this.listeners = new ArrayList<>();
    }

    /**
     * Gets the current active selection.
     * @return The active selection, or null if no selection exists
     */
    public SelectionRegion getActiveSelection() {
        return activeSelection;
    }

    /**
     * Sets the active selection and notifies listeners.
     * @param selection The new selection (null to clear selection)
     */
    public void setActiveSelection(SelectionRegion selection) {
        SelectionRegion oldSelection = this.activeSelection;
        this.activeSelection = selection;

        if (hasSelectionChanged(oldSelection, selection)) {
            logger.debug("Selection changed: {} -> {}",
                    oldSelection != null ? oldSelection.getBounds() : "null",
                    selection != null ? selection.getBounds() : "null");
            notifyListeners(oldSelection, selection);
        }
    }

    /**
     * Clears the active selection.
     */
    public void clearSelection() {
        setActiveSelection(null);
    }

    /**
     * Checks if there is an active selection.
     * @return true if there is an active non-empty selection
     */
    public boolean hasActiveSelection() {
        return activeSelection != null && !activeSelection.isEmpty();
    }

    /**
     * Adds a selection change listener.
     * @param listener The listener to add
     */
    public void addSelectionChangeListener(SelectionChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a selection change listener.
     * @param listener The listener to remove
     */
    public void removeSelectionChangeListener(SelectionChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies all listeners of a selection change.
     * @param oldSelection The previous selection
     * @param newSelection The new selection
     */
    private void notifyListeners(SelectionRegion oldSelection, SelectionRegion newSelection) {
        // Create a copy of the listeners list to avoid ConcurrentModificationException
        List<SelectionChangeListener> listenersCopy = new ArrayList<>(listeners);

        for (SelectionChangeListener listener : listenersCopy) {
            try {
                listener.onSelectionChanged(oldSelection, newSelection);
            } catch (Exception e) {
                logger.error("Error notifying selection change listener", e);
            }
        }
    }

    /**
     * Checks if the selection has actually changed.
     * Handles null comparisons and empty selections.
     */
    private boolean hasSelectionChanged(SelectionRegion oldSelection, SelectionRegion newSelection) {
        // Both null - no change
        if (oldSelection == null && newSelection == null) {
            return false;
        }

        // One null, one not - changed
        if (oldSelection == null || newSelection == null) {
            return true;
        }

        if (oldSelection.getType() != newSelection.getType()) {
            return true;
        }

        if (!oldSelection.getBounds().equals(newSelection.getBounds())) {
            return true;
        }

        return !oldSelection.equals(newSelection);
    }
}
