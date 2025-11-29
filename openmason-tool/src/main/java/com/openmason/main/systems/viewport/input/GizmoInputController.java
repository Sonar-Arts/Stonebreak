package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.viewport.gizmo.rendering.GizmoRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all gizmo (transform manipulator) input for the viewport.
 *
 * Responsibilities:
 * - Update gizmo hover detection via raycasting
 * - Handle gizmo click/drag/release
 * - Determine if gizmo is active (blocks lower-priority input)
 * - Thin wrapper around GizmoRenderer (which has GizmoInteractionHandler internally)
 *
 * Design:
 * - Single Responsibility: Gizmo interaction only
 * - Delegation: GizmoRenderer handles rendering and interaction logic
 * - Priority: Medium (blocks camera, but lower than vertex/edge for precise editing)
 * - Active state: Hovered OR dragging (prevents camera from capturing mouse)
 */
public class GizmoInputController {

    private static final Logger logger = LoggerFactory.getLogger(GizmoInputController.class);

    private GizmoRenderer gizmoRenderer = null;

    /**
     * Set the gizmo renderer for interaction.
     * This should be called after viewport initialization.
     */
    public void setGizmoRenderer(GizmoRenderer gizmoRenderer) {
        this.gizmoRenderer = gizmoRenderer;
        logger.debug("Gizmo renderer set in GizmoInputController");
    }

    /**
     * Handle gizmo input.
     *
     * Priority: Medium (lower than vertex/edge, higher than camera)
     * - If gizmo is dragging: Return true (blocks camera)
     * - If gizmo is hovered: Return true (prevents camera from starting drag)
     * - Otherwise: Return false (allows camera to process input)
     *
     * Note: Vertex and edge controllers have higher priority and process first
     *
     * @param context Input context with mouse state
     * @return True if gizmo handled input (blocks camera)
     */
    public boolean handleInput(InputContext context) {
        if (gizmoRenderer == null || !gizmoRenderer.isInitialized() ||
            !gizmoRenderer.getGizmoState().isEnabled()) {
            return false; // Gizmo not available
        }

        // Update gizmo hover with camera matrices for raycasting
        gizmoRenderer.handleMouseMove(
                context.mouseX,
                context.mouseY,
                context.viewMatrix,
                context.projectionMatrix,
                context.viewportWidth,
                context.viewportHeight
        );

        // Check if gizmo has a hovered part (prevents camera from capturing mouse)
        // This is critical: we need to check hover BEFORE camera can start dragging
        boolean gizmoIsHovered = gizmoRenderer.getGizmoState().getHoveredPart() != null;

        // Handle mouse press on gizmo
        boolean gizmoHandledClick = false;
        if (context.mouseInBounds && context.mouseClicked) {
            gizmoHandledClick = gizmoRenderer.handleMousePress(context.mouseX, context.mouseY);
            if (gizmoHandledClick) {
                logger.debug("Gizmo captured mouse press");
            }
        }

        // Handle mouse release
        if (context.mouseReleased) {
            gizmoRenderer.handleMouseRelease(context.mouseX, context.mouseY);
        }

        // Priority 1: Active gizmo dragging
        // If gizmo is dragging, it has full control and blocks all other input
        if (gizmoRenderer.isDragging()) {
            return true; // Block all lower-priority controllers
        }

        // Priority 2: Gizmo hover state
        // Prevent camera from starting drag when hovering over gizmo parts
        // This prevents the camera from capturing the mouse on the next click
        if (gizmoIsHovered) {
            return true; // Block lower-priority controllers (treat hover as "handled")
        }

        // Priority 3: Gizmo handled click
        if (gizmoHandledClick) {
            return true;
        }

        return false; // Gizmo not active, allow lower-priority controllers
    }

    /**
     * Check if gizmo is currently active (hovered OR dragging).
     * Used by main controller to determine if camera should release mouse capture.
     *
     * @return True if gizmo is hovered or dragging
     */
    public boolean isActive() {
        if (gizmoRenderer == null || !gizmoRenderer.isInitialized() ||
            !gizmoRenderer.getGizmoState().isEnabled()) {
            return false;
        }

        return gizmoRenderer.isDragging() ||
               gizmoRenderer.getGizmoState().getHoveredPart() != null;
    }
}
