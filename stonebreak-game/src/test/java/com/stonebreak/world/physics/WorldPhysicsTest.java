package com.stonebreak.world.physics;

import com.openmason.engine.physics.FluidSample;
import com.openmason.engine.physics.RayHit;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockShape;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stonebreak's physics glue ({@link WorldPhysics}) over a real world: the shapes it hands the
 * engine agree with {@link BlockShape} — the one collision-height rule — for every block kind and
 * footprint; edits reach the cache through the world's funnels; water, rays and model keys work.
 */
class WorldPhysicsTest {

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
        world.setChunk(0, 0, chunk);
    }

    @AfterEach
    void tearDown() {
        world.cleanup();
    }

    @Test
    void cellHeightsMatchTheBlockShapeRuleForEveryKindAndFootprint() {
        // A row of every shape kind on the floor.
        chunk.setBlock(1, 11, 1, BlockType.SNOW);
        chunk.setBlock(2, 11, 1, BlockType.ROSE);
        chunk.setBlock(3, 11, 1, BlockType.TORCH_PLACED);
        chunk.setBlock(4, 11, 1, BlockType.WATER);
        chunk.setBlock(5, 11, 1, BlockType.STONE);
        StairState.Facing[] facings = StairState.Facing.values();
        for (int i = 0; i < facings.length; i++) {
            chunk.setBlock(6 + i, 11, 1, BlockType.OAK_STAIRS);
            chunk.setBlockState(6 + i, 11, 1, new StairState(facings[i]).toStateString());
        }
        world.setChunk(0, 0, chunk);
        world.getSnowLayerManager().setSnowLayers(1, 11, 1, 3);

        WorldPhysics physics = world.physics();
        Random random = new Random(117);
        for (int cellX = 0; cellX < 12; cellX++) {
            final int x = cellX;
            for (int i = 0; i < 40; i++) {
                float a = random.nextFloat(), b = random.nextFloat();
                float c = random.nextFloat(), d = random.nextFloat();
                float minX = x + Math.min(a, b), maxX = x + Math.max(a, b);
                float minZ = 1 + Math.min(c, d), maxZ = 1 + Math.max(c, d);
                float expected = BlockShape.collisionHeight(world, x, 11, 1, minX, minZ, maxX, maxZ);
                float actual = physics.cellTop(x, 11, 1, minX, minZ, maxX, maxZ, WorldPhysics.MOB_MASK);
                assertEquals(expected, actual, 1e-5f,
                    () -> world.getBlockAt(x, 11, 1) + " at x=" + x + " footprint [" + minX + "," + minZ + "]-["
                        + maxX + "," + maxZ + "]");
            }
        }
    }

    @Test
    void editsThroughTheWorldReachTheCache() {
        WorldPhysics physics = world.physics();
        assertFalse(physics.isOpen(3, 10, 3, WorldPhysics.SOLID));
        world.setBlockAt(3, 10, 3, BlockType.AIR);
        assertTrue(physics.isOpen(3, 10, 3, WorldPhysics.SOLID));

        world.setBlockAt(3, 11, 4, BlockType.SNOW);
        world.getSnowLayerManager().setSnowLayers(3, 11, 4, 2);
        float two = physics.cellTop(3, 11, 4, WorldPhysics.SOLID);
        world.getSnowLayerManager().setSnowLayers(3, 11, 4, 6);
        float six = physics.cellTop(3, 11, 4, WorldPhysics.SOLID);
        assertEquals(world.getSnowHeight(3, 11, 4), six, 1e-6f);
        assertTrue(six > two);

        world.setBlockAt(5, 11, 5, BlockType.OAK_STAIRS);
        world.setBlockStateAt(5, 11, 5, new StairState(StairState.Facing.NORTH).toStateString());
        float north = physics.cellTop(5, 11, 5, 5.1f, 5.1f, 5.2f, 5.2f, WorldPhysics.SOLID);
        world.setBlockStateAt(5, 11, 5, new StairState(StairState.Facing.SOUTH).toStateString());
        float south = physics.cellTop(5, 11, 5, 5.1f, 5.1f, 5.2f, 5.2f, WorldPhysics.SOLID);
        assertNotEquals(north, south, "turning the stair moves its high tread");
    }

    @Test
    void waterCarriesItsSurfaceAndFlowersArePickedButWalkedThrough() {
        for (int y = 11; y <= 12; y++) {
            world.setBlockAt(8, y, 8, BlockType.WATER);
        }
        world.setBlockAt(2, 11, 8, BlockType.ROSE);
        WorldPhysics physics = world.physics();
        FluidSample s = physics.measureFluid(8.5f, 11f, 8.5f, 1.8f, 0.3f, 11f, 1.8f);
        assertTrue(s.submersion > 0.9f, "a body standing on the floor of a 2-deep pool is mostly under");
        assertEquals(12.875f, s.surfaceY, 1e-4f); // source water: 7/8 of the top cell

        assertTrue(physics.isOpen(2, 11, 8, WorldPhysics.PLAYER_MASK));
        RayHit hit = new RayHit();
        assertTrue(physics.raycast(0.5f, 11.3f, 8.5f, 1, 0, 0, 10, WorldPhysics.PICK_MASK, 0, hit));
        assertEquals(2, hit.x);
    }

    @Test
    void blockPositionKeysRoundTripAndAreNeverZero() {
        int[][] cases = {{0, 0, 0}, {-1, -1, -1}, {123456, 255, -654321}, {-16_000_000, -2048, 16_000_000}};
        for (int[] c : cases) {
            BlockPos p = new BlockPos(c[0], c[1], c[2]);
            long key = WorldPhysics.BlockPosKey.pack(p);
            assertNotEquals(0L, key);
            assertEquals(p, WorldPhysics.BlockPosKey.unpack(key));
        }
    }

    @Test
    void physicsGoesAwayWithTheWorld() {
        WorldPhysics physics = world.physics();
        world.cleanup();
        assertTrue(physics.engine().isClosed());
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true); // for tearDown
    }
}
