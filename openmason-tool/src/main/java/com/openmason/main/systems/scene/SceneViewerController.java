package com.openmason.main.systems.scene;

import com.openmason.engine.format.omsc.OMSCFormat;
import com.openmason.engine.rendering.shaders.ShaderManager;
import com.openmason.engine.rendering.viewer.ModelViewer;
import com.openmason.engine.rendering.viewer.ViewerSettings;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.gizmo.rendering.GizmoRenderer;
import com.openmason.engine.rendering.viewer.passes.GizmoPass;
import com.openmason.engine.rendering.viewer.passes.GridPass;
import com.openmason.engine.rendering.viewer.passes.ModelInstancePass;
import com.openmason.engine.rendering.viewer.passes.SelectionOutlinePass;
import com.openmason.engine.rendering.viewer.picking.PickResult;
import com.openmason.engine.rendering.viewer.picking.ScenePicker;
import com.openmason.engine.rendering.viewer.scene.InstanceTransformTarget;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Scene Viewer's 3D surface: a second {@link ModelViewer}, independent of the model
 * editor's.
 *
 * <p>Registers only the shared engine passes — grid, model instances, selection
 * outline, gizmo — so it never touches the editor's mesh-editing machinery (and
 * therefore never touches the process-global edit-mode state that machinery relies on).
 */
public class SceneViewerController {

    private static final Logger logger = LoggerFactory.getLogger(SceneViewerController.class);

    /** Matches the model editor's historical fixed frame delta. */
    private static final float FRAME_DELTA_SECONDS = 0.016f;

    private final SceneDocument document;
    private final SceneViewerUIState uiState;

    private final ShaderManager shaderManager = new ShaderManager();
    private final ViewerSettings viewerSettings = new ViewerSettings();
    private final ModelViewer viewer;

    private final GizmoState gizmoState = new GizmoState();
    private final TransformState gizmoModelTransform = new TransformState();
    private final GizmoRenderer gizmoRenderer;
    private final InstanceTransformTarget transformTarget = new InstanceTransformTarget();
    private final ScenePicker picker = new ScenePicker();
    private final SceneViewportInput viewportInput;

    /** What the outline pass draws; the host points this at the selection. */
    private Supplier<List<ModelInstance>> outlinedInstances = List::of;

    /** The scene's own undo stack — separate from the model editor's. */
    private final com.openmason.main.systems.services.commands.ModelCommandHistory commandHistory =
            new com.openmason.main.systems.services.commands.ModelCommandHistory();

    private boolean glInitialized = false;

    public SceneViewerController(SceneDocument document, SceneViewerUIState uiState) {
        this.document = java.util.Objects.requireNonNull(document, "document");
        this.uiState = java.util.Objects.requireNonNull(uiState, "uiState");

        this.viewer = new ModelViewer(shaderManager, /* ownsShaders */ true, viewerSettings);
        // Snapping comes from this viewport's own settings, not the editor's.
        this.gizmoRenderer = new GizmoRenderer(gizmoState, gizmoModelTransform, viewerSettings);
        this.gizmoRenderer.setTransformTarget(transformTarget);
        this.viewportInput = new SceneViewportInput(viewer.camera());
        this.viewportInput.setGizmoRenderer(gizmoRenderer);

        // Scene instances have no other undo mechanism (unlike the editor's parts), so
        // InstanceTransformTarget opts into drag recording (recordsDragsForUndo) and the
        // gizmo reports finished drags here. The bridge also marks the scene dirty —
        // commit is the only point that observes a drag ending. Follower start poses are
        // still available at commit (the gizmo records before it ends the drag), which is
        // what lets a group drag land in the history as one entry.
        this.gizmoRenderer.setUndoSink(new SceneGizmoUndoBridge(
                commandHistory, transformTarget::instance,
                document.scene()::byId, document::markDirty,
                transformTarget::followerStarts));
    }

    // ------------------------------------------------------------- lifecycle

    public void initialize() {
        if (glInitialized) {
            return;
        }
        try {
            shaderManager.initialize();
            viewer.initialize(uiState.getWidth(), uiState.getHeight());
            gizmoRenderer.initialize();

            viewer.addPass(new GridPass());
            viewer.addPass(new ModelInstancePass(document.scene()));
            viewer.addPass(new SelectionOutlinePass(() -> outlinedInstances.get()));
            viewer.addPass(new GizmoPass(gizmoRenderer));

            glInitialized = true;
            logger.info("Scene viewer initialized");
        } catch (Exception e) {
            logger.error("Scene viewer initialization failed", e);
            cleanup();
            throw new RuntimeException("Scene viewer initialization failed", e);
        }
    }

    public void render() {
        if (!glInitialized) {
            initialize();
        }

        viewerSettings.setGridVisible(uiState.getGridVisible().get());
        viewerSettings.setUnrendered(uiState.getUnrendered().get());
        viewerSettings.setSnapEnabled(uiState.getGridSnappingEnabled().get());
        viewerSettings.setSnapIncrement(uiState.getGridSnappingIncrement().get());
        viewerSettings.setSize(uiState.getWidth(), uiState.getHeight());

        viewer.render(FRAME_DELTA_SECONDS);
    }

    public void resize(int width, int height) {
        if (width <= 0 || height <= 0 || !uiState.dimensionsChanged(width, height)) {
            return;
        }
        uiState.setDimensions(width, height);
        if (glInitialized) {
            viewer.resize(width, height);
        }
    }

    public void update(float deltaTime) {
        viewer.update(deltaTime);
    }

    public void cleanup() {
        try {
            gizmoRenderer.dispose();
        } catch (Exception e) {
            logger.error("Error disposing the scene gizmo", e);
        }
        viewer.close();
        glInitialized = false;
    }

    // --------------------------------------------------------------- picking

    /** Pick at a pixel inside the viewport image. */
    public Optional<PickResult> pickAt(float localX, float localY) {
        return picker.pickScreen(document.scene(), localX, localY,
                uiState.getWidth(), uiState.getHeight(),
                camera().getViewMatrix(), camera().getProjectionMatrix());
    }

    /** Point the gizmo at one instance with no followers; null hides it. */
    public void setGizmoInstance(ModelInstance instance) {
        setGizmoSelection(instance, List.of());
    }

    /**
     * Point the gizmo at the primary and make the rest of the selection follow it.
     * The outline pass draws the whole group.
     */
    public void setGizmoSelection(ModelInstance primary, List<ModelInstance> followers) {
        transformTarget.setInstance(primary);
        transformTarget.setFollowers(followers);
        gizmoState.setEnabled(primary != null);
        // Sizes the handles relative to what is selected — without it the gizmo falls
        // back to a fixed scale and reads as tiny beside a large model.
        gizmoRenderer.updateModelBounds(primary != null
                ? primary.worldBounds()
                : com.openmason.engine.rendering.model.ModelBounds.EMPTY);
    }

    /** Supplies the instances the outline pass highlights, primary first. */
    public void setOutlinedInstances(Supplier<List<ModelInstance>> supplier) {
        this.outlinedInstances = supplier != null ? supplier : List::of;
    }

    /** Frame the camera on an instance, or on the whole scene when null. */
    public void focusOn(ModelInstance instance) {
        var bounds = instance != null ? instance.worldBounds() : document.scene().worldBounds();
        focusOnBounds(bounds);
    }

    /** Frame the camera on the combined bounds of several instances (falls back to the scene). */
    public void focusOn(List<ModelInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            focusOn((ModelInstance) null);
            return;
        }
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        for (ModelInstance instance : instances) {
            var b = instance.worldBounds();
            min.min(b.min());
            max.max(b.max());
        }
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        Vector3f size = new Vector3f(max).sub(min);
        focusOnBounds(new com.openmason.engine.rendering.model.ModelBounds(min, max, center, size));
    }

    private void focusOnBounds(com.openmason.engine.rendering.model.ModelBounds bounds) {
        camera().setTarget(bounds.center());
        float extent = Math.max(bounds.maxExtent(), 0.5f);
        camera().setDistance(extent * 3.0f);
    }

    /** Return the camera to its default orbit. */
    public void resetView() {
        camera().reset();
    }

    // ------------------------------------------------------------ view state

    /** The camera as the file format records it. */
    public OMSCFormat.CameraState captureCameraState() {
        ViewerCamera camera = camera();
        Vector3f target = camera.getTarget();
        return new OMSCFormat.CameraState(
                camera.getCameraMode().name(),
                camera.getDistance(), camera.getPitch(), camera.getYaw(), camera.getFov(),
                target.x, target.y, target.z);
    }

    /** Restore a saved camera; unknown modes fall back to arcball. */
    public void applyCameraState(OMSCFormat.CameraState state) {
        if (state == null) {
            return;
        }
        ViewerCamera camera = camera();
        try {
            camera.setCameraMode(ViewerCamera.CameraMode.valueOf(state.mode()));
        } catch (IllegalArgumentException e) {
            camera.setCameraMode(ViewerCamera.CameraMode.ARCBALL);
        }
        camera.setTarget(new Vector3f(state.targetX(), state.targetY(), state.targetZ()));
        camera.setDistance(state.distance());
        camera.setPitch(state.pitch());
        camera.setYaw(state.yaw());
        if (state.fov() > 0) {
            camera.setFov(state.fov());
        }
        camera.updateMatrices();
    }

    /** The display toggles as the file format records them. */
    public OMSCFormat.ViewportState captureViewportState() {
        return new OMSCFormat.ViewportState(
                0, 0,
                uiState.getGridVisible().get(),
                uiState.getAxesVisible().get(),
                uiState.getUnrendered().get(),
                false,
                gizmoState.isEnabled(),
                uiState.getGridSnappingEnabled().get(),
                uiState.getGridSnappingIncrement().get());
    }

    public void applyViewportState(OMSCFormat.ViewportState state) {
        if (state == null) {
            return;
        }
        uiState.getGridVisible().set(state.gridVisible());
        uiState.getAxesVisible().set(state.axesVisible());
        uiState.getUnrendered().set(state.unrenderedMode());
        uiState.getGridSnappingEnabled().set(state.gridSnappingEnabled());
        if (state.gridSnappingIncrement() > 0) {
            uiState.getGridSnappingIncrement().set(state.gridSnappingIncrement());
        }
    }

    /**
     * Apply the user's camera preferences. Called at startup and again whenever
     * Preferences changes them, so both 3D surfaces navigate identically — the settings
     * are user preferences, not a property of one viewport.
     */
    public void applyCameraPreferences(float orbitSensitivity, float panSensitivity) {
        ViewerCamera camera = viewer.camera();
        if (camera != null) {
            camera.setMouseSensitivity(orbitSensitivity);
            camera.setPanSensitivity(panSensitivity);
        }
    }

    // ------------------------------------------------------------- accessors

    public SceneViewportInput viewportInput() { return viewportInput; }
    public com.openmason.main.systems.services.commands.ModelCommandHistory commandHistory() {
        return commandHistory;
    }
    public ViewerCamera camera() { return viewer.camera(); }
    public ModelViewer viewer() { return viewer; }
    public GizmoState gizmoState() { return gizmoState; }
    public GizmoRenderer gizmoRenderer() { return gizmoRenderer; }
    public InstanceTransformTarget transformTarget() { return transformTarget; }
    public int getColorTexture() { return viewer.colorTexture(); }
    public boolean isInitialized() { return glInitialized; }
}
