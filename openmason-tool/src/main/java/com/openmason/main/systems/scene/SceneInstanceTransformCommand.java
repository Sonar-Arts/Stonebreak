package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.services.commands.ModelCommand;
import org.joml.Vector3f;

import java.util.function.Function;

/**
 * Undoable transform of one scene instance, resolved by id at apply time.
 *
 * <p>Deliberately holds neither the instance nor its {@code TransformState}: the instance
 * can be deleted — or the whole scene swapped — while this entry is still in the history.
 * Resolving through the live scene at execute/undo time means a stale entry degrades to a
 * harmless no-op instead of silently writing into a detached transform, and the command
 * never pins a removed instance in memory. Same identity discipline as
 * {@link SceneSelectionState}.
 *
 * <p>Not mergeable — each gizmo drag is a discrete operation.
 */
final class SceneInstanceTransformCommand implements ModelCommand {

    private final String instanceId;
    private final Function<String, ModelInstance> resolver;
    private final String description;
    private final Vector3f oldPosition;
    private final Vector3f oldRotation;
    private final Vector3f oldScale;
    private final Vector3f newPosition;
    private final Vector3f newRotation;
    private final Vector3f newScale;

    SceneInstanceTransformCommand(String instanceId, Function<String, ModelInstance> resolver,
                                  String description,
                                  Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                  Vector3f newPos, Vector3f newRot, Vector3f newScale) {
        this.instanceId = java.util.Objects.requireNonNull(instanceId, "instanceId");
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.description = description;
        this.oldPosition = new Vector3f(oldPos);
        this.oldRotation = new Vector3f(oldRot);
        this.oldScale = new Vector3f(oldScale);
        this.newPosition = new Vector3f(newPos);
        this.newRotation = new Vector3f(newRot);
        this.newScale = new Vector3f(newScale);
    }

    /** The instance this entry belongs to — lets a delete purge its history. */
    String instanceId() {
        return instanceId;
    }

    @Override
    public void execute() {
        apply(newPosition, newRotation, newScale);
    }

    @Override
    public void undo() {
        apply(oldPosition, oldRotation, oldScale);
    }

    private void apply(Vector3f position, Vector3f rotation, Vector3f scale) {
        ModelInstance instance = resolver.apply(instanceId);
        if (instance == null) {
            return; // instance no longer in the scene — nothing to write into
        }
        var t = instance.transform();
        t.setPosition(position.x, position.y, position.z);
        t.setRotation(rotation.x, rotation.y, rotation.z);
        t.setScale(scale.x, scale.y, scale.z);
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
        throw new UnsupportedOperationException("SceneInstanceTransformCommand is not mergeable");
    }
}
