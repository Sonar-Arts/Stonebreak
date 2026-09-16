package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import com.openmason.engine.rendering.viewer.scene.InstanceTransformTarget;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.services.commands.ModelCommand;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records a finished scene gizmo drag as an undo entry, and marks the scene dirty.
 *
 * <p>This is the one place a completed drag is observed, so it also owns the dirty flag
 * for gizmo edits: the drag path itself ({@code InstanceTransformTarget}) writes the
 * transform every frame and must not touch document state. Without this, a scene moved
 * only with the gizmo would close without a save prompt.
 *
 * <p>Entries are {@link SceneInstanceTransformCommand}s — keyed by instance id and
 * resolved against the live scene at undo time — rather than the model editor's
 * {@code GizmoTransformCommand}, which would capture a {@code TransformState} that can
 * outlive its instance. A group drag (multi-selection) records the primary and every
 * follower in one {@link SceneCompositeCommand}, so Ctrl+Z moves the group back together.
 *
 * <p>The dragged instance is resolved at commit time rather than held, because the
 * selection — and therefore which instance the gizmo drives — changes between drags.
 */
public class SceneGizmoUndoBridge implements TransformUndoSink {

    private final ModelCommandHistory history;
    private final Supplier<ModelInstance> selectedInstance;
    private final Function<String, ModelInstance> instanceResolver;
    private final Runnable markDirty;

    /** Follower start poses of the drag being committed; empty when none. */
    private final Supplier<List<InstanceTransformTarget.StartPose>> followerStarts;

    public SceneGizmoUndoBridge(ModelCommandHistory history,
                                Supplier<ModelInstance> selectedInstance,
                                Function<String, ModelInstance> instanceResolver,
                                Runnable markDirty) {
        this(history, selectedInstance, instanceResolver, markDirty, List::of);
    }

    public SceneGizmoUndoBridge(ModelCommandHistory history,
                                Supplier<ModelInstance> selectedInstance,
                                Function<String, ModelInstance> instanceResolver,
                                Runnable markDirty,
                                Supplier<List<InstanceTransformTarget.StartPose>> followerStarts) {
        this.history = java.util.Objects.requireNonNull(history, "history");
        this.selectedInstance = java.util.Objects.requireNonNull(selectedInstance, "selectedInstance");
        this.instanceResolver = java.util.Objects.requireNonNull(instanceResolver, "instanceResolver");
        this.markDirty = java.util.Objects.requireNonNull(markDirty, "markDirty");
        this.followerStarts = followerStarts != null ? followerStarts : List::of;
    }

    @Override
    public void onTransformCommitted(Mode mode,
                                     Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                     Vector3f newPos, Vector3f newRot, Vector3f newScale) {
        ModelInstance instance = selectedInstance.get();
        if (instance == null) {
            return;
        }
        String description = switch (mode) {
            case TRANSLATE -> "Move Instance";
            case ROTATE -> "Rotate Instance";
            case SCALE -> "Scale Instance";
        };

        SceneInstanceTransformCommand primary = new SceneInstanceTransformCommand(
                instance.id(), instanceResolver, description,
                oldPos, oldRot, oldScale, newPos, newRot, newScale);

        List<InstanceTransformTarget.StartPose> starts = followerStarts.get();
        if (starts == null || starts.isEmpty()) {
            history.pushCompleted(primary);
        } else {
            List<ModelCommand> parts = new ArrayList<>(starts.size() + 1);
            parts.add(primary);
            for (InstanceTransformTarget.StartPose start : starts) {
                var t = start.instance().transform();
                parts.add(new SceneInstanceTransformCommand(
                        start.instance().id(), instanceResolver, description,
                        start.position(), start.rotation(), start.scale(),
                        new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ()),
                        new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ()),
                        new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ())));
            }
            history.pushCompleted(new SceneCompositeCommand(
                    description.replace("Instance", "Instances"), parts));
        }
        markDirty.run();
    }
}
