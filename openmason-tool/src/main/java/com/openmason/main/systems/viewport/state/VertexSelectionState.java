package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages vertex selection state for the viewport system.
 * Tracks selected vertex, original position, current position, and working plane for translation.
 * Thread-safe state management with dirty tracking.
 */
public class VertexSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(VertexSelectionState.class);

    // Selection state
    private int selectedVertexIndex = -1;  // -1 means no selection
    private Vector3f originalPosition = null;
    private Vector3f currentPosition = null;
    private boolean isDragging = false;
    private boolean isModified = false;

    // Working plane for plane-constrained translation
    private Vector3f planeNormal = null;
    private Vector3f planePoint = null;

    /**
     * Creates a new VertexSelectionState with no selection.
     */
    public VertexSelectionState() {
        logger.debug("VertexSelectionState initialized");
    }

    /**
     * Select a vertex by index.
     *
     * @param vertexIndex the index of the vertex to select
     * @param position    the world-space position of the vertex
     * @throws IllegalArgumentException if vertexIndex is negative
     */
    public synchronized void selectVertex(int vertexIndex, Vector3f position) {
        if (vertexIndex < 0) {
            throw new IllegalArgumentException("Vertex index cannot be negative: " + vertexIndex);
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }

        this.selectedVertexIndex = vertexIndex;
        this.originalPosition = new Vector3f(position);
        this.currentPosition = new Vector3f(position);
        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;

        logger.debug("Vertex {} selected at position ({}, {}, {})",
                vertexIndex,
                String.format("%.2f", position.x),
                String.format("%.2f", position.y),
                String.format("%.2f", position.z));
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (selectedVertexIndex >= 0) {
            logger.debug("Clearing selection (was vertex {})", selectedVertexIndex);
        }

        this.selectedVertexIndex = -1;
        this.originalPosition = null;
        this.currentPosition = null;
        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;
    }

    /**
     * Start dragging the selected vertex.
     *
     * @param planeNormal the normal vector of the working plane
     * @param planePoint  a point on the working plane (usually the vertex position)
     * @throws IllegalStateException if no vertex is selected
     */
    public synchronized void startDrag(Vector3f planeNormal, Vector3f planePoint) {
        if (selectedVertexIndex < 0) {
            throw new IllegalStateException("Cannot start drag: no vertex selected");
        }
        if (planeNormal == null || planePoint == null) {
            throw new IllegalArgumentException("Plane normal and point cannot be null");
        }

        this.isDragging = true;
        this.planeNormal = new Vector3f(planeNormal).normalize();
        this.planePoint = new Vector3f(planePoint);

        logger.debug("Started dragging vertex {} on plane with normal ({}, {}, {})",
                selectedVertexIndex,
                String.format("%.2f", this.planeNormal.x),
                String.format("%.2f", this.planeNormal.y),
                String.format("%.2f", this.planeNormal.z));
    }

    /**
     * Update the current position during drag.
     *
     * @param newPosition the new world-space position
     * @throws IllegalStateException if not currently dragging
     */
    public synchronized void updatePosition(Vector3f newPosition) {
        if (!isDragging) {
            throw new IllegalStateException("Cannot update position: not currently dragging");
        }
        if (newPosition == null) {
            throw new IllegalArgumentException("New position cannot be null");
        }

        this.currentPosition = new Vector3f(newPosition);

        // Check if position has changed from original
        float threshold = 0.001f;
        float distanceSquared = originalPosition.distanceSquared(currentPosition);
        this.isModified = (distanceSquared > threshold * threshold);

        logger.trace("Updated vertex {} position to ({}, {}, {}), modified={}",
                selectedVertexIndex,
                String.format("%.2f", newPosition.x),
                String.format("%.2f", newPosition.y),
                String.format("%.2f", newPosition.z),
                isModified);
    }

    /**
     * End the drag operation, committing the position change.
     */
    public synchronized void endDrag() {
        if (isDragging) {
            this.isDragging = false;
            logger.debug("Ended drag for vertex {}, final position ({}, {}, {}), modified={}",
                    selectedVertexIndex,
                    String.format("%.2f", currentPosition.x),
                    String.format("%.2f", currentPosition.y),
                    String.format("%.2f", currentPosition.z),
                    isModified);
        }
    }

    /**
     * Cancel the drag operation, reverting to original position.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            this.currentPosition = new Vector3f(originalPosition);
            this.isDragging = false;
            this.isModified = false;
            logger.debug("Cancelled drag for vertex {}, reverted to original position", selectedVertexIndex);
        }
    }

    /**
     * Revert the current position to the original position.
     * Useful after drag is committed but before save.
     */
    public synchronized void revertToOriginal() {
        if (selectedVertexIndex >= 0 && originalPosition != null) {
            this.currentPosition = new Vector3f(originalPosition);
            this.isModified = false;
            logger.debug("Reverted vertex {} to original position", selectedVertexIndex);
        }
    }

    // Getters

    /**
     * Check if a vertex is currently selected.
     *
     * @return true if a vertex is selected, false otherwise
     */
    public synchronized boolean hasSelection() {
        return selectedVertexIndex >= 0;
    }

    /**
     * Get the selected vertex index.
     *
     * @return the vertex index, or -1 if no selection
     */
    public synchronized int getSelectedVertexIndex() {
        return selectedVertexIndex;
    }

    /**
     * Get the original position of the selected vertex.
     *
     * @return copy of original position, or null if no selection
     */
    public synchronized Vector3f getOriginalPosition() {
        return originalPosition != null ? new Vector3f(originalPosition) : null;
    }

    /**
     * Get the current position of the selected vertex.
     *
     * @return copy of current position, or null if no selection
     */
    public synchronized Vector3f getCurrentPosition() {
        return currentPosition != null ? new Vector3f(currentPosition) : null;
    }

    /**
     * Check if currently dragging a vertex.
     *
     * @return true if dragging, false otherwise
     */
    public synchronized boolean isDragging() {
        return isDragging;
    }

    /**
     * Check if the selected vertex has been modified (position changed from original).
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
        if (selectedVertexIndex < 0) {
            return "VertexSelectionState{no selection}";
        }
        return String.format("VertexSelectionState{index=%d, original=(%.2f,%.2f,%.2f), current=(%.2f,%.2f,%.2f), dragging=%s, modified=%s}",
                selectedVertexIndex,
                originalPosition.x, originalPosition.y, originalPosition.z,
                currentPosition.x, currentPosition.y, currentPosition.z,
                isDragging, isModified);
    }
}
