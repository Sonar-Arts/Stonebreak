package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.OmoModelLoader;
import com.openmason.engine.rendering.viewer.scene.PngTextureUploader;
import com.openmason.main.systems.scene.dnd.SceneDropResolver;
import com.openmason.main.systems.scene.views.SceneInspectorImGui;
import com.openmason.main.systems.scene.views.SceneOutlinerImGui;
import com.openmason.main.systems.scene.views.SceneToolbarRenderer;
import com.openmason.main.systems.scene.views.SceneViewerMainView;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the Scene Viewer's windows and the objects behind them.
 *
 * <p>Peer of {@code ViewportImGuiInterface}: rendered from the app's top-level UI pass
 * rather than from inside the dockspace host window, and with its own keybind context so
 * shortcuts never fire in both 3D surfaces at once.
 */
public class SceneViewerImGuiInterface {

    private static final Logger logger = LoggerFactory.getLogger(SceneViewerImGuiInterface.class);

    /** Ground plane scenes are placed on. */
    private static final float GROUND_Y = 0.0f;

    /** Where a drop lands when the ground plane is not usable from this angle. */
    private static final float FALLBACK_DROP_DISTANCE = 12.0f;

    private final ModelCache modelCache;
    private final SceneService sceneService;
    private final SceneDocument document;
    private final SceneSelectionState selection = new SceneSelectionState();
    private final SceneViewerUIState uiState = new SceneViewerUIState();
    private final SceneViewerController controller;
    private final SceneViewerActions actions;

    private final SceneViewerMainView mainView;
    private final SceneOutlinerImGui outliner;
    private final SceneInspectorImGui inspector;

    private final UIVisibilityState uiVisibility;

    private Supplier<Path> projectRootSupplier = () -> null;

    public SceneViewerImGuiInterface(UIVisibilityState uiVisibility) {
        this.uiVisibility = uiVisibility;

        this.modelCache = new ModelCache(
                new OmoModelLoader(new PngTextureUploader()), ModelCache.glDisposer());
        this.sceneService = new SceneService(modelCache);
        this.document = sceneService.getDocument();
        this.controller = new SceneViewerController(document, uiState);
        this.actions = new SceneViewerActions(sceneService, document, selection, controller);

        SceneToolbarRenderer toolbar = new SceneToolbarRenderer(uiState, actions);
        this.mainView = new SceneViewerMainView(uiState, controller, document, selection, toolbar, actions);
        this.outliner = new SceneOutlinerImGui(document, selection, actions,
                uiVisibility.getShowSceneOutliner());
        this.inspector = new SceneInspectorImGui(document, selection, actions,
                uiVisibility.getShowSceneInspector());

        this.mainView.setOnModelDropped(this::onModelDropped);
        // A scene swap (new / open / project change) invalidates any selection: the ids
        // it holds refer to instances that no longer exist.
        this.sceneService.setOnSceneChanged(this::dropStaleSelection);
        this.actions.setProjectRootSupplier(() -> projectRootSupplier.get());

        // Camera navigation follows the same preferences as the model editor.
        var prefs = com.openmason.main.systems.menus.preferences.PreferencesManager.getInstance();
        controller.applyCameraPreferences(
                prefs.getCameraMouseSensitivity(), prefs.getCameraPanSensitivity());

        sceneService.newScene("Untitled Scene");
    }

    /** Supplies the open project's root folder, or null when none is open. */
    public void setProjectRootSupplier(Supplier<Path> supplier) {
        this.projectRootSupplier = supplier != null ? supplier : () -> null;
    }

    /** Wire "Edit Model..." to the model editor. */
    public void setOnEditModelRequested(Consumer<String> callback) {
        actions.setOnEditModelRequested(callback);
    }

    /** Wire "Add Model..." to a file dialog. */
    public void setOnAddModelRequested(Runnable callback) {
        actions.setOnAddModelRequested(callback);
    }

    public void render() {
        if (uiVisibility.getShowSceneViewer().get()) {
            mainView.render();
        } else {
            // Not submitted at all this frame: the view can't clear its own flags.
            uiState.setSceneViewVisible(false);
            uiState.setSceneViewFocused(false);
        }
        outliner.render();
        inspector.render();
    }

    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    public void dispose() {
        controller.cleanup();
        modelCache.close();
    }

    /**
     * Load and place a model dragged in from the Project Browser.
     *
     * <p>The drop position comes from a ray through the cursor onto the ground plane, so
     * the model lands where the user pointed.
     */
    private void onModelDropped(String omoPath, float[] localCursor) {
        try {
            SceneModelRef ref = sceneService.addModelFromFile(
                    Path.of(omoPath), projectRootSupplier.get());

            Vector3f where = SceneDropResolver.resolve(
                    localCursor[0], localCursor[1],
                    uiState.getWidth(), uiState.getHeight(),
                    controller.camera().getViewMatrix(),
                    controller.camera().getProjectionMatrix(),
                    GROUND_Y, FALLBACK_DROP_DISTANCE);

            if (uiState.getGridSnappingEnabled().get()) {
                float increment = uiState.getGridSnappingIncrement().get();
                where.x = com.openmason.engine.rendering.viewer.math.SnappingUtil.snapToGrid(where.x, increment);
                where.z = com.openmason.engine.rendering.viewer.math.SnappingUtil.snapToGrid(where.z, increment);
            }

            String name = ref.sourceName() != null
                    ? ref.sourceName().replaceFirst("(?i)\\.omo$", "")
                    : "Instance";
            actions.place(ref, name, where.x, where.y, where.z);
            logger.info("Placed '{}' at ({}, {}, {})", name, where.x, where.y, where.z);

        } catch (IOException e) {
            logger.error("Could not place {}: {}", omoPath, e.getMessage());
        }
    }

    /** Drop selected ids that no longer resolve, and detach the gizmo if the primary went. */
    private void dropStaleSelection() {
        for (String id : selection.selectedIds()) {
            if (document.scene().byId(id) == null) {
                selection.remove(id);
            }
        }
        String primary = selection.primary();
        controller.setGizmoInstance(primary == null ? null : document.scene().byId(primary));
    }

    // --------------------------------------------------------------- accessors

    /** Re-apply camera preferences after the user changes them. */
    public void applyCameraPreferences(float orbitSensitivity, float panSensitivity) {
        controller.applyCameraPreferences(orbitSensitivity, panSensitivity);
    }

    public SceneService getSceneService() { return sceneService; }
    public SceneViewerUIState getUIState() { return uiState; }
    public SceneViewerController getController() { return controller; }
    public SceneSelectionState getSelection() { return selection; }
    public SceneViewerActions getActions() { return actions; }
}
