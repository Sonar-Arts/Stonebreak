package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages vertex selection state for the viewport system.
 * Supports multi-selection: tracks selected vertex indices and their original positions
 * (at the time of selection or last drag commit) for undo/delta calculations.
 *
 * <p>Current positions during a drag live in the VertexRenderer (single source of truth).
 * This class only stores originals so that deltas can be computed relative to the drag start.</p>
 *
 * Thread-safe state management.
 */
public class VertexSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(VertexSelectionState.class);

    // Multi-selection state (LinkedHashSet preserves insertion order)
    private final Set<Integer> selectedVertexIndices = new LinkedHashSet<>();
    private final Map<Integer, Vector3f> originalPositions = new HashMap<>();

    private boolean isDragging = false;

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
     * Select a vertex by index, replacing any existing selection.
     * Use this for normal click (no Shift).
     *
     * @param vertexIndex the index of the vertex to select
     * @param position    the model-space position of the vertex
     * @throws IllegalArgumentException if vertexIndex is negative or position is null
     */
    public synchronized void selectVertex(int vertexIndex, Vector3f position) {
        if (vertexIndex < 0) {
            throw new IllegalArgumentException("Vertex index cannot be negative: " + vertexIndex);
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }

        clearSelection();
        selectedVertexIndices.add(vertexIndex);
        originalPositions.put(vertexIndex, new Vector3f(position));

        logger.debug("Vertex {} selected at position ({}, {}, {})",
                vertexIndex,
                String.format("%.2f", position.x),
                String.format("%.2f", position.y),
                String.format("%.2f", position.z));
    }

    /**
     * Toggle a vertex in the selection (add if not selected, remove if selected).
     * Use this for multi-selection.
     *
     * @param vertexIndex the index of the vertex to toggle
     * @param position    the model-space position of the vertex (only used if adding)
     * @throws IllegalArgumentException if vertexIndex is negative or position is null
     */
    public synchronized void toggleVertex(int vertexIndex, Vector3f position) {
        if (vertexIndex < 0) {
            throw new IllegalArgumentException("Vertex index cannot be negative: " + vertexIndex);
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }

        if (selectedVertexIndices.contains(vertexIndex)) {
            selectedVertexIndices.remove(vertexIndex);
            originalPositions.remove(vertexIndex);
            logger.debug("Vertex {} removed from selection (now {} selected)",
                    vertexIndex, selectedVertexIndices.size());
        } else {
            selectedVertexIndices.add(vertexIndex);
            originalPositions.put(vertexIndex, new Vector3f(position));
            logger.debug("Vertex {} added to selection at ({}, {}, {}) (now {} selected)",
                    vertexIndex,
                    String.format("%.2f", position.x),
                    String.format("%.2f", position.y),
                    String.format("%.2f", position.z),
                    selectedVertexIndices.size());
        }
    }

    /**
     * Check if a specific vertex is in the current selection.
     *
     * @param vertexIndex the vertex index to check
     * @return true if the vertex is selected, false otherwise
     */
    public synchronized boolean isSelected(int vertexIndex) {
        return selectedVertexIndices.contains(vertexIndex);
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (!selectedVertexIndices.isEmpty()) {
            logger.debug("Clearing selection (was {} vertices)", selectedVertexIndices.size());
        }

        selectedVertexIndices.clear();
        originalPositions.clear();
        isDragging = false;
        planeNormal = null;
        planePoint = null;
    }

    /**
     * Start dragging all selected vertices.
     *
     * @param planeNormal the normal vector of the working plane
     * @param planePoint  a point on the working plane
     * @throws IllegalStateException if no vertex is selected
     */
    public synchronized void startDrag(Vector3f planeNormal, Vector3f planePoint) {
        if (selectedVertexIndices.isEmpty()) {
            throw new IllegalStateException("Cannot start drag: no vertex selected");
        }
        if (planeNormal == null || planePoint == null) {
            throw new IllegalArgumentException("Plane normal and point cannot be null");
        }

        isDragging = true;
        this.planeNormal = new Vector3f(planeNormal).normalize();
        this.planePoint = new Vector3f(planePoint);

        logger.debug("Started dragging {} vertices on plane with normal ({}, {}, {})",
                selectedVertexIndices.size(),
                String.format("%.2f", this.planeNormal.x),
                String.format("%.2f", this.planeNormal.y),
                String.format("%.2f", this.planeNormal.z));
    }

    /**
     * End the drag operation, committing new positions as originals.
     * The committed positions come from the vertex renderer (single source of truth).
     *
     * @param committedPositions map of vertex index to new model-space position
     */
    public synchronized void endDrag(Map<Integer, Vector3f> committedPositions) {
        if (isDragging) {
            for (Map.Entry<Integer, Vector3f> entry : committedPositions.entrySet()) {
                if (selectedVertexIndices.contains(entry.getKey()) && entry.getValue() != null) {
                    originalPositions.put(entry.getKey(), new Vector3f(entry.getValue()));
                }
            }

            isDragging = false;
            logger.debug("Ended drag for {} vertices, positions committed",
                    selectedVertexIndices.size());
        }
    }

    /**
     * Cancel the drag operation.
     * The translation handler is responsible for reverting vertex positions in the renderer
     * using the original positions from this state.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            isDragging = false;
            logger.debug("Cancelled drag for {} vertices", selectedVertexIndices.size());
        }
    }

    // Getters

    /**
     * Check if any vertex is currently selected.
     *
     * @return true if at least one vertex is selected, false otherwise
     */
    public synchronized boolean hasSelection() {
        return !selectedVertexIndices.isEmpty();
    }

    /**
     * Get the number of selected vertices.
     *
     * @return the count of selected vertices
     */
    public synchronized int getSelectionCount() {
        return selectedVertexIndices.size();
    }

    /**
     * Get all selected vertex indices.
     *
     * @return a copy of the selected vertex indices set
     */
    public synchronized Set<Integer> getSelectedVertexIndices() {
        return new LinkedHashSet<>(selectedVertexIndices);
    }

    /**
     * Get the first selected vertex index (for backward compatibility).
     *
     * @return the first vertex index, or -1 if no selection
     */
    public synchronized int getSelectedVertexIndex() {
        return selectedVertexIndices.isEmpty() ? -1 : selectedVertexIndices.iterator().next();
    }

    /**
     * Get the original position of a specific vertex.
     *
     * @param vertexIndex the vertex index
     * @return copy of original position, or null if not in selection
     */
    public synchronized Vector3f getOriginalPosition(int vertexIndex) {
        Vector3f pos = originalPositions.get(vertexIndex);
        return pos != null ? new Vector3f(pos) : null;
    }

    /**
     * Get the original position of the first selected vertex.
     *
     * @return copy of original position, or null if no selection
     */
    public synchronized Vector3f getOriginalPosition() {
        if (selectedVertexIndices.isEmpty()) {
            return null;
        }
        Integer firstIndex = selectedVertexIndices.iterator().next();
        Vector3f pos = originalPositions.get(firstIndex);
        return pos != null ? new Vector3f(pos) : null;
    }

    /**
     * Check if currently dragging vertices.
     *
     * @return true if dragging, false otherwise
     */
    public synchronized boolean isDragging() {
        return isDragging;
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
        if (selectedVertexIndices.isEmpty()) {
            return "VertexSelectionState{no selection}";
        }
        return String.format("VertexSelectionState{count=%d, indices=%s, dragging=%s}",
                selectedVertexIndices.size(),
                selectedVertexIndices,
                isDragging);
    }
}
