package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
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
    private VertexRenderer vertexRenderer = null;  // For priority check
    private EdgeRenderer edgeRenderer = null;      // For priority check
    private FaceRenderer faceRenderer = null;      // For priority check

    /**
     * Set the gizmo renderer for interaction.
     * This should be called after viewport initialization.
     */
    public void setGizmoRenderer(GizmoRenderer gizmoRenderer) {
        this.gizmoRenderer = gizmoRenderer;
        logger.debug("Gizmo renderer set in GizmoInputController");
    }

    /**
     * Set the vertex renderer for priority checking.
     * CRITICAL: Gizmo controller needs to check if a vertex is hovered before updating gizmo hover.
     */
    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        this.vertexRenderer = vertexRenderer;
        logger.debug("Vertex renderer set in GizmoInputController for priority checking");
    }

    /**
     * Set the edge renderer for priority checking.
     * CRITICAL: Gizmo controller needs to check if an edge is hovered before updating gizmo hover.
     */
    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        this.edgeRenderer = edgeRenderer;
        logger.debug("Edge renderer set in GizmoInputController for priority checking");
    }

    /**
     * Set the face renderer for priority checking.
     * CRITICAL: Gizmo controller needs to check if a face is hovered before updating gizmo hover.
     */
    public void setFaceRenderer(FaceRenderer faceRenderer) {
        this.faceRenderer = faceRenderer;
        logger.debug("Face renderer set in GizmoInputController for priority checking");
    }

    /**
     * Handle gizmo input.
     *
     * Priority: Medium (lower than vertex/edge/face, higher than camera)
     * - If gizmo is dragging: Return true (blocks camera)
     * - If gizmo is hovered: Return true (prevents camera from starting drag)
     * - Otherwise: Return false (allows camera to process input)
     *
     * Note: Vertex, edge, and face controllers have higher priority and process first
     *
     * @param context Input context with mouse state
     * @return True if gizmo handled input (blocks camera)
     */
    public boolean handleInput(InputContext context) {
        if (gizmoRenderer == null || !gizmoRenderer.isInitialized() ||
            !gizmoRenderer.getGizmoState().isEnabled()) {
            return false; // Gizmo not available
        }

        // PRIORITY CHECK: Only update gizmo hover if no vertex/edge/face is hovered
        int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
        int hoveredEdge = (edgeRenderer != null) ? edgeRenderer.getHoveredEdgeIndex() : -1;
        int hoveredFace = (faceRenderer != null) ? faceRenderer.getHoveredFaceIndex() : -1;

        if (hoveredVertex >= 0 || hoveredEdge >= 0 || hoveredFace >= 0) {
            // Higher-priority element is hovered - clear gizmo hover and don't process
            gizmoRenderer.getGizmoState().setHoveredPart(null);
            return false;
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
