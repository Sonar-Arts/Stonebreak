package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages edge selection state for the viewport system.
 * Tracks selected edge, original endpoint positions, current positions, and working plane for translation.
 * Follows the same pattern as VertexSelectionState (SOLID, DRY).
 * Thread-safe state management with dirty tracking.
 */
public class EdgeSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(EdgeSelectionState.class);

    // Selection state
    private int selectedEdgeIndex = -1;  // -1 means no selection

    // Edge endpoints (2 vertices)
    private Vector3f originalPoint1 = null;
    private Vector3f originalPoint2 = null;
    private Vector3f currentPoint1 = null;
    private Vector3f currentPoint2 = null;

    private boolean isDragging = false;
    private boolean isModified = false;

    // Working plane for plane-constrained translation
    private Vector3f planeNormal = null;
    private Vector3f planePoint = null;

    /**
     * Creates a new EdgeSelectionState with no selection.
     */
    public EdgeSelectionState() {
        logger.debug("EdgeSelectionState initialized");
    }

    /**
     * Select an edge by index.
     *
     * @param edgeIndex the index of the edge to select
     * @param point1    the world-space position of the first endpoint
     * @param point2    the world-space position of the second endpoint
     * @throws IllegalArgumentException if edgeIndex is negative or positions are null
     */
    public synchronized void selectEdge(int edgeIndex, Vector3f point1, Vector3f point2) {
        if (edgeIndex < 0) {
            throw new IllegalArgumentException("Edge index cannot be negative: " + edgeIndex);
        }
        if (point1 == null || point2 == null) {
            throw new IllegalArgumentException("Edge endpoint positions cannot be null");
        }

        this.selectedEdgeIndex = edgeIndex;
        this.originalPoint1 = new Vector3f(point1);
        this.originalPoint2 = new Vector3f(point2);
        this.currentPoint1 = new Vector3f(point1);
        this.currentPoint2 = new Vector3f(point2);
        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;

        logger.debug("Edge {} selected with endpoints ({}, {}, {}) - ({}, {}, {})",
                edgeIndex,
                String.format("%.2f", point1.x), String.format("%.2f", point1.y), String.format("%.2f", point1.z),
                String.format("%.2f", point2.x), String.format("%.2f", point2.y), String.format("%.2f", point2.z));
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (selectedEdgeIndex >= 0) {
            logger.debug("Clearing edge selection (was edge {})", selectedEdgeIndex);
        }

        this.selectedEdgeIndex = -1;
        this.originalPoint1 = null;
        this.originalPoint2 = null;
        this.currentPoint1 = null;
        this.currentPoint2 = null;
        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;
    }

    /**
     * Start dragging the selected edge.
     *
     * @param planeNormal the normal vector of the working plane
     * @param planePoint  a point on the working plane (usually the edge midpoint)
     * @throws IllegalStateException if no edge is selected
     */
    public synchronized void startDrag(Vector3f planeNormal, Vector3f planePoint) {
        if (selectedEdgeIndex < 0) {
            throw new IllegalStateException("Cannot start drag: no edge selected");
        }
        if (planeNormal == null || planePoint == null) {
            throw new IllegalArgumentException("Plane normal and point cannot be null");
        }

        this.isDragging = true;
        this.planeNormal = new Vector3f(planeNormal).normalize();
        this.planePoint = new Vector3f(planePoint);

        logger.debug("Started dragging edge {} on plane with normal ({}, {}, {})",
                selectedEdgeIndex,
                String.format("%.2f", this.planeNormal.x),
                String.format("%.2f", this.planeNormal.y),
                String.format("%.2f", this.planeNormal.z));
    }

    /**
     * Update the edge position during drag by applying a translation delta.
     * Both endpoints move together by the same amount.
     *
     * @param delta the translation delta to apply to both endpoints
     * @throws IllegalStateException if not currently dragging
     */
    public synchronized void updatePosition(Vector3f delta) {
        if (!isDragging) {
            throw new IllegalStateException("Cannot update position: not currently dragging");
        }
        if (delta == null) {
            throw new IllegalArgumentException("Delta cannot be null");
        }

        // Apply delta to both endpoints
        this.currentPoint1 = new Vector3f(originalPoint1).add(delta);
        this.currentPoint2 = new Vector3f(originalPoint2).add(delta);

        // Check if position has changed from original
        float threshold = 0.001f;
        this.isModified = (delta.lengthSquared() > threshold * threshold);

        logger.trace("Updated edge {} position by delta ({}, {}, {}), modified={}",
                selectedEdgeIndex,
                String.format("%.2f", delta.x),
                String.format("%.2f", delta.y),
                String.format("%.2f", delta.z),
                isModified);
    }

    /**
     * End the drag operation, committing the position change.
     */
    public synchronized void endDrag() {
        if (isDragging) {
            this.isDragging = false;
            logger.debug("Ended drag for edge {}, modified={}",
                    selectedEdgeIndex, isModified);
        }
    }

    /**
     * Cancel the drag operation, reverting to original positions.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            this.currentPoint1 = new Vector3f(originalPoint1);
            this.currentPoint2 = new Vector3f(originalPoint2);
            this.isDragging = false;
            this.isModified = false;
            logger.debug("Cancelled drag for edge {}, reverted to original positions", selectedEdgeIndex);
        }
    }

    /**
     * Revert the current positions to the original positions.
     */
    public synchronized void revertToOriginal() {
        if (selectedEdgeIndex >= 0 && originalPoint1 != null && originalPoint2 != null) {
            this.currentPoint1 = new Vector3f(originalPoint1);
            this.currentPoint2 = new Vector3f(originalPoint2);
            this.isModified = false;
            logger.debug("Reverted edge {} to original positions", selectedEdgeIndex);
        }
    }

    /**
     * Get the midpoint of the edge (useful for plane positioning).
     *
     * @return the midpoint, or null if no selection
     */
    public synchronized Vector3f getMidpoint() {
        if (currentPoint1 == null || currentPoint2 == null) {
            return null;
        }
        return new Vector3f(currentPoint1).add(currentPoint2).mul(0.5f);
    }

    // Getters

    /**
     * Check if an edge is currently selected.
     *
     * @return true if an edge is selected, false otherwise
     */
    public synchronized boolean hasSelection() {
        return selectedEdgeIndex >= 0;
    }

    /**
     * Get the selected edge index.
     *
     * @return the edge index, or -1 if no selection
     */
    public synchronized int getSelectedEdgeIndex() {
        return selectedEdgeIndex;
    }

    /**
     * Get the original first endpoint position.
     *
     * @return copy of original point1, or null if no selection
     */
    public synchronized Vector3f getOriginalPoint1() {
        return originalPoint1 != null ? new Vector3f(originalPoint1) : null;
    }

    /**
     * Get the original second endpoint position.
     *
     * @return copy of original point2, or null if no selection
     */
    public synchronized Vector3f getOriginalPoint2() {
        return originalPoint2 != null ? new Vector3f(originalPoint2) : null;
    }

    /**
     * Get the current first endpoint position.
     *
     * @return copy of current point1, or null if no selection
     */
    public synchronized Vector3f getCurrentPoint1() {
        return currentPoint1 != null ? new Vector3f(currentPoint1) : null;
    }

    /**
     * Get the current second endpoint position.
     *
     * @return copy of current point2, or null if no selection
     */
    public synchronized Vector3f getCurrentPoint2() {
        return currentPoint2 != null ? new Vector3f(currentPoint2) : null;
    }

    /**
     * Check if currently dragging an edge.
     *
     * @return true if dragging, false otherwise
     */
    public synchronized boolean isDragging() {
        return isDragging;
    }

    /**
     * Check if the selected edge has been modified (position changed from original).
     *
     * @return true if modified, false otherwise
     */
    public synchronized boolean isModified() {
        return isModified;
    }

    /**
     * Get the working plane normal.
     *
     * @return copy of plane normal, or null if not dragging
     */
    public synchronized Vector3f getPlaneNormal() {
        return planeNormal != null ? new Vector3f(planeNormal) : null;
    }

    /**
     * Get the working plane point.
     *
     * @return copy of plane point, or null if not dragging
     */
    public synchronized Vector3f getPlanePoint() {
        return planePoint != null ? new Vector3f(planePoint) : null;
    }

    @Override
    public synchronized String toString() {
        if (selectedEdgeIndex < 0) {
            return "EdgeSelectionState{no selection}";
        }
        return String.format("EdgeSelectionState{index=%d, dragging=%s, modified=%s}",
                selectedEdgeIndex, isDragging, isModified);
    }
}
