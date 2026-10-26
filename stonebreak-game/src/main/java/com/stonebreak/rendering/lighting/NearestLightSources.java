package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.mobs.entities.Entity;
import java.util.Arrays;

/**
 * Bounded, reusable nearest-source selection; no full-world sorting or candidate allocations.
 * A source is either a placed light block or an entity holding a light (another player's torch).
 */
final class NearestLightSources {
    private final BlockPos[] positions;
    private final Entity[] holders;
    private final float[] distances;
    private int size;
    private int limit;

    NearestLightSources(int capacity) {
        positions = new BlockPos[capacity];
        holders = new Entity[capacity];
        distances = new float[capacity];
    }

    void clear(int limit) {
        if (limit < 0 || limit > positions.length) throw new IllegalArgumentException("Invalid limit");
        Arrays.fill(positions, null);
        Arrays.fill(holders, null);
        size = 0;
        this.limit = limit;
    }

    void offer(BlockPos position, float distanceSquared) {
        insert(position, null, distanceSquared);
    }

    /** An entity carrying a light, ranked by distance with the placed sources. */
    void offer(Entity holder, float distanceSquared) {
        insert(null, holder, distanceSquared);
    }

    private void insert(BlockPos position, Entity holder, float distanceSquared) {
        int at = size;
        while (at > 0 && comesBefore(position, holder, distanceSquared, at - 1)) at--;
        if (at >= limit) return;
        int moved = Math.min(size, limit - 1) - at;
        System.arraycopy(positions, at, positions, at + 1, moved);
        System.arraycopy(holders, at, holders, at + 1, moved);
        System.arraycopy(distances, at, distances, at + 1, moved);
        positions[at] = position;
        holders[at] = holder;
        distances[at] = distanceSquared;
        size = Math.min(size + 1, limit);
    }

    private boolean comesBefore(BlockPos p, Entity holder, float distance, int index) {
        int comparison = Float.compare(distance, distances[index]);
        if (comparison != 0) return comparison < 0;
        // Stable ties keep shadow slots from churning with concurrent-index iteration order:
        // blocks by position, then held lights by identity.
        BlockPos other = positions[index];
        if (p == null || other == null) {
            if (p != null) return true;
            if (other != null) return false;
            return System.identityHashCode(holder) < System.identityHashCode(holders[index]);
        }
        if (p.x() != other.x()) return p.x() < other.x();
        if (p.y() != other.y()) return p.y() < other.y();
        return p.z() < other.z();
    }

    int size() { return size; }
    /** The placed source at {@code index}, or null when it is a held light ({@link #holder}). */
    BlockPos get(int index) { return positions[index]; }
    /** The entity carrying the light at {@code index}, or null for a placed source. */
    Entity holder(int index) { return holders[index]; }
}
