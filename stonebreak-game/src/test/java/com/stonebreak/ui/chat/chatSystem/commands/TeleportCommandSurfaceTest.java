package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /tp on the cubic world: 64-bit columns, the surface tracker's top, MIN_Y..MAX_Y bounds. */
class TeleportCommandSurfaceTest {

    private final World world = World.createDetached(new WorldConfiguration(), 11L);

    @AfterEach
    void close() {
        world.cleanup();
    }

    @Test
    void landsOnTheHighestSolidBlockOfAFarColumn() {
        long far = 5_000_000_000L;
        world.setBlockAt(far, -250, -far, BlockType.STONE);
        world.setBlockAt(far, 300, -far, BlockType.STONE);
        world.setBlockAt(far, 301, -far, BlockType.WATER); // water is not ground
        assertEquals(301L, TeleportCommand.surfaceY(world, far + 0.5, -far + 0.5));
    }

    @Test
    void deepGroundUnderTheSea() {
        world.setBlockAt(-7, -200, 9, BlockType.STONE);
        world.setBlockAt(-7, -199, 9, BlockType.WATER);
        assertEquals(-199L, TeleportCommand.surfaceY(world, -6.5, 9.5));
    }

    @Test
    void verticalBoundsAreTheWorlds() {
        assertTrue(TeleportCommand.withinVerticalBounds(WorldConfiguration.MIN_Y));
        assertTrue(TeleportCommand.withinVerticalBounds(WorldConfiguration.MAX_Y));
        assertTrue(TeleportCommand.withinVerticalBounds(-300.5));
        assertFalse(TeleportCommand.withinVerticalBounds(WorldConfiguration.MIN_Y - 0.5));
        assertFalse(TeleportCommand.withinVerticalBounds(WorldConfiguration.MAX_Y + 1));
    }

    @Test
    void horizontalBoundReachesFarBeyondInts() {
        assertTrue(TeleportCommand.MAX_HORIZONTAL > Integer.MAX_VALUE * 100.0);
    }
}
