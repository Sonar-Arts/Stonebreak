package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import java.util.Arrays;

/** Bounded, reusable nearest-source selection; no full-world sorting or candidate allocations. */
final class NearestLightSources {
    private final BlockPos[] positions;
    private final float[] distances;
    private int size;
    private int limit;

    NearestLightSources(int capacity) {
        positions = new BlockPos[capacity];
        distances = new float[capacity];
    }

    void clear(int limit) {
        if (limit < 0 || limit > positions.length) throw new IllegalArgumentException("Invalid limit");
        Arrays.fill(positions, null);
        size = 0;
        this.limit = limit;
    }

    void offer(BlockPos position, float distanceSquared) {
        int at = size;
        while (at > 0 && comesBefore(position, distanceSquared, at - 1)) at--;
        if (at >= limit) return;
        int moved = Math.min(size, limit - 1) - at;
        System.arraycopy(positions, at, positions, at + 1, moved);
        System.arraycopy(distances, at, distances, at + 1, moved);
        positions[at] = position;
        distances[at] = distanceSquared;
        size = Math.min(size + 1, limit);
    }

    private boolean comesBefore(BlockPos p, float distance, int index) {
        int comparison = Float.compare(distance, distances[index]);
        if (comparison != 0) return comparison < 0;
        // Stable ties keep shadow slots from churning with concurrent-index iteration order.
        BlockPos other = positions[index];
        if (p.x() != other.x()) return p.x() < other.x();
        if (p.y() != other.y()) return p.y() < other.y();
        return p.z() < other.z();
    }

    int size() { return size; }
    BlockPos get(int index) { return positions[index]; }
}
