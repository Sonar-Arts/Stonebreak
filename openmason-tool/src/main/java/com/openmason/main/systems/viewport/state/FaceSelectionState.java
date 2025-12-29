package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages face selection state for the viewport system.
 * Supports multi-selection: tracks multiple selected faces with their vertex positions.
 * Follows the same pattern as EdgeSelectionState (SOLID, DRY).
 * Thread-safe state management with dirty tracking.
 *
 * <p>Supports both quad mode (4 vertices) and triangle mode (variable vertices after subdivision).
 */
public class FaceSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(FaceSelectionState.class);

    // Multi-selection state (LinkedHashSet preserves insertion order)
    private final Set<Integer> selectedFaceIndices = new LinkedHashSet<>();

    // Per-face data: vertex indices and positions
    private final Map<Integer, int[]> faceVertexIndices = new HashMap<>();
    private final Map<Integer, Vector3f[]> originalVertices = new HashMap<>();
    private final Map<Integer, Vector3f[]> currentVertices = new HashMap<>();

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
     * Select a face by index (quad mode with 4 vertices), replacing any existing selection.
     *
     * @param faceIndex the index of the face to select
     * @param vertices  array of vertex positions (quad corners) in model space
     * @throws IllegalArgumentException if faceIndex is negative or vertices array invalid
     */
    public synchronized void selectFace(int faceIndex, Vector3f[] vertices) {
        selectFace(faceIndex, vertices, null);
    }

    /**
     * Select a face by index with vertex indices, replacing any existing selection.
     *
     * @param faceIndex the index of the face to select
     * @param vertices  array of vertex positions in model space (variable count)
     * @param indices   array of mesh vertex indices (parallel to vertices), can be null
     * @throws IllegalArgumentException if faceIndex is negative or vertices array invalid
     */
    public synchronized void selectFace(int faceIndex, Vector3f[] vertices, int[] indices) {
        validateFaceInput(faceIndex, vertices, indices);

        clearSelection();
        addFaceToSelection(faceIndex, vertices, indices);

        logger.debug("Face {} selected with {} vertices", faceIndex, vertices.length);
    }

    /**
     * Toggle a face in the selection (add if not selected, remove if selected).
     * Use this for Shift+click multi-selection.
     *
     * @param faceIndex the index of the face to toggle
     * @param vertices  array of vertex positions in model space
     * @param indices   array of mesh vertex indices (can be null)
     */
    public synchronized void toggleFace(int faceIndex, Vector3f[] vertices, int[] indices) {
        validateFaceInput(faceIndex, vertices, indices);

        if (selectedFaceIndices.contains(faceIndex)) {
            // Remove from selection
            selectedFaceIndices.remove(faceIndex);
            faceVertexIndices.remove(faceIndex);
            originalVertices.remove(faceIndex);
            currentVertices.remove(faceIndex);
            logger.debug("Face {} removed from selection (now {} selected)",
                    faceIndex, selectedFaceIndices.size());
        } else {
            // Add to selection
            addFaceToSelection(faceIndex, vertices, indices);
            logger.debug("Face {} added to selection (now {} selected)",
                    faceIndex, selectedFaceIndices.size());
        }
    }

    private void validateFaceInput(int faceIndex, Vector3f[] vertices, int[] indices) {
        if (faceIndex < 0) {
            throw new IllegalArgumentException("Face index cannot be negative: " + faceIndex);
        }
        if (vertices == null || vertices.length < 3) {
            throw new IllegalArgumentException("Vertices array must contain at least 3 vertices");
        }
        for (int i = 0; i < vertices.length; i++) {
            if (vertices[i] == null) {
                throw new IllegalArgumentException("Vertex " + i + " cannot be null");
            }
        }
        if (indices != null && indices.length != vertices.length) {
            throw new IllegalArgumentException("Indices array length must match vertices length");
        }
    }

    private void addFaceToSelection(int faceIndex, Vector3f[] vertices, int[] indices) {
        selectedFaceIndices.add(faceIndex);
        faceVertexIndices.put(faceIndex, indices != null ? indices.clone() : null);

        // Deep copy vertices
        Vector3f[] origCopy = new Vector3f[vertices.length];
        Vector3f[] currCopy = new Vector3f[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            origCopy[i] = new Vector3f(vertices[i]);
            currCopy[i] = new Vector3f(vertices[i]);
        }
        originalVertices.put(faceIndex, origCopy);
        currentVertices.put(faceIndex, currCopy);
    }

    /**
     * Check if a specific face is in the current selection.
     *
     * @param faceIndex the face index to check
     * @return true if the face is selected, false otherwise
     */
    public synchronized boolean isSelected(int faceIndex) {
        return selectedFaceIndices.contains(faceIndex);
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (!selectedFaceIndices.isEmpty()) {
            logger.debug("Clearing face selection (was {} faces)", selectedFaceIndices.size());
        }

        selectedFaceIndices.clear();
        faceVertexIndices.clear();
        originalVertices.clear();
        currentVertices.clear();
        isDragging = false;
        isModified = false;
        planeNormal = null;
        planePoint = null;
    }

    /**
     * Start dragging all selected faces.
     *
     * @param planeNormal the normal vector of the working plane
     * @param planePoint  a point on the working plane (usually the first face's centroid)
     * @throws IllegalStateException if no face is selected
     */
    public synchronized void startDrag(Vector3f planeNormal, Vector3f planePoint) {
        if (selectedFaceIndices.isEmpty()) {
            throw new IllegalStateException("Cannot start drag: no face selected");
        }
        if (planeNormal == null || planePoint == null) {
            throw new IllegalArgumentException("Plane normal and point cannot be null");
        }

        isDragging = true;
        this.planeNormal = new Vector3f(planeNormal).normalize();
        this.planePoint = new Vector3f(planePoint);

        logger.debug("Started dragging {} faces on plane with normal ({}, {}, {})",
                selectedFaceIndices.size(),
                String.format("%.2f", this.planeNormal.x),
                String.format("%.2f", this.planeNormal.y),
                String.format("%.2f", this.planeNormal.z));
    }

    /**
     * Update all selected face positions during drag by applying a translation delta.
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

        // Apply delta to all selected faces
        for (Integer faceIndex : selectedFaceIndices) {
            Vector3f[] original = originalVertices.get(faceIndex);
            if (original != null) {
                Vector3f[] updated = new Vector3f[original.length];
                for (int i = 0; i < original.length; i++) {
                    updated[i] = new Vector3f(original[i]).add(delta);
                }
                currentVertices.put(faceIndex, updated);
            }
        }

        // Check if position has changed from original
        float threshold = 0.001f;
        isModified = (delta.lengthSquared() > threshold * threshold);

        logger.trace("Updated {} face positions by delta ({}, {}, {}), modified={}",
                selectedFaceIndices.size(),
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
            isDragging = false;
            logger.debug("Ended drag for {} faces, modified={}",
                    selectedFaceIndices.size(), isModified);
        }
    }

    /**
     * Cancel the drag operation, reverting all faces to original positions.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            for (Integer faceIndex : selectedFaceIndices) {
                Vector3f[] original = originalVertices.get(faceIndex);
                if (original != null) {
                    Vector3f[] reverted = new Vector3f[original.length];
                    for (int i = 0; i < original.length; i++) {
                        reverted[i] = new Vector3f(original[i]);
                    }
                    currentVertices.put(faceIndex, reverted);
                }
            }
            isDragging = false;
            isModified = false;
            logger.debug("Cancelled drag for {} faces, reverted to original positions",
                    selectedFaceIndices.size());
        }
    }

    /**
     * Revert all current positions to original positions.
     */
    public synchronized void revertToOriginal() {
        if (!selectedFaceIndices.isEmpty()) {
            for (Integer faceIndex : selectedFaceIndices) {
                Vector3f[] original = originalVertices.get(faceIndex);
                if (original != null) {
                    Vector3f[] reverted = new Vector3f[original.length];
                    for (int i = 0; i < original.length; i++) {
                        reverted[i] = new Vector3f(original[i]);
                    }
                    currentVertices.put(faceIndex, reverted);
                }
            }
            isModified = false;
            logger.debug("Reverted {} faces to original positions", selectedFaceIndices.size());
        }
    }

    /**
     * Get the centroid of the first selected face.
     *
     * @return the centroid, or null if no selection
     */
    public synchronized Vector3f getCentroid() {
        if (selectedFaceIndices.isEmpty()) {
            return null;
        }
        Integer firstIndex = selectedFaceIndices.iterator().next();
        Vector3f[] current = currentVertices.get(firstIndex);
        if (current == null || current.length == 0) {
            return null;
        }

        Vector3f centroid = new Vector3f();
        for (Vector3f vertex : current) {
            centroid.add(vertex);
        }
        return centroid.mul(1.0f / current.length);
    }

    // Getters

    /**
     * Check if any face is currently selected.
     *
     * @return true if at least one face is selected, false otherwise
     */
    public synchronized boolean hasSelection() {
        return !selectedFaceIndices.isEmpty();
    }

    /**
     * Get the number of selected faces.
     *
     * @return the count of selected faces
     */
    public synchronized int getSelectionCount() {
        return selectedFaceIndices.size();
    }

    /**
     * Get all selected face indices.
     *
     * @return a copy of the selected face indices set
     */
    public synchronized Set<Integer> getSelectedFaceIndices() {
        return new LinkedHashSet<>(selectedFaceIndices);
    }

    /**
     * Get the first selected face index (for backward compatibility).
     *
     * @return the first face index, or -1 if no selection
     */
    public synchronized int getSelectedFaceIndex() {
        return selectedFaceIndices.isEmpty() ? -1 : selectedFaceIndices.iterator().next();
    }

    /**
     * Get the vertex indices for a specific face.
     *
     * @param faceIndex the face index
     * @return array of vertex indices, or null if not in selection
     */
    public synchronized int[] getFaceVertexIndices(int faceIndex) {
        int[] indices = faceVertexIndices.get(faceIndex);
        return indices != null ? indices.clone() : null;
    }

    /**
     * Get the mesh vertex indices of the first selected face (for backward compatibility).
     *
     * @return array of vertex indices, or null if not available
     */
    public synchronized int[] getVertexIndices() {
        if (selectedFaceIndices.isEmpty()) return null;
        Integer firstIndex = selectedFaceIndices.iterator().next();
        int[] indices = faceVertexIndices.get(firstIndex);
        return indices != null ? indices.clone() : null;
    }

    /**
     * Get the original vertices for a specific face.
     *
     * @param faceIndex the face index
     * @return array of original vertex positions, or null if not in selection
     */
    public synchronized Vector3f[] getOriginalVertices(int faceIndex) {
        Vector3f[] verts = originalVertices.get(faceIndex);
        if (verts == null) return null;
        Vector3f[] result = new Vector3f[verts.length];
        for (int i = 0; i < verts.length; i++) {
            result[i] = new Vector3f(verts[i]);
        }
        return result;
    }

    /**
     * Get the original vertices of the first selected face (for backward compatibility).
     *
     * @return array of original vertex positions, or null if no selection
     */
    public synchronized Vector3f[] getOriginalVertices() {
        if (selectedFaceIndices.isEmpty()) return null;
        Integer firstIndex = selectedFaceIndices.iterator().next();
        return getOriginalVertices(firstIndex);
    }

    /**
     * Get the current vertices for a specific face.
     *
     * @param faceIndex the face index
     * @return array of current vertex positions, or null if not in selection
     */
    public synchronized Vector3f[] getCurrentVertices(int faceIndex) {
        Vector3f[] verts = currentVertices.get(faceIndex);
        if (verts == null) return null;
        Vector3f[] result = new Vector3f[verts.length];
        for (int i = 0; i < verts.length; i++) {
            result[i] = new Vector3f(verts[i]);
        }
        return result;
    }

    /**
     * Get the current vertices of the first selected face (for backward compatibility).
     *
     * @return array of current vertex positions, or null if no selection
     */
    public synchronized Vector3f[] getCurrentVertices() {
        if (selectedFaceIndices.isEmpty()) return null;
        Integer firstIndex = selectedFaceIndices.iterator().next();
        return getCurrentVertices(firstIndex);
    }

    /**
     * Get the number of vertices in the first selected face.
     *
     * @return vertex count, or 0 if no selection
     */
    public synchronized int getVertexCount() {
        if (selectedFaceIndices.isEmpty()) return 0;
        Integer firstIndex = selectedFaceIndices.iterator().next();
        Vector3f[] verts = currentVertices.get(firstIndex);
        return verts != null ? verts.length : 0;
    }

    /**
     * Get all unique vertex indices across all selected faces.
     *
     * @return set of unique vertex indices
     */
    public synchronized Set<Integer> getAllSelectedVertexIndices() {
        Set<Integer> allVertices = new HashSet<>();
        for (Integer faceIndex : selectedFaceIndices) {
            int[] indices = faceVertexIndices.get(faceIndex);
            if (indices != null) {
                for (int idx : indices) {
                    if (idx >= 0) {
                        allVertices.add(idx);
                    }
                }
            }
        }
        return allVertices;
    }

    /**
     * Check if currently dragging faces.
     *
     * @return true if dragging, false otherwise
     */
    public synchronized boolean isDragging() {
        return isDragging;
    }

    /**
     * Check if any selected face has been modified.
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
        if (selectedFaceIndices.isEmpty()) {
            return "FaceSelectionState{no selection}";
        }
        return String.format("FaceSelectionState{count=%d, indices=%s, dragging=%s, modified=%s}",
                selectedFaceIndices.size(),
                selectedFaceIndices,
                isDragging, isModified);
    }
}
