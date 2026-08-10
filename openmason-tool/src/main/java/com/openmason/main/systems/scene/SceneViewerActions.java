package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Everything the Scene Viewer's UI can do, in one place.
 *
 * <p>The views call these rather than mutating the document directly, so selection,
 * gizmo state and the dirty flag stay consistent no matter which surface triggered the
 * change (viewport click, outliner row, or toolbar button).
 */
public class SceneViewerActions {

    private static final Logger logger = LoggerFactory.getLogger(SceneViewerActions.class);

    /** Offset applied to a duplicate so it is visibly beside the original, not inside it. */
    private static final float DUPLICATE_OFFSET = 1.0f;

    private final SceneService sceneService;
    private final SceneDocument document;
    private final SceneSelectionState selection;
    private final SceneViewerController controller;

    private Supplier<Path> projectRootSupplier = () -> null;
    private Runnable onAddModelRequested = () -> { };
    private Consumer<String> onEditModelRequested = path -> { };

    public SceneViewerActions(SceneService sceneService, SceneDocument document,
                              SceneSelectionState selection, SceneViewerController controller) {
        this.sceneService = sceneService;
        this.document = document;
        this.selection = selection;
        this.controller = controller;
    }

    public void setProjectRootSupplier(Supplier<Path> supplier) {
        this.projectRootSupplier = supplier != null ? supplier : () -> null;
    }

    /** Wired to a file dialog that picks a .omo to place. */
    public void setOnAddModelRequested(Runnable callback) {
        this.onAddModelRequested = callback != null ? callback : () -> { };
    }

    /** Wired to the model editor: opens the given .omo for part-level editing. */
    public void setOnEditModelRequested(Consumer<String> callback) {
        this.onEditModelRequested = callback != null ? callback : path -> { };
    }

    // -------------------------------------------------------------- queries

    public boolean hasSelection() {
        return !selection.isEmpty();
    }

    private List<ModelInstance> selected() {
        return selection.resolve(document.instances());
    }

    private ModelInstance primary() {
        String id = selection.primary();
        return id == null ? null : document.scene().byId(id);
    }

    // ------------------------------------------------------------- mutations

    public void requestAddModel() {
        onAddModelRequested.run();
    }

    /** Place a model that has already been loaded into the document. */
    public ModelInstance place(SceneModelRef model, String name, float x, float y, float z) {
        ModelInstance instance = sceneService.placeInstance(model, name, x, y, z);
        selection.select(instance.id());
        syncGizmoToSelection();
        return instance;
    }

    public void duplicateSelected() {
        List<ModelInstance> targets = selected();
        if (targets.isEmpty()) {
            return;
        }
        ModelInstance last = null;
        for (ModelInstance source : targets) {
            last = document.duplicateInstance(source, DUPLICATE_OFFSET);
        }
        if (last != null) {
            selection.select(last.id());
            syncGizmoToSelection();
            markDirty();
        }
    }

    public void deleteSelected() {
        List<ModelInstance> targets = selected();
        if (targets.isEmpty()) {
            return;
        }
        for (ModelInstance instance : targets) {
            if (instance.isLocked()) {
                continue; // a locked instance is protected from deletion too
            }
            document.removeInstance(instance);
            selection.remove(instance.id());
        }
        syncGizmoToSelection();
        markDirty();
    }

    public void focusSelected() {
        controller.focusOn(primary());
    }

    /** Keep the gizmo pointed at whatever the primary selection is now. */
    public void syncGizmoToSelection() {
        controller.setGizmoInstance(primary());
    }

    public void importMissingModels() {
        Path root = projectRootSupplier.get();
        if (root == null) {
            logger.warn("Cannot import models: no project is open");
            return;
        }
        int imported = sceneService.importMissingModelsToProject(root);
        logger.info("Imported {} model(s) into the project", imported);
    }

    /** Open the selected instance's source model in the Model Editor. */
    public void editSelectedModel() {
        ModelInstance instance = primary();
        if (instance == null) {
            return;
        }
        SceneModelRef ref = document.modelFor(instance);
        if (ref == null || ref.sourcePath() == null) {
            logger.warn("'{}' has no file to edit — import it into the project first", instance.name());
            return;
        }
        onEditModelRequested.accept(ref.sourcePath().toString());
    }

    /** Current gizmo mode (translate / rotate / scale). */
    public com.openmason.engine.rendering.viewer.gizmo.GizmoState.Mode gizmoMode() {
        return controller.gizmoState().getCurrentMode();
    }

    public void setGizmoMode(com.openmason.engine.rendering.viewer.gizmo.GizmoState.Mode mode) {
        controller.gizmoState().setCurrentMode(mode);
    }

    public boolean canUndo() {
        return controller.commandHistory().canUndo();
    }

    public boolean canRedo() {
        return controller.commandHistory().canRedo();
    }

    /** Undo the last gizmo transform in this scene. */
    public void undo() {
        if (!canUndo()) {
            return;
        }
        controller.commandHistory().undo();
        syncGizmoToSelection();
        markDirty();
    }

    public void redo() {
        if (!canRedo()) {
            return;
        }
        controller.commandHistory().redo();
        syncGizmoToSelection();
        markDirty();
    }

    public void markDirty() {
        sceneService.markDirty();
    }
}
