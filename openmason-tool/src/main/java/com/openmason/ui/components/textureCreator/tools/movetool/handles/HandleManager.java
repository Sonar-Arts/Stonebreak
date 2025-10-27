package com.openmason.ui.components.textureCreator.tools.movetool.handles;

import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.movetool.transform.GeometryHelper;
import com.openmason.ui.components.textureCreator.transform.TransformHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages transform handles generation and lookup.
 * Responsible for creating handles around selection bounds and finding handles based on mouse position.
 * Follows Single Responsibility Principle - only handles handle management.
 *
 * @author Open Mason Team
 */
public class HandleManager {

    // Handle configuration - hit radii in canvas pixels
    private static final double CORNER_HANDLE_RADIUS = 1.0;
    private static final double EDGE_HANDLE_RADIUS = 1.0;
    private static final double ROTATION_HANDLE_RADIUS = 1.0;
    private static final double CENTER_HANDLE_RADIUS = 3.0;
    private static final double ROTATION_HANDLE_SCREEN_OFFSET = 40.0;

    private final List<TransformHandle> handles = new ArrayList<>();
    private float currentZoom = 1.0f;

    /**
     * Generates handles around the given selection region.
     *
     * @param selection The selection region to generate handles for
     * @param zoom Current zoom level (for screen-space calculations)
     */
    public void generateHandles(SelectionRegion selection, float zoom) {
        handles.clear();
        this.currentZoom = zoom;

        if (selection == null || selection.isEmpty()) {
            return;
        }

        // Use bounds for any selection type (rectangular, free-form, etc.)
        java.awt.Rectangle bounds = selection.getBounds();
        int x1 = bounds.x;
        int y1 = bounds.y;
        int x2 = bounds.x + bounds.width - 1;
        int y2 = bounds.y + bounds.height - 1;

        // Calculate visual box edges (selection box goes from x1 to x2+1 in canvas space)
        double boxRight = x2 + 1;
        double boxBottom = y2 + 1;
        double midX = (x1 + boxRight) / 2.0;
        double midY = (y1 + boxBottom) / 2.0;

        // Corner handles - positioned on selection box corners
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_LEFT, x1, y1, CORNER_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_RIGHT, boxRight, y1, CORNER_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_LEFT, x1, boxBottom, CORNER_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_RIGHT, boxRight, boxBottom, CORNER_HANDLE_RADIUS));

        // Edge handles - positioned on selection box edge midpoints
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_TOP, midX, y1, EDGE_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_RIGHT, boxRight, midY, EDGE_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_BOTTOM, midX, boxBottom, EDGE_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_LEFT, x1, midY, EDGE_HANDLE_RADIUS));

        // Rotation handle - offset is constant in screen space
        double canvasSpaceOffset = ROTATION_HANDLE_SCREEN_OFFSET / Math.max(0.1, currentZoom);
        handles.add(new TransformHandle(TransformHandle.Type.ROTATION, midX, y1 - canvasSpaceOffset, ROTATION_HANDLE_RADIUS));

        // Center handle (for moving)
        handles.add(new TransformHandle(TransformHandle.Type.CENTER, midX, midY, CENTER_HANDLE_RADIUS));
    }

    /**
     * Finds the nearest handle to the given point using distance-based selection.
     * Returns the handle only if the mouse is within its hit radius.
     *
     * @param x Mouse x coordinate in canvas space
     * @param y Mouse y coordinate in canvas space
     * @param includeCenter Whether to include the center handle in the search
     * @return The nearest handle within range, or null if no handle is close enough
     */
    public TransformHandle findNearestHandle(double x, double y, boolean includeCenter) {
        TransformHandle nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (TransformHandle handle : handles) {
            // Skip center handle if not included
            if (!includeCenter && handle.isCenter()) {
                continue;
            }

            if (handle.contains(x, y)) {
                double distance = GeometryHelper.calculateDistance(x, y, handle.getX(), handle.getY());

                if (distance < nearestDistance) {
                    nearest = handle;
                    nearestDistance = distance;
                }
            }
        }

        return nearest;
    }

    /**
     * Finds the center handle.
     *
     * @return The center handle, or null if not found
     */
    public TransformHandle findCenterHandle() {
        for (TransformHandle handle : handles) {
            if (handle.isCenter()) {
                return handle;
            }
        }
        return null;
    }

    /**
     * Gets the current list of handles.
     *
     * @return List of transform handles
     */
    public List<TransformHandle> getHandles() {
        return handles;
    }

    /**
     * Clears all handles.
     */
    public void clear() {
        handles.clear();
    }

    /**
     * Checks if there are any handles.
     *
     * @return true if handles exist, false otherwise
     */
    public boolean isEmpty() {
        return handles.isEmpty();
    }
}
