package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.menus.textureCreator.keyboard.KeyCodeTranslator;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.util.BoxSelectMath;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Modal input controller for box selection (B key, Blender-style).
 *
 * <p>Selects the current edit mode's elements inside a screen-space rectangle:
 * vertices by projected position, edges when BOTH endpoints are inside, faces
 * by centroid. Shift held at release adds to the selection; otherwise the
 * selection is replaced. No occlusion test — the box selects through the model
 * (documented v1 simplification).
 *
 * <p>State machine:
 * <ul>
 *   <li>INACTIVE → B (with edit mode) → ARMED (consumes all input)</li>
 *   <li>ARMED → mouse press in viewport → DRAGGING (rect follows mouse)</li>
 *   <li>DRAGGING → mouse release → apply selection → INACTIVE</li>
 *   <li>Any phase → Esc or B again → INACTIVE</li>
 * </ul>
 */
public class BoxSelectController {

    private static final Logger logger = LoggerFactory.getLogger(BoxSelectController.class);

    private enum Phase {
        INACTIVE,
        ARMED,
        DRAGGING
    }

    // Dependencies (set via setters, same pattern as other input controllers)
    private GenericModelRenderer modelRenderer;
    private VertexRenderer vertexRenderer;
    private EdgeRenderer edgeRenderer;
    private FaceRenderer faceRenderer;
    private VertexSelectionState vertexSelectionState;
    private EdgeSelectionState edgeSelectionState;
    private FaceSelectionState faceSelectionState;
    private TransformState transformState;

    // Tool state
    private Phase phase = Phase.INACTIVE;
    private float dragStartX;
    private float dragStartY;
    private float dragCurrentX;
    private float dragCurrentY;

    /**
     * Toggle box select on/off from the keybind (B key).
     * Requires an active edit mode to arm; toggling while active cancels.
     */
    public void toggle() {
        if (phase != Phase.INACTIVE) {
            deactivate();
            logger.info("Box select cancelled (B pressed again)");
            return;
        }

        if (EditModeManager.getInstance().getCurrentMode() == EditMode.NONE) {
            logger.debug("Box select requires an active edit mode");
            return;
        }

        phase = Phase.ARMED;
        logger.info("Box select armed");
    }

    /**
     * @return true if box select is currently active (armed or dragging)
     */
    public boolean isActive() {
        return phase != Phase.INACTIVE;
    }

    /**
     * Get the active selection rectangle for overlay rendering.
     *
     * @return Viewport-relative rect as {minX, minY, maxX, maxY}, or null when not dragging
     */
    public float[] getActiveRect() {
        if (phase != Phase.DRAGGING) {
            return null;
        }
        return BoxSelectMath.normalizeRect(dragStartX, dragStartY, dragCurrentX, dragCurrentY);
    }

    // =========================================================================
    // DEPENDENCY SETTERS
    // =========================================================================

    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        this.modelRenderer = modelRenderer;
    }

    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        this.vertexRenderer = vertexRenderer;
    }

    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        this.edgeRenderer = edgeRenderer;
    }

    public void setFaceRenderer(FaceRenderer faceRenderer) {
        this.faceRenderer = faceRenderer;
    }

    public void setVertexSelectionState(VertexSelectionState vertexSelectionState) {
        this.vertexSelectionState = vertexSelectionState;
    }

    public void setEdgeSelectionState(EdgeSelectionState edgeSelectionState) {
        this.edgeSelectionState = edgeSelectionState;
    }

    public void setFaceSelectionState(FaceSelectionState faceSelectionState) {
        this.faceSelectionState = faceSelectionState;
    }

    public void setTransformState(TransformState transformState) {
        this.transformState = transformState;
    }

    // =========================================================================
    // INPUT HANDLING
    // =========================================================================

    /**
     * Handle input for box select.
     *
     * @param context Input context with mouse and keyboard state
     * @return true if input was consumed (blocks lower-priority controllers)
     */
    public boolean handleInput(InputContext context) {
        if (phase == Phase.INACTIVE) {
            return false;
        }

        // Deactivate if editing is no longer allowed
        if (EditModeManager.getInstance().getCurrentMode() == EditMode.NONE) {
            deactivate();
            return false;
        }

        // Esc cancels without selecting
        if (isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            deactivate();
            logger.info("Box select cancelled (Esc)");
            return true;
        }

        if (phase == Phase.ARMED) {
            // Start the rectangle on mouse press inside the viewport
            if (context.mouseInBounds && context.mouseClicked) {
                dragStartX = context.mouseX;
                dragStartY = context.mouseY;
                dragCurrentX = context.mouseX;
                dragCurrentY = context.mouseY;
                phase = Phase.DRAGGING;
            }
            return true; // Consume all input while armed
        }

        // DRAGGING: track the rectangle, select on release
        dragCurrentX = context.mouseX;
        dragCurrentY = context.mouseY;

        if (context.mouseReleased) {
            applySelection(context);
            deactivate();
        }

        return true; // Consume all input while active
    }

    // =========================================================================
    // SELECTION
    // =========================================================================

    /**
     * Apply the rectangle selection for the current edit mode.
     * Shift held at release adds to the existing selection, otherwise replaces it.
     */
    private void applySelection(InputContext context) {
        float[] rect = BoxSelectMath.normalizeRect(dragStartX, dragStartY, dragCurrentX, dragCurrentY);
        boolean additive = context.shiftDown;

        Matrix4f modelMatrix = (transformState != null)
            ? transformState.getTransformMatrix()
            : new Matrix4f();
        Matrix4f mvp = new Matrix4f(context.projectionMatrix).mul(context.viewMatrix).mul(modelMatrix);

        switch (EditModeManager.getInstance().getCurrentMode()) {
            case VERTEX -> selectVertices(rect, mvp, context, additive);
            case EDGE -> selectEdges(rect, mvp, context, additive);
            case FACE -> selectFaces(rect, mvp, context, additive);
            default -> { /* NONE: nothing to select */ }
        }
    }

    private void selectVertices(float[] rect, Matrix4f mvp, InputContext context, boolean additive) {
        if (vertexRenderer == null || vertexSelectionState == null) {
            return;
        }

        int[] hits = BoxSelectMath.verticesInRect(
            vertexRenderer.getAllVertexPositions(), vertexRenderer.getVertexCount(),
            mvp, context.viewportWidth, context.viewportHeight, rect);

        if (!additive) {
            vertexSelectionState.clearSelection();
            vertexRenderer.clearSelection();
        }

        for (int vertexIndex : hits) {
            if (!vertexSelectionState.isSelected(vertexIndex)) {
                Vector3f position = vertexRenderer.getVertexPosition(vertexIndex);
                if (position != null) {
                    vertexSelectionState.toggleVertex(vertexIndex, position);
                }
            }
        }

        vertexRenderer.updateSelectionSet(vertexSelectionState.getSelectedVertexIndices());
        logger.info("Box select: {} vertices in rect ({} total selected)",
            hits.length, vertexSelectionState.getSelectionCount());
    }

    private void selectEdges(float[] rect, Matrix4f mvp, InputContext context, boolean additive) {
        if (edgeRenderer == null || edgeSelectionState == null) {
            return;
        }

        int[] hits = BoxSelectMath.edgesInRect(
            edgeRenderer.getEdgePositions(), edgeRenderer.getEdgeCount(),
            mvp, context.viewportWidth, context.viewportHeight, rect);

        if (!additive) {
            edgeSelectionState.clearSelection();
            edgeRenderer.clearSelection();
        }

        for (int edgeIndex : hits) {
            if (!edgeSelectionState.isSelected(edgeIndex)) {
                Vector3f[] endpoints = edgeRenderer.getEdgeEndpoints(edgeIndex);
                int[] vertexIndices = edgeRenderer.getEdgeVertexIndices(edgeIndex);
                if (endpoints != null && endpoints.length == 2
                        && vertexIndices != null && vertexIndices.length == 2) {
                    edgeSelectionState.toggleEdge(edgeIndex,
                        endpoints[0], endpoints[1], vertexIndices[0], vertexIndices[1]);
                }
            }
        }

        edgeRenderer.updateSelectionSet(edgeSelectionState.getSelectedEdgeIndices());
        logger.info("Box select: {} edges in rect ({} total selected)",
            hits.length, edgeSelectionState.getSelectionCount());
    }

    private void selectFaces(float[] rect, Matrix4f mvp, InputContext context, boolean additive) {
        if (faceRenderer == null || faceSelectionState == null || modelRenderer == null) {
            return;
        }

        int[] hits = BoxSelectMath.facesInRect(
            collectFaceIds(),
            faceRenderer::getTriangleVertexPositionsForFace,
            mvp, context.viewportWidth, context.viewportHeight, rect);

        if (!additive) {
            faceSelectionState.clearSelection();
            faceRenderer.clearSelection();
        }

        for (int faceId : hits) {
            if (!faceSelectionState.isSelected(faceId)) {
                Vector3f[] vertices = faceRenderer.getTriangleVertexPositionsForFace(faceId);
                int[] vertexIndices = faceRenderer.getTriangleVertexIndicesForFace(faceId);
                if (vertices != null && vertices.length >= 3) {
                    faceSelectionState.toggleFace(faceId, vertices, vertexIndices);
                }
            }
        }

        faceRenderer.updateSelectionSet(faceSelectionState.getSelectedFaceIndices());
        logger.info("Box select: {} faces in rect ({} total selected)",
            hits.length, faceSelectionState.getSelectionCount());
    }

    /**
     * Collect the distinct face IDs from the triangle-to-face mapping.
     * Face IDs can be sparse (Blender-style gaps after deletions), so they are
     * enumerated from the mapping rather than assumed contiguous.
     */
    private int[] collectFaceIds() {
        int[] triangleToFaceId = modelRenderer.getTriangleToFaceMapping();
        if (triangleToFaceId == null) {
            return new int[0];
        }
        Set<Integer> faceIds = new LinkedHashSet<>();
        for (int faceId : triangleToFaceId) {
            faceIds.add(faceId);
        }
        int[] result = new int[faceIds.size()];
        int i = 0;
        for (int faceId : faceIds) {
            result[i++] = faceId;
        }
        return result;
    }

    private void deactivate() {
        phase = Phase.INACTIVE;
    }

    // =========================================================================
    // INPUT PROBES (overridable seam for headless state-machine tests)
    // =========================================================================

    /**
     * @return true if the key was pressed this frame (ImGui-backed in production)
     */
    protected boolean isKeyPressed(int glfwKeyCode) {
        return KeyCodeTranslator.isKeyPressed(glfwKeyCode);
    }
}
