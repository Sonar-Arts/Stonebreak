package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages vertex selection state for the viewport system.
 * Supports multi-selection: tracks multiple selected vertices with their original and current positions.
 * Thread-safe state management with dirty tracking.
 */
public class VertexSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(VertexSelectionState.class);

    // Multi-selection state (LinkedHashSet preserves insertion order)
    private final Set<Integer> selectedVertexIndices = new LinkedHashSet<>();
    private final Map<Integer, Vector3f> originalPositions = new HashMap<>();
    private final Map<Integer, Vector3f> currentPositions = new HashMap<>();

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
     * Select a vertex by index, replacing any existing selection.
     * Use this for normal click (no Shift).
     *
     * @param vertexIndex the index of the vertex to select
     * @param position    the world-space position of the vertex
     * @throws IllegalArgumentException if vertexIndex is negative or position is null
     */
    public synchronized void selectVertex(int vertexIndex, Vector3f position) {
        if (vertexIndex < 0) {
            throw new IllegalArgumentException("Vertex index cannot be negative: " + vertexIndex);
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }

        // Clear existing selection and select only this vertex
        clearSelection();
        selectedVertexIndices.add(vertexIndex);
        originalPositions.put(vertexIndex, new Vector3f(position));
        currentPositions.put(vertexIndex, new Vector3f(position));

        logger.debug("Vertex {} selected at position ({}, {}, {})",
                vertexIndex,
                String.format("%.2f", position.x),
                String.format("%.2f", position.y),
                String.format("%.2f", position.z));
    }

    /**
     * Toggle a vertex in the selection (add if not selected, remove if selected).
     * Use this for Shift+click multi-selection.
     *
     * @param vertexIndex the index of the vertex to toggle
     * @param position    the world-space position of the vertex (only used if adding)
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
            // Remove from selection
            selectedVertexIndices.remove(vertexIndex);
            originalPositions.remove(vertexIndex);
            currentPositions.remove(vertexIndex);
            logger.debug("Vertex {} removed from selection (now {} selected)",
                    vertexIndex, selectedVertexIndices.size());
        } else {
            // Add to selection
            selectedVertexIndices.add(vertexIndex);
            originalPositions.put(vertexIndex, new Vector3f(position));
            currentPositions.put(vertexIndex, new Vector3f(position));
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
        currentPositions.clear();
        isDragging = false;
        isModified = false;
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
     * Update all selected vertex positions during drag by applying a delta.
     *
     * @param delta the translation delta to apply to all selected vertices
     * @throws IllegalStateException if not currently dragging
     */
    public synchronized void updatePositionsByDelta(Vector3f delta) {
        if (!isDragging) {
            throw new IllegalStateException("Cannot update positions: not currently dragging");
        }
        if (delta == null) {
            throw new IllegalArgumentException("Delta cannot be null");
        }

        // Apply delta to all selected vertices
        for (Integer vertexIndex : selectedVertexIndices) {
            Vector3f original = originalPositions.get(vertexIndex);
            if (original != null) {
                Vector3f newPosition = new Vector3f(original).add(delta);
                currentPositions.put(vertexIndex, newPosition);
            }
        }

        // Check if any position has changed from original
        float threshold = 0.001f;
        isModified = (delta.lengthSquared() > threshold * threshold);

        logger.trace("Updated {} vertex positions by delta ({}, {}, {}), modified={}",
                selectedVertexIndices.size(),
                String.format("%.2f", delta.x),
                String.format("%.2f", delta.y),
                String.format("%.2f", delta.z),
                isModified);
    }

    /**
     * Update the position of a specific vertex during drag.
     * Used for single-vertex backward compatibility.
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

        // For backward compatibility: update first selected vertex
        if (!selectedVertexIndices.isEmpty()) {
            Integer firstIndex = selectedVertexIndices.iterator().next();
            currentPositions.put(firstIndex, new Vector3f(newPosition));

            // Check if position has changed from original
            Vector3f original = originalPositions.get(firstIndex);
            if (original != null) {
                float threshold = 0.001f;
                float distanceSquared = original.distanceSquared(newPosition);
                isModified = (distanceSquared > threshold * threshold);
            }

            logger.trace("Updated vertex {} position to ({}, {}, {}), modified={}",
                    firstIndex,
                    String.format("%.2f", newPosition.x),
                    String.format("%.2f", newPosition.y),
                    String.format("%.2f", newPosition.z),
                    isModified);
        }
    }

    /**
     * End the drag operation, committing the position changes.
     */
    public synchronized void endDrag() {
        if (isDragging) {
            isDragging = false;
            logger.debug("Ended drag for {} vertices, modified={}",
                    selectedVertexIndices.size(), isModified);
        }
    }

    /**
     * Cancel the drag operation, reverting all vertices to original positions.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            // Revert all vertices to original positions
            for (Integer vertexIndex : selectedVertexIndices) {
                Vector3f original = originalPositions.get(vertexIndex);
                if (original != null) {
                    currentPositions.put(vertexIndex, new Vector3f(original));
                }
            }
            isDragging = false;
            isModified = false;
            logger.debug("Cancelled drag for {} vertices, reverted to original positions",
                    selectedVertexIndices.size());
        }
    }

    /**
     * Revert all current positions to original positions.
     */
    public synchronized void revertToOriginal() {
        if (!selectedVertexIndices.isEmpty()) {
            for (Integer vertexIndex : selectedVertexIndices) {
                Vector3f original = originalPositions.get(vertexIndex);
                if (original != null) {
                    currentPositions.put(vertexIndex, new Vector3f(original));
                }
            }
            isModified = false;
            logger.debug("Reverted {} vertices to original positions", selectedVertexIndices.size());
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
     * Get the original position of the first selected vertex (for backward compatibility).
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
     * Get the current position of a specific vertex.
     *
     * @param vertexIndex the vertex index
     * @return copy of current position, or null if not in selection
     */
    public synchronized Vector3f getCurrentPosition(int vertexIndex) {
        Vector3f pos = currentPositions.get(vertexIndex);
        return pos != null ? new Vector3f(pos) : null;
    }

    /**
     * Get the current position of the first selected vertex (for backward compatibility).
     *
     * @return copy of current position, or null if no selection
     */
    public synchronized Vector3f getCurrentPosition() {
        if (selectedVertexIndices.isEmpty()) {
            return null;
        }
        Integer firstIndex = selectedVertexIndices.iterator().next();
        Vector3f pos = currentPositions.get(firstIndex);
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
     * Check if any selected vertex has been modified (position changed from original).
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
        if (selectedVertexIndices.isEmpty()) {
            return "VertexSelectionState{no selection}";
        }
        return String.format("VertexSelectionState{count=%d, indices=%s, dragging=%s, modified=%s}",
                selectedVertexIndices.size(),
                selectedVertexIndices,
                isDragging, isModified);
    }
}
