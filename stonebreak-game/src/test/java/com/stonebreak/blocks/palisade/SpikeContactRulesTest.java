package com.stonebreak.blocks.palisade;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A spiked wall pricks only what walks into its points — never from behind, nor alongside. */
class SpikeContactRulesTest {

    /** One spiked wall at the origin, its points leaning east (+X). */
    private static World wallLeaningEast() {
        World world = mock(World.class);
        when(world.getBlockAt(anyLong(), anyLong(), anyLong())).thenReturn(BlockType.AIR);
        when(world.getBlockAt(0, 0, 0)).thenReturn(BlockType.PALISADE);
        when(world.getBlockStateAt(0, 0, 0)).thenReturn("palisade:facing=EAST");
        return world;
    }

    /** A player-sized body standing with its west side at {@code minX}, centred on z = {@code z}. */
    private static float[] bodyAt(World world, float minX, float z) {
        return SpikeContactRules.pointsTouching(world, minX, 0.0f, z - 0.3f, minX + 0.6f, 1.8f, z + 0.3f);
    }

    @Test
    void walkingIntoThePointsPricksAndThrowsBackTheWayTheyLean() {
        float[] lean = bodyAt(wallLeaningEast(), 1.0f, 0.5f); // flush against the east face
        assertNotNull(lean);
        assertArrayEquals(new float[] {1, 0}, lean);
    }

    @Test
    void comingUpBehindTheWallDoesNothing() {
        assertNull(bodyAt(wallLeaningEast(), -0.6f, 0.5f), "flush against the log side");
    }

    @Test
    void walkingAlongsideTheWallDoesNothing() {
        World world = wallLeaningEast();
        assertNull(SpikeContactRules.pointsTouching(world, 0.2f, 0.0f, 1.0f, 0.8f, 1.8f, 1.6f), "beside it, to the south");
    }

    @Test
    void standingWellClearDoesNothing() {
        assertNull(bodyAt(wallLeaningEast(), 2.0f, 0.5f));
    }

    @Test
    void everyFacingPointsItsOwnWay() {
        assertArrayEquals(new float[] {0, 1}, SpikeContactRules.direction(com.stonebreak.blocks.stairs.StairState.Facing.SOUTH));
        assertArrayEquals(new float[] {0, -1}, SpikeContactRules.direction(com.stonebreak.blocks.stairs.StairState.Facing.NORTH));
        assertArrayEquals(new float[] {-1, 0}, SpikeContactRules.direction(com.stonebreak.blocks.stairs.StairState.Facing.WEST));
    }
}
