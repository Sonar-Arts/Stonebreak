package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.voxel.NavCell;
import com.openmason.engine.wayfind.voxel.NavVolume;

import java.util.HashSet;
import java.util.Set;

/**
 * Endless flat ground with optional walls, for exercising the pathfinding service without a real
 * world. {@link WorldNavVolume}'s own translation of blocks is covered by the engine's domain
 * tests; what the service tests need is a volume that is instant, deterministic, and can be made
 * to misbehave on demand.
 */
final class FlatNavVolume implements NavVolume {

    private final int groundTopY;
    private final Set<Long> walls = new HashSet<>();
    private boolean throwOnRead;

    FlatNavVolume(int groundTopY) {
        this.groundTopY = groundTopY;
    }

    /** Blocks the column at (x, z) with a two-cell wall standing on the ground. */
    FlatNavVolume wall(int x, int z) {
        walls.add(key(x, groundTopY + 1, z));
        walls.add(key(x, groundTopY + 2, z));
        return this;
    }

    /** Makes every subsequent read blow up, standing in for a world torn down mid-search. */
    FlatNavVolume failOnRead() {
        this.throwOnRead = true;
        return this;
    }

    @Override
    public int flags(int x, int y, int z) {
        if (throwOnRead) {
            throw new IllegalStateException("volume unavailable");
        }
        if (y < 0) {
            return NavCell.UNKNOWN;
        }
        if (y <= groundTopY || walls.contains(key(x, y, z))) {
            return NavCell.SOLID;
        }
        return NavCell.OPEN;
    }

    @Override
    public float topSurface(int x, int y, int z) {
        return 1.0f;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }
}
