package com.openmason.main.systems.rendering.model.gmr.subrenders.edge.operations;

import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages edge selection state and operations.
 * Single Responsibility: Handles edge selection validation and state updates.
 *
 * <p>This class encapsulates the logic for:
 * <ul>
 *   <li>Validating edge selection indices against valid ranges</li>
 *   <li>Managing selection state transitions with change detection</li>
 *   <li>Logging selection changes for debugging and traceability</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is stateless and thread-safe.
 * Selection state is managed externally and passed as parameters.
 *
 * @see EdgeRenderer
 */
public class EdgeSelectionManager {

    private static final Logger logger = LoggerFactory.getLogger(EdgeSelectionManager.class);

    /** Indicates no edge is selected. */
    private static final int NO_SELECTION = -1;

    /**
     * Result of a selection operation.
     * Immutable value object containing the new selection state and change status.
     *
     * <p>This class follows the immutability pattern for thread safety and
     * provides a clear contract for selection operation results.
     */
    public static class SelectionResult {
        private final int selectedIndex;
        private final boolean selectionChanged;

        /**
         * Creates a new selection result.
         *
         * @param selectedIndex the index of the selected edge, or -1 for no selection
         * @param selectionChanged true if the selection state changed from the previous state
         */
        public SelectionResult(int selectedIndex, boolean selectionChanged) {
            this.selectedIndex = selectedIndex;
            this.selectionChanged = selectionChanged;
        }

        /**
         * Returns the index of the selected edge.
         *
         * @return the selected edge index, or -1 if no edge is selected
         */
        public int getSelectedIndex() {
            return selectedIndex;
        }

        /**
         * Returns whether the selection changed from the previous state.
         *
         * @return true if the selection state changed
         */
        public boolean isSelectionChanged() {
            return selectionChanged;
        }

        /**
         * Returns whether an edge is currently selected.
         *
         * @return true if an edge is selected (index != -1)
         */
        public boolean hasSelection() {
            return selectedIndex != NO_SELECTION;
        }
    }

    /**
     * Sets the selected edge by index with validation.
     * Validates the edge index against the valid range and updates selection state.
     *
     * <p>Valid index range: -1 (clear selection) to (edgeCount - 1).
     * Any index outside this range will be rejected with a warning log.
     *
     * @param edgeIndex the index of the edge to select, or -1 to clear selection
     * @param currentSelection the current selected edge index
     * @param edgeCount the total number of edges in the model
     * @return SelectionResult containing the new selection state, or null if index is invalid
     */
    public SelectionResult setSelectedEdge(int edgeIndex, int currentSelection, int edgeCount) {
        // Validate index range
        if (edgeIndex < NO_SELECTION || edgeIndex >= edgeCount) {
            logger.warn("Invalid edge index: {}, valid range is -1 to {}", edgeIndex, edgeCount - 1);
            return null;
        }

        // Check if selection changed
        boolean selectionChanged = (edgeIndex != currentSelection);

        // Log selection change
        if (edgeIndex >= 0) {
            logger.debug("Selected edge {}", edgeIndex);
        } else {
            logger.debug("Cleared edge selection");
        }

        return new SelectionResult(edgeIndex, selectionChanged);
    }

    /**
     * Clears the edge selection.
     * Returns null if no selection exists (already cleared).
     *
     * @param currentSelection the current selected edge index
     * @return SelectionResult with no selection, or null if selection was already cleared
     */
    public SelectionResult clearSelection(int currentSelection) {
        if (currentSelection == NO_SELECTION) {
            return null; // Already cleared
        }

        logger.debug("Cleared edge selection");
        return new SelectionResult(NO_SELECTION, true);
    }
}
