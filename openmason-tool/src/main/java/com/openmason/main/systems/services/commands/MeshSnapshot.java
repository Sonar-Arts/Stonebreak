package com.openmason.main.systems.services.commands;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.editable.EditableMeshSnapshot;

/**
 * Tool-side handle on the engine's exact mesh snapshot
 * ({@link EditableMeshSnapshot}) for snapshot-based undo/redo.
 *
 * <p>Capture and restore are exact: the authoritative editable mesh plus
 * per-face texture mappings and materials are deep-copied, and restoring
 * never re-welds — coincident vertices stay distinct across an undo.
 * Part GEOMETRY is not captured (same as the legacy array snapshot), so
 * commands spanning part-structure changes keep using part-driven paths.
 *
 * @param snapshot The captured engine snapshot
 */
public record MeshSnapshot(EditableMeshSnapshot snapshot) {

    /**
     * Capture the current state of a GenericModelRenderer.
     *
     * @param gmr The renderer to snapshot
     * @return An independent snapshot of all mesh + face texture state
     */
    public static MeshSnapshot capture(GenericModelRenderer gmr) {
        return new MeshSnapshot(gmr.captureSnapshot());
    }

    /**
     * Restore this snapshot's state into the given renderer (exact restore).
     *
     * @param gmr The renderer to restore into
     */
    public void restore(GenericModelRenderer gmr) {
        gmr.restoreSnapshot(snapshot);
    }
}
