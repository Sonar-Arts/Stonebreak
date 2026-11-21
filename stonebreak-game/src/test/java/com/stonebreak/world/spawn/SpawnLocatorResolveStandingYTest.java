package com.stonebreak.world.spawn;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SpawnLocator#resolveStandingY} reads the real blocks of a column (issue #250): the topmost solid
 * ground with two free cells above, scanning down from a start height. Over a detached cube world (#447;
 * the old version generated Java chunks - generation is native now and covered by the stream integration
 * test and the native ctest).
 */
class SpawnLocatorResolveStandingYTest {

    private final World world = World.createDetached(new WorldConfiguration(), 1L);

    @AfterEach
    void close() {
        world.cleanup();
    }

    @Test
    void standsOnTheTopmostSolidWithHeadroom() {
        for (int y = 40; y <= 60; y++) world.setBlockAt(3, y, 3, BlockType.STONE);
        world.setBlockAt(3, 64, 3, BlockType.STONE); // a one-block ledge with one free cell under it
        assertEquals(65, SpawnLocator.resolveStandingY(world, 3, 3, 100));
        world.setBlockAt(3, 64, 3, BlockType.AIR);
        assertEquals(61, SpawnLocator.resolveStandingY(world, 3, 3, 100));
    }

    @Test
    void waterIsNotGround() {
        for (int y = 40; y <= 50; y++) world.setBlockAt(5, y, 5, BlockType.STONE);
        world.setBlockAt(5, 51, 5, BlockType.WATER);
        // Water is not ground; the cell above the stone holds water, so nothing stands there either.
        assertEquals(SpawnLocator.NO_STAND, SpawnLocator.resolveStandingY(world, 5, 5, 100));
    }

    @Test
    void standsBelowYZeroToo() {
        // The world reaches MIN_Y: -1 is a real height, not a "nothing found" answer.
        world.setBlockAt(7, -2, 7, BlockType.STONE);
        assertEquals(-1, SpawnLocator.resolveStandingY(world, 7, 7, 10));
    }

    @Test
    void emptyColumnHasNoStandingY() {
        assertEquals(SpawnLocator.NO_STAND, SpawnLocator.resolveStandingY(world, 9, 9, WorldConfiguration.MAX_Y - 1));
    }
}
