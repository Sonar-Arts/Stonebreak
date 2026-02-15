package com.openmason.main.systems.services.commands;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;

/**
 * Delta-based command for vertex, edge, and face translation.
 *
 * <p>Stores per-vertex old/new positions. The same class is used for all three
 * drag types — only the description differs (DRY principle).
 *
 * <p>Consecutive drags of the same vertex set are merged into a single undo step
 * via {@link #canMergeWith} / {@link #mergeWith}.
 */
public final class VertexMoveCommand implements ModelCommand {

    /**
     * Per-vertex position delta.
     */
    public record VertexDelta(int index, Vector3f oldPos, Vector3f newPos) {}

    private final Map<Integer, VertexDelta> deltas;
    private final String description;
    private final GenericModelRenderer gmr;
    private final RendererSynchronizer synchronizer;

    public VertexMoveCommand(Map<Integer, VertexDelta> deltas,
                             String description,
                             GenericModelRenderer gmr,
                             RendererSynchronizer synchronizer) {
        this.deltas = Map.copyOf(deltas);
        this.description = description;
        this.gmr = gmr;
        this.synchronizer = synchronizer;
    }

    @Override
    public void execute() {
        for (VertexDelta delta : deltas.values()) {
            gmr.updateVertexPosition(delta.index(), new Vector3f(delta.newPos()));
        }
        synchronizer.synchronize();
    }

    @Override
    public void undo() {
        for (VertexDelta delta : deltas.values()) {
            gmr.updateVertexPosition(delta.index(), new Vector3f(delta.oldPos()));
        }
        synchronizer.synchronize();
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        if (!(other instanceof VertexMoveCommand otherMove)) {
            return false;
        }
        return deltas.keySet().equals(otherMove.deltas.keySet());
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        VertexMoveCommand otherMove = (VertexMoveCommand) other;

        // Keep this command's old positions, take other's new positions
        Map<Integer, VertexDelta> merged = new java.util.HashMap<>();
        for (Map.Entry<Integer, VertexDelta> entry : deltas.entrySet()) {
            int idx = entry.getKey();
            VertexDelta mine = entry.getValue();
            VertexDelta theirs = otherMove.deltas.get(idx);
            merged.put(idx, new VertexDelta(idx, mine.oldPos(), theirs.newPos()));
        }

        return new VertexMoveCommand(merged, description, gmr, synchronizer);
    }

    /**
     * Get the set of vertex indices affected by this command.
     */
    public Set<Integer> getAffectedVertexIndices() {
        return deltas.keySet();
    }
}
