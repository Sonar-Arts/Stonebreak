package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.physics.PhysicsWorld;
import com.stonebreak.world.physics.WorldPhysics;

import java.util.HashSet;
import java.util.Set;

/**
 * Endless flat ground with optional walls, as a physics world filled the way {@link WorldPhysics}
 * fills one, for exercising the pathfinding service without a real world. The block-to-cell
 * translation itself is pinned by {@code NavCellsTest}; what the service tests need is a world that
 * is instant, deterministic, and can be made to misbehave on demand.
 */
final class FlatWorldCells {

    static final int FULL = 1;

    private final int groundTopY;
    private final Set<Long> walls = new HashSet<>();
    private boolean throwOnRead;

    FlatWorldCells(int groundTopY) {
        this.groundTopY = groundTopY;
    }

    /** Blocks the column at (x, z) with a two-cell wall standing on the ground. */
    FlatWorldCells wall(int x, int z) {
        walls.add(key(x, groundTopY + 1, z));
        walls.add(key(x, groundTopY + 2, z));
        return this;
    }

    /** Makes every subsequent read blow up, standing in for a world torn down mid-search. */
    FlatWorldCells failOnRead() {
        this.throwOnRead = true;
        return this;
    }

    /** A physics world over these cells, with a full block as shape {@link #FULL}. */
    PhysicsWorld physics() {
        PhysicsWorld physics = PhysicsWorld.create(this::fill, 4, 0, 0);
        physics.defineShape(FULL, new float[] {0, 0, 0, 1, 1, 1}, WorldPhysics.SOLID | WorldPhysics.PICK, 0);
        return physics;
    }

    private boolean fill(long x0, long y0, long z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        if (throwOnRead) {
            throw new IllegalStateException("world unavailable (expected by the test)");
        }
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int wx = (int) (x0 + x), wy = (int) (y0 + y), wz = (int) (z0 + z);
                    if ((wy >= 0 && wy <= groundTopY) || walls.contains(key(wx, wy, wz))) {
                        shapes[(y * sz + z) * sx + x] = FULL;
                    }
                }
            }
        }
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }
}
