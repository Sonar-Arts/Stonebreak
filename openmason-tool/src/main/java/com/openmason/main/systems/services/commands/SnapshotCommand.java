package com.openmason.main.systems.services.commands;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;

/**
 * Snapshot-based command for topology-changing operations.
 *
 * <p>Captures the full mesh state before and after an operation.
 * Used for subdivision, knife cuts, edge insertion, face deletion,
 * and face creation — any operation that changes the index array
 * or face mapping.
 *
 * <p>Not mergeable — each topology change is a discrete step.
 */
public final class SnapshotCommand implements ModelCommand {

    private final MeshSnapshot before;
    private final MeshSnapshot after;
    private final String description;
    private final GenericModelRenderer gmr;
    private final RendererSynchronizer synchronizer;

    private SnapshotCommand(MeshSnapshot before,
                            MeshSnapshot after,
                            String description,
                            GenericModelRenderer gmr,
                            RendererSynchronizer synchronizer) {
        this.before = before;
        this.after = after;
        this.description = description;
        this.gmr = gmr;
        this.synchronizer = synchronizer;
    }

    @Override
    public void execute() {
        after.restore(gmr);
        synchronizer.synchronize();
    }

    @Override
    public void undo() {
        before.restore(gmr);
        synchronizer.synchronize();
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
        throw new UnsupportedOperationException("SnapshotCommand is not mergeable");
    }

    // ── Factory methods ─────────────────────────────────────────────────

    public static SnapshotCommand subdivision(MeshSnapshot before, MeshSnapshot after,
                                              GenericModelRenderer gmr, RendererSynchronizer sync) {
        return new SnapshotCommand(before, after, "Subdivide Edge", gmr, sync);
    }

    public static SnapshotCommand knifeCut(MeshSnapshot before, MeshSnapshot after,
                                           GenericModelRenderer gmr, RendererSynchronizer sync) {
        return new SnapshotCommand(before, after, "Knife Cut", gmr, sync);
    }

    public static SnapshotCommand edgeInsertion(MeshSnapshot before, MeshSnapshot after,
                                                GenericModelRenderer gmr, RendererSynchronizer sync) {
        return new SnapshotCommand(before, after, "Insert Edge", gmr, sync);
    }

    public static SnapshotCommand faceDeletion(MeshSnapshot before, MeshSnapshot after,
                                               GenericModelRenderer gmr, RendererSynchronizer sync) {
        return new SnapshotCommand(before, after, "Delete Face", gmr, sync);
    }

    public static SnapshotCommand faceCreation(MeshSnapshot before, MeshSnapshot after,
                                               GenericModelRenderer gmr, RendererSynchronizer sync) {
        return new SnapshotCommand(before, after, "Create Face", gmr, sync);
    }

    public static SnapshotCommand vertexMerge(MeshSnapshot before, MeshSnapshot after,
                                              GenericModelRenderer gmr, RendererSynchronizer sync) {
        return new SnapshotCommand(before, after, "Move + Merge Vertices", gmr, sync);
    }
}
