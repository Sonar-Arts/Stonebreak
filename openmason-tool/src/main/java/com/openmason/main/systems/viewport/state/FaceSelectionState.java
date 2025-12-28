package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages face selection state for the viewport system.
 * Tracks selected face, vertex positions (variable count for quad/triangle modes), and working plane for translation.
 * Follows the same pattern as EdgeSelectionState (SOLID, DRY).
 * Thread-safe state management with dirty tracking.
 *
 * <p>Supports both quad mode (4 vertices) and triangle mode (variable vertices after subdivision).
 */
public class FaceSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(FaceSelectionState.class);

    // Selection state
    private int selectedFaceIndex = -1;  // -1 means no selection

    // Face vertices (variable count - 4 for quads, more for subdivided faces)
    private List<Vector3f> originalVertices = new ArrayList<>();
    private List<Vector3f> currentVertices = new ArrayList<>();

    // Mesh vertex indices (parallel to originalVertices/currentVertices)
    private int[] vertexIndices = null;

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
     * Select a face by index (quad mode with 4 vertices).
     *
     * @param faceIndex the index of the face to select
     * @param vertices  array of 4 vertex positions (quad corners) in model space
     * @throws IllegalArgumentException if faceIndex is negative or vertices array invalid
     */
    public synchronized void selectFace(int faceIndex, Vector3f[] vertices) {
        selectFace(faceIndex, vertices, null);
    }

    /**
     * Select a face by index with vertex indices (supports variable vertex count).
     * Used in triangle mode where faces may have more than 4 vertices after subdivision.
     *
     * @param faceIndex the index of the face to select
     * @param vertices  array of vertex positions in model space (variable count)
     * @param indices   array of mesh vertex indices (parallel to vertices), can be null for quad mode
     * @throws IllegalArgumentException if faceIndex is negative or vertices array invalid
     */
    public synchronized void selectFace(int faceIndex, Vector3f[] vertices, int[] indices) {
        if (faceIndex < 0) {
            throw new IllegalArgumentException("Face index cannot be negative: " + faceIndex);
        }
        if (vertices == null || vertices.length < 3) {
            throw new IllegalArgumentException("Vertices array must contain at least 3 vertices, got: " +
                    (vertices == null ? "null" : vertices.length));
        }
        for (int i = 0; i < vertices.length; i++) {
            if (vertices[i] == null) {
                throw new IllegalArgumentException("Vertex " + i + " cannot be null");
            }
        }
        if (indices != null && indices.length != vertices.length) {
            throw new IllegalArgumentException("Indices array length must match vertices length");
        }

        this.selectedFaceIndex = faceIndex;
        this.vertexIndices = indices != null ? indices.clone() : null;

        // Deep copy all vertices to original positions
        this.originalVertices.clear();
        this.currentVertices.clear();
        for (Vector3f vertex : vertices) {
            this.originalVertices.add(new Vector3f(vertex));
            this.currentVertices.add(new Vector3f(vertex));
        }

        this.isDragging = false;
        this.isModified = false;
        this.planeNormal = null;
        this.planePoint = null;

        logger.debug("Face {} selected with {} vertices", faceIndex, vertices.length);
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (selectedFaceIndex >= 0) {
            logger.debug("Clearing face selection (was face {})", selectedFaceIndex);
        }

        this.selectedFaceIndex = -1;
        this.originalVertices.clear();
        this.currentVertices.clear();
        this.vertexIndices = null;
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
     * All vertices move together by the same amount.
     *
     * @param delta the translation delta to apply to all vertices
     * @throws IllegalStateException if not currently dragging
     */
    public synchronized void updatePosition(Vector3f delta) {
        if (!isDragging) {
            throw new IllegalStateException("Cannot update position: not currently dragging");
        }
        if (delta == null) {
            throw new IllegalArgumentException("Delta cannot be null");
        }

        // Apply delta to all vertices
        currentVertices.clear();
        for (Vector3f original : originalVertices) {
            currentVertices.add(new Vector3f(original).add(delta));
        }

        // Check if position has changed from original
        float threshold = 0.001f;
        this.isModified = (delta.lengthSquared() > threshold * threshold);

        logger.trace("Updated face {} position by delta ({}, {}, {}), modified={}, vertices={}",
                selectedFaceIndex,
                String.format("%.2f", delta.x),
                String.format("%.2f", delta.y),
                String.format("%.2f", delta.z),
                isModified,
                currentVertices.size());
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
            currentVertices.clear();
            for (Vector3f original : originalVertices) {
                currentVertices.add(new Vector3f(original));
            }
            this.isDragging = false;
            this.isModified = false;
            logger.debug("Cancelled drag for face {}, reverted to original positions", selectedFaceIndex);
        }
    }

    /**
     * Revert the current positions to the original positions.
     */
    public synchronized void revertToOriginal() {
        if (selectedFaceIndex >= 0 && !originalVertices.isEmpty()) {
            currentVertices.clear();
            for (Vector3f original : originalVertices) {
                currentVertices.add(new Vector3f(original));
            }
            this.isModified = false;
            logger.debug("Reverted face {} to original positions", selectedFaceIndex);
        }
    }

    /**
     * Get the centroid (center point) of the face.
     * Calculated as the average of all vertices.
     * Useful for plane positioning during drag.
     *
     * @return the centroid, or null if no selection
     */
    public synchronized Vector3f getCentroid() {
        if (currentVertices.isEmpty()) {
            return null;
        }

        Vector3f centroid = new Vector3f();
        for (Vector3f vertex : currentVertices) {
            centroid.add(vertex);
        }
        return centroid.mul(1.0f / currentVertices.size());
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
     * Get the original vertices.
     *
     * @return array of original vertex positions (deep copied), or null if no selection
     */
    public synchronized Vector3f[] getOriginalVertices() {
        if (originalVertices.isEmpty()) {
            return null;
        }
        Vector3f[] result = new Vector3f[originalVertices.size()];
        for (int i = 0; i < originalVertices.size(); i++) {
            result[i] = new Vector3f(originalVertices.get(i));
        }
        return result;
    }

    /**
     * Get the current vertices.
     *
     * @return array of current vertex positions (deep copied), or null if no selection
     */
    public synchronized Vector3f[] getCurrentVertices() {
        if (currentVertices.isEmpty()) {
            return null;
        }
        Vector3f[] result = new Vector3f[currentVertices.size()];
        for (int i = 0; i < currentVertices.size(); i++) {
            result[i] = new Vector3f(currentVertices.get(i));
        }
        return result;
    }

    /**
     * Get the mesh vertex indices.
     * Used in triangle mode for updating GenericModelRenderer.
     *
     * @return array of vertex indices, or null if not available
     */
    public synchronized int[] getVertexIndices() {
        return vertexIndices != null ? vertexIndices.clone() : null;
    }

    /**
     * Get the number of vertices in this face.
     *
     * @return vertex count, or 0 if no selection
     */
    public synchronized int getVertexCount() {
        return currentVertices.size();
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
