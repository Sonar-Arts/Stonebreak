package com.openmason.engine.rendering.viewer.gizmo.interaction;

import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import com.openmason.engine.rendering.viewer.transform.TransformLimits;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which gizmo drags become undo entries.
 *
 * <p>The decision is per-target ({@link ITransformTarget#recordsDragsForUndo()}): one
 * gizmo swaps between targets whose undo stories differ. Reporting an editor part/bone/
 * socket target to a sink that writes the model-level transform would undo the wrong
 * thing, while <em>not</em> reporting a scene's instance target would leave instance
 * moves un-undoable.
 */
class UndoRedoRecorderTest {

    /** Captures what the recorder decided to report. */
    private record Commit(TransformUndoSink.Mode mode, Vector3f newPos) {}

    private static final class CapturingSink implements TransformUndoSink {
        final List<Commit> commits = new ArrayList<>();

        @Override
        public void onTransformCommitted(Mode mode, Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                         Vector3f newPos, Vector3f newRot, Vector3f newScale) {
            commits.add(new Commit(mode, new Vector3f(newPos)));
        }
    }

    /** Minimal target that reports a fixed transform. */
    private static final class StubTarget implements ITransformTarget {
        private final Vector3f position;
        private final boolean recordsDrags;

        StubTarget(Vector3f position) {
            this(position, false);
        }

        StubTarget(Vector3f position, boolean recordsDrags) {
            this.position = position;
            this.recordsDrags = recordsDrags;
        }

        @Override public boolean recordsDragsForUndo() { return recordsDrags; }
        @Override public Vector3f getPosition() { return new Vector3f(position); }
        @Override public Vector3f getRotation() { return new Vector3f(); }
        @Override public Vector3f getScale() { return new Vector3f(1, 1, 1); }
        @Override public void setPosition(float x, float y, float z) { position.set(x, y, z); }
        @Override public void setPosition(float x, float y, float z, boolean s, float i) { setPosition(x, y, z); }
        @Override public void setRotation(float x, float y, float z) { }
        @Override public void setScale(float x, float y, float z) { }
        @Override public Vector3f getWorldCenter() { return new Vector3f(position); }
        @Override public boolean isActive() { return true; }
        @Override public String getTargetName() { return "Stub"; }
    }

    /** A gizmo state whose drag started at the origin, on the X translation arrow. */
    private static GizmoState draggedFromOrigin() {
        GizmoPart part = new GizmoPart(AxisConstraint.X, new Vector3f(1, 0, 0),
                GizmoPart.PartType.ARROW, new Vector3f(), 0.2f);
        GizmoState state = new GizmoState();
        state.startDrag(part, 0f, 0f, new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1));
        return state;
    }

    @Test
    @DisplayName("a target that does not opt in is NOT reported — the editor part/bone/socket case")
    void nonOptingTargetSkipped() {
        UndoRedoRecorder recorder = new UndoRedoRecorder();
        CapturingSink sink = new CapturingSink();
        recorder.setUndoSink(sink);

        recorder.recordIfChanged(draggedFromOrigin(),
                new StubTarget(new Vector3f(5, 0, 0)), new TransformState());

        assertTrue(sink.commits.isEmpty(),
                "reporting would record into a transform the drag never touched");
    }

    @Test
    @DisplayName("a target that opts in via recordsDragsForUndo IS reported — the scene case")
    void optingTargetReported() {
        UndoRedoRecorder recorder = new UndoRedoRecorder();
        CapturingSink sink = new CapturingSink();
        recorder.setUndoSink(sink);

        recorder.recordIfChanged(draggedFromOrigin(),
                new StubTarget(new Vector3f(5, 0, 0), true), new TransformState());

        assertEquals(1, sink.commits.size());
        assertEquals(new Vector3f(5, 0, 0), sink.commits.getFirst().newPos(),
                "the result must be read from the target that actually moved");
    }

    @Test
    @DisplayName("a model-level drag (no active target) is always reported")
    void modelLevelAlwaysReported() {
        UndoRedoRecorder recorder = new UndoRedoRecorder();
        CapturingSink sink = new CapturingSink();
        recorder.setUndoSink(sink);

        TransformState model = new TransformState(TransformLimits.UNBOUNDED);
        model.setPosition(3, 0, 0);

        recorder.recordIfChanged(draggedFromOrigin(), null, model);

        assertEquals(1, sink.commits.size());
        assertEquals(new Vector3f(3, 0, 0), sink.commits.getFirst().newPos());
    }

    @Test
    @DisplayName("a drag that changed nothing is not reported")
    void unchangedDragNotReported() {
        UndoRedoRecorder recorder = new UndoRedoRecorder();
        CapturingSink sink = new CapturingSink();
        recorder.setUndoSink(sink);

        // Target still sits where the drag started.
        recorder.recordIfChanged(draggedFromOrigin(),
                new StubTarget(new Vector3f(), true), new TransformState());

        assertTrue(sink.commits.isEmpty(), "a click without movement must not create an undo entry");
    }

    @Test
    @DisplayName("no sink means no work and no crash")
    void withoutSinkIsSafe() {
        UndoRedoRecorder recorder = new UndoRedoRecorder();
        recorder.setUndoSink(null);

        recorder.recordIfChanged(draggedFromOrigin(),
                new StubTarget(new Vector3f(5, 0, 0), true), new TransformState());
    }
}
