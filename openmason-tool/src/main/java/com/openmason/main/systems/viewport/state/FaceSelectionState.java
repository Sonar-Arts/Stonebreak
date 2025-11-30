package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages face selection state for the viewport system.
 * Tracks selected face, 4 vertex positions (quad corners), and working plane for translation.
 * Follows the same pattern as EdgeSelectionState but with 4 vertices instead of 2 (SOLID, DRY).
 * Thread-safe state management with dirty tracking.
 */
public class FaceSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(FaceSelectionState.class);

    // Selection state
    private int selectedFaceIndex = -1;  // -1 means no selection

    // Face vertices (4 corners of quad face)
    private Vector3f originalVertex1 = null;
    private Vector3f originalVertex2 = null;
    private Vector3f originalVertex3 = null;
    private Vector3f originalVertex4 = null;

    private Vector3f currentVertex1 = null;
    private Vector3f currentVertex2 = null;
    private Vector3f currentVertex3 = null;
    private Vector3f currentVertex4 = null;

    private boolean isDragging = false;
    private boolean isModified = false;

    // Working plane for plane-constrained translation
    private Vector3f planeNormal = null;
    private Vector3f planePoint = null;

    /**
     * Creates a new FaceSelectionState with no selection.
     */
    public FaceSelectionState() {
        logger.debug("FaceSelectionState initialized");
    }

    /**
     * Select a face by index.
     *
     * @param faceIndex the index of the face to select
     * @param vertices  array of 4 vertex positions (quad corners) in model space
     * @throws IllegalArgumentException if faceIndex is negative or vertices array invalid
     */
    public synchronized void selectFace(int faceIndex, Vector3f[] vertices) {
        if (faceIndex < 0) {
            throw new IllegalArgumentException("Face index cannot be negative: " + faceIndex);
        }
        if (vertices == null || vertices.length != 4) {
            throw new IllegalArgumentException("Vertices array must contain exactly 4 vertices, got: " +
                    (vertices == null ? "null" : vertices.length));
        }
        for (int i = 0; i < 4; i++) {
            if (vertices[i] == null) {
                throw new IllegalArgumentException("Vertex " + i + " cannot be null");
            }
        }

        this.selectedFaceIndex = faceIndex;

        // Deep copy all 4 vertices to original positions
        this.originalVertex1 = new Vector3f(vertices[0]);
        this.originalVertex2 = new Vector3f(vertices[1]);
        this.originalVertex3 = new Vector3f(vertices[2]);
        this.originalVertex4 = new Vector3f(vertices[3]);

        // Initialize current positions to original
        this.currentVertex1 = new Vector3f(vertices[0]);
        this.currentVertex2 = new Vector3f(vertices[1]);
        this.currentVertex3 = new Vector3f(vertices[2]);
        this.currentVertex4 = new Vector3f(vertices[3]);

        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;

        logger.debug("Face {} selected with 4 vertices", faceIndex);
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (selectedFaceIndex >= 0) {
            logger.debug("Clearing face selection (was face {})", selectedFaceIndex);
        }

        this.selectedFaceIndex = -1;
        this.originalVertex1 = null;
        this.originalVertex2 = null;
        this.originalVertex3 = null;
        this.originalVertex4 = null;
        this.currentVertex1 = null;
        this.currentVertex2 = null;
        this.currentVertex3 = null;
        this.currentVertex4 = null;
        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;
    }

    /**
     * Start dragging the selected face.
     *
     * @param planeNormal the normal vector of the working plane
     * @param planePoint  a point on the working plane (usually the face centroid)
     * @throws IllegalStateException if no face is selected
     */
    public synchronized void startDrag(Vector3f planeNormal, Vector3f planePoint) {
        if (selectedFaceIndex < 0) {
            throw new IllegalStateException("Cannot start drag: no face selected");
        }
        if (planeNormal == null || planePoint == null) {
            throw new IllegalArgumentException("Plane normal and point cannot be null");
        }

        this.isDragging = true;
        this.planeNormal = new Vector3f(planeNormal).normalize();
        this.planePoint = new Vector3f(planePoint);

        logger.debug("Started dragging face {} on plane with normal ({}, {}, {})",
                selectedFaceIndex,
                String.format("%.2f", this.planeNormal.x),
                String.format("%.2f", this.planeNormal.y),
                String.format("%.2f", this.planeNormal.z));
    }

    /**
     * Update the face position during drag by applying a translation delta.
     * All 4 vertices move together by the same amount.
     *
     * @param delta the translation delta to apply to all 4 vertices
     * @throws IllegalStateException if not currently dragging
     */
    public synchronized void updatePosition(Vector3f delta) {
        if (!isDragging) {
            throw new IllegalStateException("Cannot update position: not currently dragging");
        }
        if (delta == null) {
            throw new IllegalArgumentException("Delta cannot be null");
        }

        // Apply delta to all 4 vertices
        this.currentVertex1 = new Vector3f(originalVertex1).add(delta);
        this.currentVertex2 = new Vector3f(originalVertex2).add(delta);
        this.currentVertex3 = new Vector3f(originalVertex3).add(delta);
        this.currentVertex4 = new Vector3f(originalVertex4).add(delta);

        // Check if position has changed from original
        float threshold = 0.001f;
        this.isModified = (delta.lengthSquared() > threshold * threshold);

        logger.trace("Updated face {} position by delta ({}, {}, {}), modified={}",
                selectedFaceIndex,
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
            logger.debug("Ended drag for face {}, modified={}",
                    selectedFaceIndex, isModified);
        }
    }

    /**
     * Cancel the drag operation, reverting to original positions.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            this.currentVertex1 = new Vector3f(originalVertex1);
            this.currentVertex2 = new Vector3f(originalVertex2);
            this.currentVertex3 = new Vector3f(originalVertex3);
            this.currentVertex4 = new Vector3f(originalVertex4);
            this.isDragging = false;
            this.isModified = false;
            logger.debug("Cancelled drag for face {}, reverted to original positions", selectedFaceIndex);
        }
    }

    /**
     * Revert the current positions to the original positions.
     */
    public synchronized void revertToOriginal() {
        if (selectedFaceIndex >= 0 && originalVertex1 != null) {
            this.currentVertex1 = new Vector3f(originalVertex1);
            this.currentVertex2 = new Vector3f(originalVertex2);
            this.currentVertex3 = new Vector3f(originalVertex3);
            this.currentVertex4 = new Vector3f(originalVertex4);
            this.isModified = false;
            logger.debug("Reverted face {} to original positions", selectedFaceIndex);
        }
    }

    /**
     * Get the centroid (center point) of the face.
     * Calculated as the average of all 4 vertices.
     * Useful for plane positioning during drag.
     *
     * @return the centroid, or null if no selection
     */
    public synchronized Vector3f getCentroid() {
        if (currentVertex1 == null || currentVertex2 == null ||
            currentVertex3 == null || currentVertex4 == null) {
            return null;
        }

        return new Vector3f(currentVertex1)
                .add(currentVertex2)
                .add(currentVertex3)
                .add(currentVertex4)
                .mul(0.25f);  // Divide by 4
    }

    // Getters

    /**
     * Check if a face is currently selected.
     *
     * @return true if a face is selected, false otherwise
     */
    public synchronized boolean hasSelection() {
        return selectedFaceIndex >= 0;
    }

    /**
     * Get the selected face index.
     *
     * @return the face index, or -1 if no selection
     */
    public synchronized int getSelectedFaceIndex() {
        return selectedFaceIndex;
    }

    /**
     * Get the original vertices (4 corners of quad face).
     *
     * @return array of 4 original vertex positions, or null if no selection
     */
    public synchronized Vector3f[] getOriginalVertices() {
        if (originalVertex1 == null) {
            return null;
        }
        return new Vector3f[] {
            new Vector3f(originalVertex1),
            new Vector3f(originalVertex2),
            new Vector3f(originalVertex3),
            new Vector3f(originalVertex4)
        };
    }

    /**
     * Get the current vertices (4 corners of quad face).
     *
     * @return array of 4 current vertex positions, or null if no selection
     */
    public synchronized Vector3f[] getCurrentVertices() {
        if (currentVertex1 == null) {
            return null;
        }
        return new Vector3f[] {
            new Vector3f(currentVertex1),
            new Vector3f(currentVertex2),
            new Vector3f(currentVertex3),
            new Vector3f(currentVertex4)
        };
    }

    /**
     * Check if currently dragging a face.
     *
     * @return true if dragging, false otherwise
     */
    public synchronized boolean isDragging() {
        return isDragging;
    }

    /**
     * Check if the selected face has been modified (position changed from original).
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
        if (selectedFaceIndex < 0) {
            return "FaceSelectionState{no selection}";
        }
        return String.format("FaceSelectionState{index=%d, dragging=%s, modified=%s}",
                selectedFaceIndex, isDragging, isModified);
    }
}
