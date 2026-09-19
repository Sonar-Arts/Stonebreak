package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.services.commands.ModelCommand;

/**
 * Undoable add or remove of one scene instance.
 *
 * <p>Holds the instance object itself rather than an id: removal takes it out of the
 * scene, so there is nothing to resolve by id until undo puts it back. Re-inserting the
 * <em>same</em> object at its old index keeps its id, so every other history entry that
 * refers to it by id (transform, property) works again after an undo-delete — which is
 * why a delete no longer purges those entries.
 */
final class SceneInstanceLifecycleCommand implements ModelCommand {

    enum Kind { ADD, REMOVE }

    private final SceneDocument document;
    private final ModelInstance instance;
    private final Kind kind;
    private final String description;

    /** Scene-order index the instance occupies when present, so undo restores order. */
    private int index;

    SceneInstanceLifecycleCommand(SceneDocument document, ModelInstance instance, Kind kind,
                                  int index, String description) {
        this.document = java.util.Objects.requireNonNull(document, "document");
        this.instance = java.util.Objects.requireNonNull(instance, "instance");
        this.kind = kind;
        this.index = index;
        this.description = description;
    }

    static SceneInstanceLifecycleCommand added(SceneDocument document, ModelInstance instance, String description) {
        return new SceneInstanceLifecycleCommand(document, instance, Kind.ADD,
                document.scene().indexOf(instance), description);
    }

    static SceneInstanceLifecycleCommand removed(SceneDocument document, ModelInstance instance, String description) {
        return new SceneInstanceLifecycleCommand(document, instance, Kind.REMOVE,
                document.scene().indexOf(instance), description);
    }

    String instanceId() {
        return instance.id();
    }

    ModelInstance instance() {
        return instance;
    }

    @Override
    public void execute() {
        if (kind == Kind.ADD) {
            insert();
        } else {
            take();
        }
    }

    @Override
    public void undo() {
        if (kind == Kind.ADD) {
            take();
        } else {
            insert();
        }
    }

    private void insert() {
        document.insertInstance(index, instance);
    }

    private void take() {
        int at = document.scene().indexOf(instance);
        if (at >= 0) {
            index = at;
        }
        document.removeInstance(instance);
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("SceneInstanceLifecycleCommand is not mergeable");
    }
}
