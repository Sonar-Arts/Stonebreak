package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.NavWorld;
import com.stonebreak.blocks.BlockShape;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterHeightUtil;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.physics.WorldPhysics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What a block is to navigation, now that routes are planned over the physics cache (OpenMason #116)
 * instead of a second block reader ({@code WorldNavVolume}, deleted). Every block type is placed in a
 * real world and classified both ways: by the old rule (air open, water liquid at its surface, else
 * solid when {@code isSolid()} at {@link BlockShape#collisionHeight}) and by the service's cells.
 *
 * <p>They agree for every block except the two where the old rule disagreed with mob physics, which
 * are pinned to the physics answer instead:
 * <ul>
 *   <li><b>Snow layers</b> are not {@code isSolid()}, so the old rule read them as air: routes stood
 *       mobs on the block under the snow while their bodies stood on top of it, and planned straight
 *       through eight-layer drifts their bodies cannot climb. Now a layer is ground at its height.</li>
 *   <li><b>Stalagmites</b> were a full block to the old rule; physics collides with the tiers of the
 *       model. Now navigation sees the tiers too.</li>
 * </ul>
 */
class NavCellsTest {

    private TestWorld world;
    private Chunk chunk;

    @BeforeEach
    void setUp() {
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        chunk = new Chunk(0, 0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlock(x, 10, z, BlockType.STONE);
            }
        }
    }

    @AfterEach
    void tearDown() {
        world.cleanup();
    }

    // ---- the rule navigation used before #116 (WorldNavVolume + NavCellCache) --------------------

    private static int legacyFlags(World world, int x, int y, int z) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return NavWorld.CELL_UNKNOWN;
        }
        BlockType block = world.getBlockAt(x, y, z);
        if (block == null || block == BlockType.AIR) {
            return 0;
        }
        if (block == BlockType.WATER) {
            return NavWorld.CELL_LIQUID;
        }
        return block.isSolid() ? NavWorld.CELL_SOLID : 0;
    }

    private static float legacyTop(World world, int x, int y, int z) {
        float top;
        if (world.getBlockAt(x, y, z) == BlockType.WATER) {
            float height = WaterHeightUtil.resolveSurfaceHeightFraction(world, x, y, z);
            top = Float.isNaN(height) ? WaterHeightUtil.MAX_WATER_HEIGHT : height;
        } else {
            top = BlockShape.collisionHeight(world, x, y, z);
        }
        return (float) Math.round(Math.min(1.0f, Math.max(0.0f, top)) * 1024) / 1024;
    }

    @Test
    void everyBlockClassifiesAsBeforeExceptTheNamedFixes() {
        BlockType[] types = BlockType.values();
        int[][] at = new int[types.length][];
        for (int i = 0; i < types.length; i++) {
            int x = 1 + (i % 14);
            int z = 1 + (i / 14) * 2;
            at[i] = new int[] {x, 11, z};
            chunk.setBlock(x, 11, z, types[i]);
        }
        world.setChunk(0, 0, chunk);
        for (int i = 0; i < types.length; i++) {
            if (types[i] == BlockType.SNOW) {
                world.getSnowLayerManager().setSnowLayers(at[i][0], 11, at[i][2], 3);
            }
        }
        PathfindingService service = world.pathfinding();
        assertNotNull(service);
        NavWorld nav = service.nav();
        WorldPhysics physics = world.physics();

        for (int i = 0; i < types.length; i++) {
            BlockType type = types[i];
            int x = at[i][0], y = at[i][1], z = at[i][2];
            int flags = nav.cell(x, y, z);
            float top = nav.cellTop(x, y, z);
            if (type == BlockType.SNOW) {
                assertEquals(0, legacyFlags(world, x, y, z), "the old rule read snow as air");
                assertEquals(NavWorld.CELL_SOLID, flags, "snow is ground at its layer height now");
                assertEquals(world.getSnowHeight(x, y, z), top, 1e-3f);
            } else if (type == BlockType.LIMESTONE_STALAGMITE) {
                float physicsTop = physics.cellTop(x, y, z, WorldPhysics.MOB_MASK);
                assertEquals(physicsTop > 0 ? NavWorld.CELL_SOLID : 0, flags, "a stalagmite is what physics says");
                assertEquals(physicsTop, top, 1e-3f);
            } else {
                assertEquals(legacyFlags(world, x, y, z), flags, type + " flags");
                if (flags == NavWorld.CELL_SOLID || flags == NavWorld.CELL_LIQUID) {
                    assertEquals(legacyTop(world, x, y, z), top, 0.0f, type + " top");
                }
            }
        }
    }

    @Test
    void unloadedAndOutOfColumnCellsAreUnknown() {
        world.setChunk(0, 0, chunk);
        NavWorld nav = world.pathfinding().nav();
        assertEquals(NavWorld.CELL_SOLID, nav.cell(3, 10, 3));
        assertEquals(NavWorld.CELL_UNKNOWN, nav.cell(3, -1, 3));
        assertEquals(NavWorld.CELL_UNKNOWN, nav.cell(3, WorldConfiguration.WORLD_HEIGHT, 3));
        assertEquals(NavWorld.CELL_UNKNOWN, nav.cell(100, 10, 100), "a chunk that is not loaded");
    }

    @Test
    void editsReachNavigationThroughThePhysicsInvalidation() {
        world.setChunk(0, 0, chunk);
        NavWorld nav = world.pathfinding().nav();
        assertEquals(0, nav.cell(5, 11, 5));
        world.setBlockAt(5, 11, 5, BlockType.STONE);
        assertEquals(NavWorld.CELL_SOLID, nav.cell(5, 11, 5));
        world.setBlockAt(5, 11, 5, BlockType.AIR);
        assertEquals(0, nav.cell(5, 11, 5));
    }
}
