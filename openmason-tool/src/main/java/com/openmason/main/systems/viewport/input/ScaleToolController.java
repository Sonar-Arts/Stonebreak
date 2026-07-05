package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.menus.textureCreator.keyboard.KeyCodeTranslator;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.util.ScaleMath;
import com.openmason.main.systems.viewport.util.ScreenProjectionUtil;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal input controller for Blender-style uniform scaling (S key).
 *
 * <p>Works in Vertex, Edge and Face edit modes over the current selection.
 * The pivot is the selection centroid; the scale factor is the ratio of the
 * mouse's distance to the projected pivot versus the distance when the tool
 * started.
 *
 * <p>State machine:
 * <ul>
 *   <li>INACTIVE → S (with edit mode + selection) → PENDING_START</li>
 *   <li>PENDING_START → first frame with a valid context → SCALING</li>
 *   <li>SCALING → left click / Enter / S again → commit → INACTIVE</li>
 *   <li>SCALING → Esc / right click → revert → INACTIVE</li>
 * </ul>
 */
public class ScaleToolController {

    private static final Logger logger = LoggerFactory.getLogger(ScaleToolController.class);

    private enum Phase {
        INACTIVE,
        PENDING_START,
        SCALING
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

    // Undo/redo support
    private ModelCommandHistory commandHistory;
    private RendererSynchronizer synchronizer;

    // Tool state
    private Phase phase = Phase.INACTIVE;
    private EditMode activeMode = EditMode.NONE;
    private MeshVertexEditSession session;
    private Vector3f pivot;
    private Vector2f pivotScreen;
    private float referenceDistance;
    private float lastAppliedFactor = 1.0f;

    /**
     * Start scale mode, or confirm the in-progress scale (Blender-style:
     * press S to scale, press S again to confirm).
     * Requires an active edit mode with a non-empty selection to start.
     */
    public void startOrConfirm() {
        if (phase != Phase.INACTIVE) {
            confirm();
            return;
        }

        EditMode mode = EditModeManager.getInstance().getCurrentMode();
        if (mode == EditMode.NONE) {
            logger.debug("Scale tool requires an active edit mode");
            return;
        }
        if (!hasSelectionForMode(mode)) {
            logger.debug("Scale tool requires a non-empty {} selection", mode);
            return;
        }

        activeMode = mode;
        phase = Phase.PENDING_START;
        logger.info("Scale tool armed ({} mode)", mode);
    }

    /**
     * @return true if the scale tool is currently active
     */
    public boolean isActive() {
        return phase != Phase.INACTIVE;
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

    public void setCommandHistory(ModelCommandHistory commandHistory, RendererSynchronizer synchronizer) {
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
    }

    // =========================================================================
    // INPUT HANDLING
    // =========================================================================

    /**
     * Handle input for the scale tool.
     *
     * @param context Input context with mouse and keyboard state
     * @return true if input was consumed (blocks lower-priority controllers)
     */
    public boolean handleInput(InputContext context) {
        if (phase == Phase.INACTIVE) {
            return false;
        }

        // Deactivate if the edit mode changed underneath the tool
        if (EditModeManager.getInstance().getCurrentMode() != activeMode) {
            cancel();
            return false;
        }

        // Esc or right click cancels (revert to original positions)
        if (isKeyPressed(GLFW.GLFW_KEY_ESCAPE) || isRightMouseClicked()) {
            cancel();
            return true;
        }

        if (phase == Phase.PENDING_START) {
            if (!beginScaling(context)) {
                deactivate();
            }
            return true;
        }

        // SCALING: confirm on left click or Enter
        if ((context.mouseInBounds && context.mouseClicked)
                || isKeyPressed(GLFW.GLFW_KEY_ENTER) || isKeyPressed(GLFW.GLFW_KEY_KP_ENTER)) {
            confirm();
            return true;
        }

        // Apply the current factor (skip redundant re-applies while the mouse is still)
        float factor = ScaleMath.factor(context.mouseX, context.mouseY, pivotScreen, referenceDistance);
        if (factor != lastAppliedFactor) {
            lastAppliedFactor = factor;
            float f = factor;
            session.apply(original -> ScaleMath.scaleAboutPivot(original, pivot, f));
        }

        return true; // Consume all input while active
    }

    /**
     * Begin the live scale: resolve the selection session, project the pivot,
     * and establish the reference distance from the current mouse position.
     *
     * @return true if scaling started, false if the session could not be established
     */
    private boolean beginScaling(InputContext context) {
        if (context.viewportWidth <= 0 || context.viewportHeight <= 0
                || context.viewMatrix == null || context.projectionMatrix == null) {
            return false;
        }

        session = new MeshVertexEditSession(
            modelRenderer, vertexRenderer, edgeRenderer, faceRenderer,
            vertexSelectionState, edgeSelectionState, faceSelectionState,
            commandHistory, synchronizer);

        if (!session.begin(activeMode)) {
            logger.debug("Scale tool: no vertices resolved from {} selection", activeMode);
            return false;
        }

        pivot = session.centroid();
        if (pivot == null) {
            return false;
        }

        // Project the model-space pivot to screen space (projection * view * model)
        Matrix4f modelMatrix = (transformState != null)
            ? transformState.getTransformMatrix()
            : new Matrix4f();
        Matrix4f mvp = new Matrix4f(context.projectionMatrix).mul(context.viewMatrix).mul(modelMatrix);
        pivotScreen = ScreenProjectionUtil.projectToScreen(pivot, mvp, context.viewportWidth, context.viewportHeight);
        if (pivotScreen == null) {
            logger.debug("Scale tool: pivot is behind the camera");
            return false;
        }

        referenceDistance = ScaleMath.referenceDistance(context.mouseX, context.mouseY, pivotScreen);
        lastAppliedFactor = 1.0f;
        phase = Phase.SCALING;

        logger.info("Scale tool started: {} vertices, pivot ({}, {}, {}), d0={}px",
            session.vertexCount(),
            String.format("%.2f", pivot.x), String.format("%.2f", pivot.y), String.format("%.2f", pivot.z),
            String.format("%.1f", referenceDistance));
        return true;
    }

    /**
     * Commit the scale at the current factor and deactivate.
     */
    private void confirm() {
        if (phase == Phase.SCALING && session != null) {
            session.commit("Scale Selection");
            logger.info("Scale tool committed at factor {}", String.format("%.3f", lastAppliedFactor));
        }
        deactivate();
    }

    /**
     * Revert to original positions and deactivate.
     */
    private void cancel() {
        if (phase == Phase.SCALING && session != null) {
            session.revert();
            logger.info("Scale tool cancelled");
        }
        deactivate();
    }

    private void deactivate() {
        phase = Phase.INACTIVE;
        activeMode = EditMode.NONE;
        session = null;
        pivot = null;
        pivotScreen = null;
        lastAppliedFactor = 1.0f;
    }

    /**
     * @return true if the current mode's selection state has at least one element
     */
    private boolean hasSelectionForMode(EditMode mode) {
        return switch (mode) {
            case VERTEX -> vertexSelectionState != null && vertexSelectionState.hasSelection();
            case EDGE -> edgeSelectionState != null && edgeSelectionState.hasSelection();
            case FACE -> faceSelectionState != null && faceSelectionState.hasSelection();
            default -> false;
        };
    }

    // =========================================================================
    // INPUT PROBES (overridable seams for headless state-machine tests)
    // =========================================================================

    /**
     * @return true if the key was pressed this frame (ImGui-backed in production)
     */
    protected boolean isKeyPressed(int glfwKeyCode) {
        return KeyCodeTranslator.isKeyPressed(glfwKeyCode);
    }

    /**
     * @return true if the right mouse button was clicked this frame (ImGui-backed in production)
     */
    protected boolean isRightMouseClicked() {
        return imgui.ImGui.isMouseClicked(1);
    }
}
