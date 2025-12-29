package com.openmason.main.systems.viewport.state;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages edge selection state for the viewport system.
 * Supports multi-selection: tracks multiple selected edges with their endpoint positions and vertex indices.
 * Thread-safe state management with dirty tracking.
 */
public class EdgeSelectionState {

    private static final Logger logger = LoggerFactory.getLogger(EdgeSelectionState.class);

    // Multi-selection state (LinkedHashSet preserves insertion order)
    private final Set<Integer> selectedEdgeIndices = new LinkedHashSet<>();

    // Per-edge data: vertex indices and endpoint positions
    private final Map<Integer, int[]> edgeVertexIndices = new HashMap<>();  // [v1, v2] per edge
    private final Map<Integer, Vector3f[]> originalEndpoints = new HashMap<>();  // [p1, p2] per edge
    private final Map<Integer, Vector3f[]> currentEndpoints = new HashMap<>();   // [p1, p2] per edge

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
     * Select an edge by index, replacing any existing selection.
     * Use this for normal click (no Shift).
     *
     * @param edgeIndex the index of the edge to select
     * @param point1    the world-space position of the first endpoint
     * @param point2    the world-space position of the second endpoint
     * @param vertIdx1  the unique vertex index for endpoint 1
     * @param vertIdx2  the unique vertex index for endpoint 2
     * @throws IllegalArgumentException if indices are negative or positions are null
     */
    public synchronized void selectEdge(int edgeIndex, Vector3f point1, Vector3f point2, int vertIdx1, int vertIdx2) {
        if (edgeIndex < 0) {
            throw new IllegalArgumentException("Edge index cannot be negative: " + edgeIndex);
        }
        if (point1 == null || point2 == null) {
            throw new IllegalArgumentException("Edge endpoint positions cannot be null");
        }
        if (vertIdx1 < 0 || vertIdx2 < 0) {
            throw new IllegalArgumentException("Vertex indices cannot be negative: " + vertIdx1 + ", " + vertIdx2);
        }

        // Clear existing selection and select only this edge
        clearSelection();
        addEdgeToSelection(edgeIndex, point1, point2, vertIdx1, vertIdx2);

        logger.debug("Edge {} selected (vertices {}, {}) with endpoints ({}, {}, {}) - ({}, {}, {})",
                edgeIndex, vertIdx1, vertIdx2,
                String.format("%.2f", point1.x), String.format("%.2f", point1.y), String.format("%.2f", point1.z),
                String.format("%.2f", point2.x), String.format("%.2f", point2.y), String.format("%.2f", point2.z));
    }

    /**
     * Toggle an edge in the selection (add if not selected, remove if selected).
     * Use this for Shift+click multi-selection.
     *
     * @param edgeIndex the index of the edge to toggle
     * @param point1    the world-space position of the first endpoint
     * @param point2    the world-space position of the second endpoint
     * @param vertIdx1  the unique vertex index for endpoint 1
     * @param vertIdx2  the unique vertex index for endpoint 2
     */
    public synchronized void toggleEdge(int edgeIndex, Vector3f point1, Vector3f point2, int vertIdx1, int vertIdx2) {
        if (edgeIndex < 0) {
            throw new IllegalArgumentException("Edge index cannot be negative: " + edgeIndex);
        }
        if (point1 == null || point2 == null) {
            throw new IllegalArgumentException("Edge endpoint positions cannot be null");
        }
        if (vertIdx1 < 0 || vertIdx2 < 0) {
            throw new IllegalArgumentException("Vertex indices cannot be negative: " + vertIdx1 + ", " + vertIdx2);
        }

        if (selectedEdgeIndices.contains(edgeIndex)) {
            // Remove from selection
            selectedEdgeIndices.remove(edgeIndex);
            edgeVertexIndices.remove(edgeIndex);
            originalEndpoints.remove(edgeIndex);
            currentEndpoints.remove(edgeIndex);
            logger.debug("Edge {} removed from selection (now {} selected)",
                    edgeIndex, selectedEdgeIndices.size());
        } else {
            // Add to selection
            addEdgeToSelection(edgeIndex, point1, point2, vertIdx1, vertIdx2);
            logger.debug("Edge {} added to selection (now {} selected)",
                    edgeIndex, selectedEdgeIndices.size());
        }
    }

    /**
     * Helper to add an edge to selection.
     */
    private void addEdgeToSelection(int edgeIndex, Vector3f point1, Vector3f point2, int vertIdx1, int vertIdx2) {
        selectedEdgeIndices.add(edgeIndex);
        edgeVertexIndices.put(edgeIndex, new int[]{vertIdx1, vertIdx2});
        originalEndpoints.put(edgeIndex, new Vector3f[]{new Vector3f(point1), new Vector3f(point2)});
        currentEndpoints.put(edgeIndex, new Vector3f[]{new Vector3f(point1), new Vector3f(point2)});
    }

    /**
     * Check if a specific edge is in the current selection.
     *
     * @param edgeIndex the edge index to check
     * @return true if the edge is selected, false otherwise
     */
    public synchronized boolean isSelected(int edgeIndex) {
        return selectedEdgeIndices.contains(edgeIndex);
    }

    /**
     * Clear the current selection.
     */
    public synchronized void clearSelection() {
        if (!selectedEdgeIndices.isEmpty()) {
            logger.debug("Clearing edge selection (was {} edges)", selectedEdgeIndices.size());
        }

        selectedEdgeIndices.clear();
        edgeVertexIndices.clear();
        originalEndpoints.clear();
        currentEndpoints.clear();
        isDragging = false;
        isModified = false;
        planeNormal = null;
        planePoint = null;
    }

    /**
     * Start dragging all selected edges.
     *
     * @param planeNormal the normal vector of the working plane
     * @param planePoint  a point on the working plane (usually the edge midpoint)
     * @throws IllegalStateException if no edge is selected
     */
    public synchronized void startDrag(Vector3f planeNormal, Vector3f planePoint) {
        if (selectedEdgeIndices.isEmpty()) {
            throw new IllegalStateException("Cannot start drag: no edge selected");
        }
        if (planeNormal == null || planePoint == null) {
            throw new IllegalArgumentException("Plane normal and point cannot be null");
        }

        isDragging = true;
        this.planeNormal = new Vector3f(planeNormal).normalize();
        this.planePoint = new Vector3f(planePoint);

        logger.debug("Started dragging {} edges on plane with normal ({}, {}, {})",
                selectedEdgeIndices.size(),
                String.format("%.2f", this.planeNormal.x),
                String.format("%.2f", this.planeNormal.y),
                String.format("%.2f", this.planeNormal.z));
    }

    /**
     * Update all selected edge positions during drag by applying a translation delta.
     *
     * @param delta the translation delta to apply to all endpoints
     * @throws IllegalStateException if not currently dragging
     */
    public synchronized void updatePosition(Vector3f delta) {
        if (!isDragging) {
            throw new IllegalStateException("Cannot update position: not currently dragging");
        }
        if (delta == null) {
            throw new IllegalArgumentException("Delta cannot be null");
        }

        // Apply delta to all selected edges
        for (Integer edgeIndex : selectedEdgeIndices) {
            Vector3f[] original = originalEndpoints.get(edgeIndex);
            if (original != null && original.length == 2) {
                Vector3f newPoint1 = new Vector3f(original[0]).add(delta);
                Vector3f newPoint2 = new Vector3f(original[1]).add(delta);
                currentEndpoints.put(edgeIndex, new Vector3f[]{newPoint1, newPoint2});
            }
        }

        // Check if position has changed from original
        float threshold = 0.001f;
        isModified = (delta.lengthSquared() > threshold * threshold);

        logger.trace("Updated {} edge positions by delta ({}, {}, {}), modified={}",
                selectedEdgeIndices.size(),
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
            logger.debug("Ended drag for {} edges, modified={}",
                    selectedEdgeIndices.size(), isModified);
        }
    }

    /**
     * Cancel the drag operation, reverting all edges to original positions.
     */
    public synchronized void cancelDrag() {
        if (isDragging) {
            // Revert all edges to original positions
            for (Integer edgeIndex : selectedEdgeIndices) {
                Vector3f[] original = originalEndpoints.get(edgeIndex);
                if (original != null && original.length == 2) {
                    currentEndpoints.put(edgeIndex, new Vector3f[]{
                            new Vector3f(original[0]),
                            new Vector3f(original[1])
                    });
                }
            }
            isDragging = false;
            isModified = false;
            logger.debug("Cancelled drag for {} edges, reverted to original positions",
                    selectedEdgeIndices.size());
        }
    }

    /**
     * Revert all current positions to original positions.
     */
    public synchronized void revertToOriginal() {
        if (!selectedEdgeIndices.isEmpty()) {
            for (Integer edgeIndex : selectedEdgeIndices) {
                Vector3f[] original = originalEndpoints.get(edgeIndex);
                if (original != null && original.length == 2) {
                    currentEndpoints.put(edgeIndex, new Vector3f[]{
                            new Vector3f(original[0]),
                            new Vector3f(original[1])
                    });
                }
            }
            isModified = false;
            logger.debug("Reverted {} edges to original positions", selectedEdgeIndices.size());
        }
    }

    /**
     * Get the midpoint of the first selected edge (useful for plane positioning).
     *
     * @return the midpoint, or null if no selection
     */
    public synchronized Vector3f getMidpoint() {
        if (selectedEdgeIndices.isEmpty()) {
            return null;
        }
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        Vector3f[] current = currentEndpoints.get(firstIndex);
        if (current == null || current.length != 2) {
            return null;
        }
        return new Vector3f(current[0]).add(current[1]).mul(0.5f);
    }

    /**
     * Remap vertex indices after a vertex merge operation.
     *
     * @param indexRemapping Mapping from old vertex indices to new vertex indices
     */
    public synchronized void remapVertexIndices(Map<Integer, Integer> indexRemapping) {
        if (selectedEdgeIndices.isEmpty() || indexRemapping == null || indexRemapping.isEmpty()) {
            return;
        }

        for (Integer edgeIndex : selectedEdgeIndices) {
            int[] vertIndices = edgeVertexIndices.get(edgeIndex);
            if (vertIndices != null && vertIndices.length == 2) {
                Integer newV1 = indexRemapping.get(vertIndices[0]);
                Integer newV2 = indexRemapping.get(vertIndices[1]);
                if (newV1 != null && newV2 != null) {
                    edgeVertexIndices.put(edgeIndex, new int[]{newV1, newV2});
                    logger.debug("Remapped edge {} vertices: ({}, {}) -> ({}, {})",
                            edgeIndex, vertIndices[0], vertIndices[1], newV1, newV2);
                }
            }
        }
    }

    // Getters

    /**
     * Check if any edge is currently selected.
     *
     * @return true if at least one edge is selected, false otherwise
     */
    public synchronized boolean hasSelection() {
        return !selectedEdgeIndices.isEmpty();
    }

    /**
     * Get the number of selected edges.
     *
     * @return the count of selected edges
     */
    public synchronized int getSelectionCount() {
        return selectedEdgeIndices.size();
    }

    /**
     * Get all selected edge indices.
     *
     * @return a copy of the selected edge indices set
     */
    public synchronized Set<Integer> getSelectedEdgeIndices() {
        return new LinkedHashSet<>(selectedEdgeIndices);
    }

    /**
     * Get the first selected edge index (for backward compatibility).
     *
     * @return the first edge index, or -1 if no selection
     */
    public synchronized int getSelectedEdgeIndex() {
        return selectedEdgeIndices.isEmpty() ? -1 : selectedEdgeIndices.iterator().next();
    }

    /**
     * Get the vertex indices for a specific edge.
     *
     * @param edgeIndex the edge index
     * @return array [v1, v2] or null if not in selection
     */
    public synchronized int[] getEdgeVertexIndices(int edgeIndex) {
        int[] indices = edgeVertexIndices.get(edgeIndex);
        return indices != null ? indices.clone() : null;
    }

    /**
     * Get the unique vertex index for endpoint 1 of the first selected edge (for backward compatibility).
     *
     * @return the vertex index, or -1 if no selection
     */
    public synchronized int getVertexIndex1() {
        if (selectedEdgeIndices.isEmpty()) return -1;
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        int[] indices = edgeVertexIndices.get(firstIndex);
        return indices != null && indices.length >= 1 ? indices[0] : -1;
    }

    /**
     * Get the unique vertex index for endpoint 2 of the first selected edge (for backward compatibility).
     *
     * @return the vertex index, or -1 if no selection
     */
    public synchronized int getVertexIndex2() {
        if (selectedEdgeIndices.isEmpty()) return -1;
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        int[] indices = edgeVertexIndices.get(firstIndex);
        return indices != null && indices.length >= 2 ? indices[1] : -1;
    }

    /**
     * Get the original endpoints for a specific edge.
     *
     * @param edgeIndex the edge index
     * @return array [point1, point2] or null if not in selection
     */
    public synchronized Vector3f[] getOriginalEndpoints(int edgeIndex) {
        Vector3f[] points = originalEndpoints.get(edgeIndex);
        if (points == null || points.length != 2) return null;
        return new Vector3f[]{new Vector3f(points[0]), new Vector3f(points[1])};
    }

    /**
     * Get the original first endpoint of the first selected edge (for backward compatibility).
     *
     * @return copy of original point1, or null if no selection
     */
    public synchronized Vector3f getOriginalPoint1() {
        if (selectedEdgeIndices.isEmpty()) return null;
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        Vector3f[] points = originalEndpoints.get(firstIndex);
        return points != null && points.length >= 1 ? new Vector3f(points[0]) : null;
    }

    /**
     * Get the original second endpoint of the first selected edge (for backward compatibility).
     *
     * @return copy of original point2, or null if no selection
     */
    public synchronized Vector3f getOriginalPoint2() {
        if (selectedEdgeIndices.isEmpty()) return null;
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        Vector3f[] points = originalEndpoints.get(firstIndex);
        return points != null && points.length >= 2 ? new Vector3f(points[1]) : null;
    }

    /**
     * Get the current endpoints for a specific edge.
     *
     * @param edgeIndex the edge index
     * @return array [point1, point2] or null if not in selection
     */
    public synchronized Vector3f[] getCurrentEndpoints(int edgeIndex) {
        Vector3f[] points = currentEndpoints.get(edgeIndex);
        if (points == null || points.length != 2) return null;
        return new Vector3f[]{new Vector3f(points[0]), new Vector3f(points[1])};
    }

    /**
     * Get the current first endpoint of the first selected edge (for backward compatibility).
     *
     * @return copy of current point1, or null if no selection
     */
    public synchronized Vector3f getCurrentPoint1() {
        if (selectedEdgeIndices.isEmpty()) return null;
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        Vector3f[] points = currentEndpoints.get(firstIndex);
        return points != null && points.length >= 1 ? new Vector3f(points[0]) : null;
    }

    /**
     * Get the current second endpoint of the first selected edge (for backward compatibility).
     *
     * @return copy of current point2, or null if no selection
     */
    public synchronized Vector3f getCurrentPoint2() {
        if (selectedEdgeIndices.isEmpty()) return null;
        Integer firstIndex = selectedEdgeIndices.iterator().next();
        Vector3f[] points = currentEndpoints.get(firstIndex);
        return points != null && points.length >= 2 ? new Vector3f(points[1]) : null;
    }

    /**
     * Check if currently dragging edges.
     *
     * @return true if dragging, false otherwise
     */
    public synchronized boolean isDragging() {
        return isDragging;
    }

    /**
     * Check if any selected edge has been modified (position changed from original).
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

    /**
     * Get all unique vertex indices across all selected edges.
     * Useful for multi-edge translation to avoid moving shared vertices twice.
     *
     * @return set of unique vertex indices
     */
    public synchronized Set<Integer> getAllSelectedVertexIndices() {
        Set<Integer> allVertices = new HashSet<>();
        for (Integer edgeIndex : selectedEdgeIndices) {
            int[] indices = edgeVertexIndices.get(edgeIndex);
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

    @Override
    public synchronized String toString() {
        if (selectedEdgeIndices.isEmpty()) {
            return "EdgeSelectionState{no selection}";
        }
        return String.format("EdgeSelectionState{count=%d, indices=%s, dragging=%s, modified=%s}",
                selectedEdgeIndices.size(),
                selectedEdgeIndices,
                isDragging, isModified);
    }
}
