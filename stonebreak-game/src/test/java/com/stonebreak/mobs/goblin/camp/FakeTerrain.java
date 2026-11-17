package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;

import java.util.HashMap;
import java.util.Map;

/**
 * A little hand-built world for the camp rules: flat ground of stone with a top at
 * {@link #GROUND_TOP}, air above, and whatever the test digs or builds in between.
 */
final class FakeTerrain implements CampTerrain {

    static final int GROUND_TOP = 40;
    static final int SIZE = 80; // loaded from -SIZE to SIZE in x and z

    private final Map<Long, BlockType> edits = new HashMap<>();

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 40) | ((long) (y + 1024) << 20) | (z + 1024);
    }

    void set(int x, int y, int z, BlockType b) {
        edits.put(key(x, y, z), b);
    }

    /** Hollows out a box of air (inclusive bounds). */
    void carve(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    set(x, y, z, BlockType.AIR);
                }
            }
        }
    }

    @Override
    public BlockType block(int x, int y, int z) {
        if (Math.abs(x) > SIZE || Math.abs(z) > SIZE) {
            return null;
        }
        BlockType edited = edits.get(key(x, y, z));
        if (edited != null) {
            return edited;
        }
        if (y <= 0) {
            return BlockType.BEDROCK;
        }
        return y <= GROUND_TOP ? BlockType.STONE : BlockType.AIR;
    }

    @Override
    public int columnTop(int x, int z) {
        if (Math.abs(x) > SIZE || Math.abs(z) > SIZE) {
            return -1;
        }
        for (int y = 120; y >= 0; y--) {
            BlockType b = block(x, y, z);
            if (b != null && b.isSolid()) {
                return y;
            }
        }
        return -1;
    }

    // ---- walking: the engine's navigation over a physics mirror of this terrain ----------------

    private com.openmason.engine.physics.PhysicsWorld physics;
    private com.stonebreak.mobs.entities.ai.nav.PathfindingService service;

    /** A goblin's route profile, exactly as a real goblin's (StubMob of type GOBLIN has its body). */
    static com.openmason.engine.nav.GroundProfile goblin() {
        return com.stonebreak.mobs.entities.ai.nav.NavProfiles.forEntity(
            new com.stonebreak.mobs.entities.StubMob(com.stonebreak.mobs.entities.EntityType.GOBLIN));
    }

    /**
     * Where a goblin can walk here: the engine's reach over a physics world filled from these blocks
     * as {@code WorldPhysics} would fill it (solid blocks a full shape, water a fluid, unloaded beyond
     * {@link #SIZE}). Reflects every edit made before each call.
     */
    CampWalker walker() {
        if (physics == null) {
            physics = com.openmason.engine.physics.PhysicsWorld.create(this::fill, 4, 0, 0);
            physics.defineShape(1, new float[] {0, 0, 0, 1, 1, 1},
                com.stonebreak.world.physics.WorldPhysics.SOLID | com.stonebreak.world.physics.WorldPhysics.PICK, 0);
            physics.defineFluid(1, 0.875f, 0, 0, 0, com.stonebreak.world.physics.WorldPhysics.WATER);
            service = com.stonebreak.mobs.entities.ai.nav.PathfindingService.over(physics, 256);
        }
        com.openmason.engine.nav.GroundProfile profile = goblin();
        return (feet, radius, maxCells) -> {
            physics.invalidateAll(); // the test may have dug since
            return service.nav().reachGround(feet.x + 0.5f, feet.y, feet.z + 0.5f, CampWalker.FOOTING_DROP, 0, radius,
                maxCells, Float.MAX_VALUE, profile);
        };
    }

    /** The navigation {@link #walker} asks (after a {@link #walker} call has built it). */
    com.openmason.engine.nav.NavWorld nav() {
        walker();
        physics.invalidateAll();
        return service.nav();
    }

    private boolean fill(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockType b = block(x0 + x, y0 + y, z0 + z);
                    int i = (y * sz + z) * sx + x;
                    if (b == BlockType.WATER) {
                        fluids[i] = 1;
                    } else if (b != null && b.isSolid()) {
                        shapes[i] = 1;
                    }
                }
            }
        }
        return true;
    }
}
