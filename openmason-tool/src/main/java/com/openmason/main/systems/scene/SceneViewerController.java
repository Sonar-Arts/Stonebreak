package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.shaders.ShaderManager;
import com.openmason.engine.rendering.viewer.ModelViewer;
import com.openmason.engine.rendering.viewer.ViewerSettings;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.gizmo.rendering.GizmoRenderer;
import com.openmason.engine.rendering.viewer.passes.GizmoPass;
import com.openmason.engine.rendering.viewer.passes.GridPass;
import com.openmason.engine.rendering.viewer.passes.ModelInstancePass;
import com.openmason.engine.rendering.viewer.picking.PickResult;
import com.openmason.engine.rendering.viewer.picking.ScenePicker;
import com.openmason.engine.rendering.viewer.scene.InstanceTransformTarget;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The Scene Viewer's 3D surface: a second {@link ModelViewer}, independent of the model
 * editor's.
 *
 * <p>Registers only the shared engine passes — grid, model instances, gizmo — so it never
 * touches the editor's mesh-editing machinery (and therefore never touches the
 * process-global edit-mode state that machinery relies on).
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
        // commit is the only point that observes a drag ending.
        this.gizmoRenderer.setUndoSink(new SceneGizmoUndoBridge(
                commandHistory, transformTarget::instance,
                document.scene()::byId, document::markDirty));
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

    /** Point the gizmo at an instance; null hides it. */
    public void setGizmoInstance(ModelInstance instance) {
        transformTarget.setInstance(instance);
        gizmoState.setEnabled(instance != null);
        // Sizes the handles relative to what is selected — without it the gizmo falls
        // back to a fixed scale and reads as tiny beside a large model.
        gizmoRenderer.updateModelBounds(instance != null
                ? instance.worldBounds()
                : com.openmason.engine.rendering.model.ModelBounds.EMPTY);
    }

    /** Frame the camera on an instance, or on the whole scene when null. */
    public void focusOn(ModelInstance instance) {
        var bounds = instance != null ? instance.worldBounds() : document.scene().worldBounds();
        camera().setTarget(bounds.center());
        float extent = Math.max(bounds.maxExtent(), 0.5f);
        camera().setDistance(extent * 3.0f);
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
    public int getColorTexture() { return viewer.colorTexture(); }
    public boolean isInitialized() { return glInitialized; }
}
