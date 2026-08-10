package com.openmason.engine.rendering.viewer.gizmo;

import org.joml.Vector3f;

/**
 * Where a completed model-level gizmo drag goes to become an undo entry.
 *
 * <p>The gizmo used to build {@code GizmoTransformCommand}s and push them into
 * {@code ModelCommandHistory} directly, which tied a reusable widget to the model
 * editor's undo stack. It now reports "a drag finished, here is before and after" and
 * lets the host decide whether that is undoable and how — the scene viewer will record
 * instance moves in its own history.
 *
 * <p>Only model-level drags are reported; part-level undo is handled by the part system.
 */
@FunctionalInterface
public interface TransformUndoSink {

    /** Modes a drag can complete in. Mirrors the gizmo's own mode enum. */
    enum Mode { TRANSLATE, ROTATE, SCALE }

    /**
     * A drag completed and the values actually changed.
     *
     * @param mode     which handle was dragged
     * @param oldPos   position before the drag
     * @param oldRot   rotation before the drag (Euler degrees)
     * @param oldScale scale before the drag
     * @param newPos   position after the drag
     * @param newRot   rotation after the drag (Euler degrees)
     * @param newScale scale after the drag
     */
    void onTransformCommitted(Mode mode,
                              Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                              Vector3f newPos, Vector3f newRot, Vector3f newScale);

    /** Discards commits — used when a host wants no undo integration. */
    TransformUndoSink NONE = (mode, oldPos, oldRot, oldScale, newPos, newRot, newScale) -> { };
}
