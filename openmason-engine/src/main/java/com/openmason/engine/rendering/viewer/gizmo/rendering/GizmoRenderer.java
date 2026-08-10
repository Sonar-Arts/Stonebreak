package com.openmason.engine.rendering.viewer.gizmo.rendering;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.gizmo.interaction.GizmoInteractionHandler;
import com.openmason.engine.rendering.viewer.gizmo.interaction.GizmoPart;
import com.openmason.engine.rendering.viewer.gizmo.interaction.ITransformTarget;
import com.openmason.engine.rendering.viewer.gizmo.modes.IGizmoMode;
import com.openmason.engine.rendering.viewer.gizmo.modes.RotateMode;
import com.openmason.engine.rendering.viewer.gizmo.modes.ScaleMode;
import com.openmason.engine.rendering.viewer.gizmo.modes.TranslateMode;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.engine.rendering.viewer.gizmo.SnapSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates transform gizmo rendering and interaction (translate, rotate, scale).
 * Extends SOLID principles with extensible mode system and proper resource management.
 */
public class GizmoRenderer {

    private static final float GIZMO_SCALE_FACTOR = 0.3f;
    private static final float MIN_GIZMO_SCALE = 0.5f;
    private static final float MAX_GIZMO_SCALE = 5.0f;

    private final GizmoState gizmoState;
    private final TransformState transformState;
    private final GizmoInteractionHandler interactionHandler;
    private SnapSettings snapSettings;

    private final Map<GizmoState.Mode, IGizmoMode> modes = new HashMap<>();
    private IGizmoMode currentMode;

    private int shaderProgram = -1;
    private boolean initialized = false;

    private ModelBounds modelBounds = ModelBounds.EMPTY;

    /**
     * Creates a new GizmoRenderer.
     *
     * @param gizmoState The gizmo state to manage (must not be null)
     * @param transformState The transform state to modify (must not be null)
     * @param snapSettings The viewport state for grid snapping (may be null initially)
     * @throws IllegalArgumentException if gizmoState or transformState is null
     */
    public GizmoRenderer(GizmoState gizmoState, TransformState transformState, SnapSettings snapSettings) {
        if (gizmoState == null) {
            throw new IllegalArgumentException("GizmoState cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.gizmoState = gizmoState;
        this.transformState = transformState;
        this.snapSettings = snapSettings;
        this.interactionHandler = new GizmoInteractionHandler(gizmoState, transformState, snapSettings);

        modes.put(GizmoState.Mode.TRANSLATE, new TranslateMode());
        modes.put(GizmoState.Mode.ROTATE, new RotateMode());
        modes.put(GizmoState.Mode.SCALE, new ScaleMode());

        currentMode = modes.get(gizmoState.getCurrentMode());
    }

    /**
     * Initializes the gizmo renderer and all modes.
     * Must be called before rendering.
     *
     * @throws IllegalStateException if initialization fails
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            shaderProgram = GizmoShaderLoader.loadGizmoShaders();
            if (shaderProgram < 0) {
                throw new IllegalStateException("Failed to load gizmo shaders");
            }

            for (IGizmoMode mode : modes.values()) {
                mode.initialize();
                mode.setShaderProgram(shaderProgram);
            }

            initialized = true;
        } catch (Exception e) {
            dispose();
            throw new IllegalStateException("Failed to initialize GizmoRenderer", e);
        }
    }

    public void render(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized) {
            throw new IllegalStateException("GizmoRenderer not initialized");
        }
        if (!gizmoState.isEnabled()) {
            return;
        }

        updateCurrentMode();
        if (currentMode == null) {
            return;
        }

        Matrix4f gizmoTransform = createGizmoTransform();
        Matrix4f viewProjection = new Matrix4f(projectionMatrix).mul(viewMatrix);

        boolean cullFaceEnabled = GL30.glIsEnabled(GL30.GL_CULL_FACE);

        configureRenderState();
        currentMode.render(gizmoTransform, viewProjection, gizmoState);
        restoreRenderState(cullFaceEnabled);
    }

    private void updateCurrentMode() {
        IGizmoMode newMode = modes.get(gizmoState.getCurrentMode());
        if (newMode != currentMode) {
            currentMode = newMode;
        }
    }

    private Matrix4f createGizmoTransform() {
        Vector3f worldCenter = computeGizmoWorldCenter();
        float gizmoScale = computeGizmoScale();
        return new Matrix4f().identity().translate(worldCenter).scale(gizmoScale);
    }

    /**
     * Computes the gizmo's world-space center.
     * If a part is selected (via the transform target on the interaction handler),
     * positions at the selected part's center. Otherwise uses model bounds center.
     */
    private Vector3f computeGizmoWorldCenter() {
        ITransformTarget target = interactionHandler.getActiveTransformTarget();
        if (target != null) {
            return target.getWorldCenter();
        }

        Vector3f boundsCenter = modelBounds.center();
        if (boundsCenter.lengthSquared() < 0.0001f) {
            return new Vector3f(
                transformState.getPositionX(),
                transformState.getPositionY(),
                transformState.getPositionZ()
            );
        }
        return transformState.getTransformMatrix()
            .transformPosition(new Vector3f(boundsCenter));
    }

    /**
     * Computes a uniform scale factor for the gizmo based on the model's largest dimension.
     */
    private float computeGizmoScale() {
        float maxExtent = modelBounds.maxExtent();
        if (maxExtent < 0.001f) {
            return 1.0f;
        }
        float scale = maxExtent * GIZMO_SCALE_FACTOR;
        return Math.max(MIN_GIZMO_SCALE, Math.min(MAX_GIZMO_SCALE, scale));
    }

    private void configureRenderState() {
        GL30.glEnable(GL30.GL_DEPTH_TEST);
        GL30.glDepthFunc(GL30.GL_ALWAYS);
        GL30.glDisable(GL30.GL_CULL_FACE);
    }

    private void restoreRenderState(boolean cullFaceEnabled) {
        if (cullFaceEnabled) {
            GL30.glEnable(GL30.GL_CULL_FACE);
        }
        GL30.glDepthFunc(GL30.GL_LEQUAL);
        GL30.glDisable(GL30.GL_DEPTH_TEST);
    }

    /**
     * Handles mouse movement for gizmo interaction.
     */
    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !gizmoState.isEnabled()) {
            return;
        }

        interactionHandler.updateCamera(viewMatrix, projectionMatrix, viewportWidth, viewportHeight);

        Vector3f gizmoWorldCenter = computeGizmoWorldCenter();
        float gizmoScale = computeGizmoScale();
        interactionHandler.setGizmoWorldCenter(gizmoWorldCenter);

        List<GizmoPart> parts = currentMode.getInteractiveParts(gizmoWorldCenter, gizmoScale);

        interactionHandler.handleMouseMove(mouseX, mouseY, parts);
    }

    /**
     * Handles mouse press for starting drag operations.
     * @return true if gizmo was clicked, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        if (!initialized || !gizmoState.isEnabled()) {
            return false;
        }

        return interactionHandler.handleMousePress(mouseX, mouseY);
    }

    /**
     * Handles mouse release for ending drag operations.
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!initialized || !gizmoState.isEnabled()) {
            return;
        }

        interactionHandler.handleMouseRelease(mouseX, mouseY);
    }

    /**
     * Sets the gizmo mode directly.
     */
    public void setMode(GizmoState.Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }
        gizmoState.setCurrentMode(mode);
    }

    public GizmoState.Mode getCurrentMode() {
        return gizmoState.getCurrentMode();
    }

    public boolean isDragging() {
        return gizmoState.isDragging();
    }

    /**
     * Set where completed model-level gizmo drags are reported for undo.
     * The gizmo itself no longer knows what an undo entry is.
     */
    public void setUndoSink(com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink undoSink) {
        interactionHandler.setUndoSink(undoSink);
    }

    /** Report drags of an active target too — for hosts whose targets have no other undo. */
    public void setRecordActiveTargets(boolean record) {
        interactionHandler.setRecordActiveTargets(record);
    }

    /**
     * Updates viewport state for grid snapping configuration.
     */
    public void setSnapSettings(SnapSettings snapSettings) {
        if (snapSettings == null) {
            throw new IllegalArgumentException("SnapSettings cannot be null");
        }

        this.snapSettings = snapSettings;

        if (interactionHandler != null) {
            interactionHandler.setSnapSettings(snapSettings);
        }
    }

    public void dispose() {
        for (IGizmoMode mode : modes.values()) {
            mode.dispose();
        }

        if (shaderProgram >= 0) {
            GL30.glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }

        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public GizmoState getGizmoState() {
        return gizmoState;
    }

    /**
     * Set the transform target for gizmo operations.
     */
    public void setTransformTarget(ITransformTarget target) {
        interactionHandler.setTransformTarget(target);
    }

    /**
     * Updates the model bounds used for gizmo auto-scaling and centering.
     */
    public void updateModelBounds(ModelBounds bounds) {
        if (bounds == null) {
            throw new IllegalArgumentException("ModelBounds cannot be null");
        }
        this.modelBounds = bounds;
    }
}
