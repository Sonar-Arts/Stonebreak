package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.services.commands.ModelCommand;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Everything the Scene Viewer's UI can do, in one place.
 *
 * <p>The views call these rather than mutating the document directly, so selection,
 * gizmo state, the undo history and the dirty flag stay consistent no matter which
 * surface triggered the change (viewport click, outliner row, toolbar button, shortcut,
 * or an MCP tool).
 *
 * <p>Every mutation is recorded in the scene's command history: placement, delete and
 * duplicate as lifecycle entries, rename / visibility / lock as property entries, typed
 * transforms as transform entries — the same kind a gizmo drag records.
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
        // The outline pass follows the selection, primary first.
        this.controller.setOutlinedInstances(this::selectedPrimaryFirst);
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

    /** Selected instances in scene order. */
    public List<ModelInstance> selected() {
        return selection.resolve(document.instances());
    }

    /** Selected instances with the primary first — the order the outline pass wants. */
    private List<ModelInstance> selectedPrimaryFirst() {
        List<ModelInstance> ordered = selected();
        ModelInstance primary = primary();
        if (primary == null || ordered.isEmpty() || ordered.get(0) == primary) {
            return ordered;
        }
        List<ModelInstance> out = new ArrayList<>(ordered.size());
        out.add(primary);
        for (ModelInstance instance : ordered) {
            if (instance != primary) {
                out.add(instance);
            }
        }
        return out;
    }

    public ModelInstance primary() {
        String id = selection.primary();
        return id == null ? null : document.scene().byId(id);
    }

    // ------------------------------------------------------------ selection

    public void selectAll() {
        selection.clear();
        for (ModelInstance instance : document.instances()) {
            selection.toggle(instance.id());
        }
        syncGizmoToSelection();
    }

    public void clearSelection() {
        selection.clear();
        syncGizmoToSelection();
    }

    /** Replace the selection with one instance (outliner / MCP). */
    public void select(String instanceId) {
        selection.select(instanceId);
        syncGizmoToSelection();
    }

    /** Keep the gizmo pointed at the primary and the outline on the whole selection. */
    public void syncGizmoToSelection() {
        ModelInstance primary = primary();
        List<ModelInstance> followers = new ArrayList<>();
        for (ModelInstance instance : selected()) {
            if (instance != primary) {
                followers.add(instance);
            }
        }
        controller.setGizmoSelection(primary, followers);
    }

    // ------------------------------------------------------------- mutations

    public void requestAddModel() {
        onAddModelRequested.run();
    }

    /** Place a model that has already been loaded into the document. */
    public ModelInstance place(SceneModelRef model, String name, float x, float y, float z) {
        ModelInstance instance = sceneService.placeInstance(model, name, x, y, z);
        controller.commandHistory().pushCompleted(
                SceneInstanceLifecycleCommand.added(document, instance, "Place " + instance.name()));
        selection.select(instance.id());
        syncGizmoToSelection();
        return instance;
    }

    public void duplicateSelected() {
        List<ModelInstance> targets = selected();
        if (targets.isEmpty()) {
            return;
        }
        List<ModelCommand> parts = new ArrayList<>();
        selection.clear();
        for (ModelInstance source : targets) {
            ModelInstance copy = document.duplicateInstance(source, DUPLICATE_OFFSET);
            parts.add(SceneInstanceLifecycleCommand.added(document, copy, "Duplicate " + source.name()));
            selection.toggle(copy.id());
        }
        pushCompleted(parts, parts.size() == 1 ? parts.get(0).getDescription() : "Duplicate Instances");
        syncGizmoToSelection();
        markDirty();
    }

    public void deleteSelected() {
        List<ModelInstance> targets = selected();
        if (targets.isEmpty()) {
            return;
        }
        List<ModelCommand> parts = new ArrayList<>();
        for (ModelInstance instance : targets) {
            if (instance.isLocked()) {
                continue; // a locked instance is protected from deletion too
            }
            SceneInstanceLifecycleCommand removal =
                    SceneInstanceLifecycleCommand.removed(document, instance, "Delete " + instance.name());
            removal.execute();
            parts.add(removal);
            selection.remove(instance.id());
        }
        if (parts.isEmpty()) {
            return; // everything selected was locked — nothing changed
        }
        // Earlier entries for these instances stay: undoing the delete puts the same
        // objects (same ids) back, and those entries become meaningful again.
        pushCompleted(parts, parts.size() == 1 ? parts.get(0).getDescription() : "Delete Instances");
        syncGizmoToSelection();
        markDirty();
    }

    public void rename(ModelInstance instance, String newName) {
        if (instance == null || newName == null) {
            return;
        }
        String trimmed = newName.trim();
        if (trimmed.isEmpty() || trimmed.equals(instance.name())) {
            return;
        }
        execute(new SceneInstancePropertyCommand(instance.id(), document.scene()::byId,
                SceneInstancePropertyCommand.Property.NAME, instance.name(), trimmed));
    }

    public void setVisible(ModelInstance instance, boolean visible) {
        if (instance == null || instance.isVisible() == visible) {
            return;
        }
        execute(new SceneInstancePropertyCommand(instance.id(), document.scene()::byId,
                SceneInstancePropertyCommand.Property.VISIBLE, instance.isVisible(), visible));
    }

    public void setLocked(ModelInstance instance, boolean locked) {
        if (instance == null || instance.isLocked() == locked) {
            return;
        }
        execute(new SceneInstancePropertyCommand(instance.id(), document.scene()::byId,
                SceneInstancePropertyCommand.Property.LOCKED, instance.isLocked(), locked));
        // Locking the primary takes it away from the gizmo; unlocking gives it back.
        syncGizmoToSelection();
    }

    /**
     * Record a transform edit that was applied live (inspector drag, MCP call) as one
     * undo entry, from the pose before the edit to the instance's pose now.
     */
    public void commitTransform(ModelInstance instance,
                                Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                String description) {
        if (instance == null) {
            return;
        }
        var t = instance.transform();
        Vector3f newPos = new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ());
        Vector3f newRot = new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ());
        Vector3f newScale = new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ());
        if (newPos.equals(oldPos, 1e-6f) && newRot.equals(oldRot, 1e-6f) && newScale.equals(oldScale, 1e-6f)) {
            return;
        }
        controller.commandHistory().pushCompleted(new SceneInstanceTransformCommand(
                instance.id(), document.scene()::byId, description,
                oldPos, oldRot, oldScale, newPos, newRot, newScale));
        syncGizmoToSelection();
        markDirty();
    }

    /** Set an instance's full transform as one undoable step. */
    public void setTransform(ModelInstance instance, Vector3f position, Vector3f rotation, Vector3f scale) {
        if (instance == null || instance.isLocked()) {
            return;
        }
        var t = instance.transform();
        Vector3f oldPos = new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ());
        Vector3f oldRot = new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ());
        Vector3f oldScale = new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ());
        if (position != null) t.setPosition(position.x, position.y, position.z);
        if (rotation != null) t.setRotation(rotation.x, rotation.y, rotation.z);
        if (scale != null) t.setScale(scale.x, scale.y, scale.z);
        commitTransform(instance, oldPos, oldRot, oldScale, "Transform " + instance.name());
    }

    // ---------------------------------------------------------------- camera

    /** Frame the selection, or the whole scene when nothing is selected. */
    public void focusSelected() {
        List<ModelInstance> targets = selected();
        if (targets.isEmpty()) {
            controller.focusOn((ModelInstance) null);
        } else {
            controller.focusOn(targets);
        }
    }

    public void frameAll() {
        controller.focusOn((ModelInstance) null);
    }

    public void resetView() {
        controller.resetView();
    }

    // ---------------------------------------------------------------- models

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

    // ----------------------------------------------------------------- gizmo

    /** Current gizmo mode (translate / rotate / scale). */
    public GizmoState.Mode gizmoMode() {
        return controller.gizmoState().getCurrentMode();
    }

    public void setGizmoMode(GizmoState.Mode mode) {
        controller.gizmoState().setCurrentMode(mode);
    }

    /**
     * Shortcut variant: ignored while the camera is in first-person mode, where W/E/R
     * would collide with the WASD fly keys the camera controller polls directly.
     */
    public void setGizmoModeFromShortcut(GizmoState.Mode mode) {
        if (controller.camera().getCameraMode()
                == com.openmason.engine.rendering.viewer.camera.ViewerCamera.CameraMode.FIRST_PERSON) {
            return;
        }
        setGizmoMode(mode);
    }

    // ------------------------------------------------------------------ undo

    public boolean canUndo() {
        return controller.commandHistory().canUndo();
    }

    public boolean canRedo() {
        return controller.commandHistory().canRedo();
    }

    public String undoDescription() {
        return controller.commandHistory().getUndoDescription();
    }

    public String redoDescription() {
        return controller.commandHistory().getRedoDescription();
    }

    /** Undo the last scene edit. */
    public void undo() {
        if (!canUndo()) {
            return;
        }
        controller.commandHistory().undo();
        afterHistoryMove();
    }

    public void redo() {
        if (!canRedo()) {
            return;
        }
        controller.commandHistory().redo();
        afterHistoryMove();
    }

    /** An undo may have removed or restored instances: drop stale ids, re-aim the gizmo. */
    private void afterHistoryMove() {
        for (String id : selection.selectedIds()) {
            if (document.scene().byId(id) == null) {
                selection.remove(id);
            }
        }
        syncGizmoToSelection();
        markDirty();
    }

    public void markDirty() {
        sceneService.markDirty();
    }

    private void execute(ModelCommand command) {
        controller.commandHistory().executeCommand(command);
        markDirty();
    }

    private void pushCompleted(List<ModelCommand> parts, String description) {
        if (parts.size() == 1) {
            controller.commandHistory().pushCompleted(parts.get(0));
        } else {
            controller.commandHistory().pushCompleted(new SceneCompositeCommand(description, parts));
        }
    }
}
