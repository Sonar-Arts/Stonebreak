package com.stonebreak.world.leaves;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Leaf decay in a real world: the reachability flood runs over the world's physics cache (OpenMason
 * #116's {@code cn_flood}), where {@code WorldPhysics} gives leaves and logs their own categories, and
 * a chop reaches the cache through {@code World.setBlockAt}. The rules themselves are pinned over a
 * fake world in {@link LeafDecaySystemTest}; this pins the production wiring.
 */
class WorldLeafDecayTest {

    private TestWorld world;

    @BeforeEach
    void setUp() {
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        Chunk chunk = new Chunk(0, 0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlock(x, 10, z, BlockType.STONE);
            }
        }
        // Two trees: one to chop, one that must keep its leaves.
        for (int y = 11; y <= 14; y++) {
            chunk.setBlock(4, y, 4, BlockType.WOOD);
            chunk.setBlock(11, y, 11, BlockType.WOOD);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunk.setBlock(4 + dx, 15, 4 + dz, BlockType.LEAVES);
                chunk.setBlock(11 + dx, 15, 11 + dz, BlockType.LEAVES);
            }
        }
        world.setChunk(0, 0, chunk);
        // Decay never acts beside an unloaded chunk (a log across the seam would look absent).
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                if (cx != 0 || cz != 0) {
                    world.setChunk(cx, cz, new Chunk(cx, cz));
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        world.cleanup();
    }

    private int leavesAround(int cx, int cz) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (world.getBlockAt(cx + dx, 15, cz + dz) == BlockType.LEAVES) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void choppingATrunkDecaysItsCanopyAndOnlyIts() {
        LeafDecaySystem decay = world.getLeafDecay();
        decay.advanceTicks(5); // the physics cache now holds both trees
        assertEquals(9, leavesAround(4, 4));

        for (int y = 11; y <= 14; y++) {
            world.setBlockAt(4, y, 4, BlockType.AIR); // through the funnel: physics and the sim hear it
        }
        decay.advanceTicks(200);

        assertEquals(0, leavesAround(4, 4), "the chopped tree's canopy decays");
        assertEquals(9, leavesAround(11, 11), "the standing tree keeps its leaves");
    }

    @Test
    void aLogPlacedBackRescuesTheCanopy() {
        LeafDecaySystem decay = world.getLeafDecay();
        decay.advanceTicks(5);
        world.setBlockAt(4, 14, 4, BlockType.AIR);
        world.setBlockAt(4, 14, 4, BlockType.WOOD); // within the same tick: nothing may decay
        decay.advanceTicks(200);
        assertEquals(9, leavesAround(4, 4));
    }
}
