package com.stonebreak.world.weather;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where precipitation lands: the column TOP of a cube world (3B-E), over a detached world. */
class WeatherSurfaceTest {

    private final World world = World.createDetached(new WorldConfiguration(), 3L);

    @AfterEach
    void close() {
        world.cleanup();
    }

    @Test
    void rainStopsAtTheWaterSurfaceNotTheLakeBed() {
        world.setBlockAt(2, 60, 2, BlockType.STONE);
        world.setBlockAt(2, 61, 2, BlockType.WATER);
        world.setBlockAt(2, 62, 2, BlockType.WATER);
        assertEquals(63, WeatherSurface.groundY(world, 2, 2));
        assertFalse(WeatherSurface.isSnow(world, 2, 2));
    }

    @Test
    void snowFallsOnIceAndOnSnowLayers() {
        world.setBlockAt(4, 60, 4, BlockType.STONE);
        world.setBlockAt(4, 61, 4, BlockType.ICE); // ice passes sky light: the old surface rule read the bed
        assertEquals(62, WeatherSurface.groundY(world, 4, 4));
        assertTrue(WeatherSurface.isSnow(world, 4, 4));

        world.setBlockAt(6, 60, 6, BlockType.GRASS);
        world.setBlockAt(6, 61, 6, BlockType.SNOW);
        assertTrue(WeatherSurface.isSnow(world, 6, 6));
    }

    @Test
    void groundBelowYZeroAndFarOutIsAnswered() {
        long far = 1L << 33;
        world.setBlockAt(far, -50, far, BlockType.STONE);
        assertEquals(-49, WeatherSurface.groundY(world, far, far));
    }

    @Test
    void anEmptyColumnFallsToTheWorldFloor() {
        assertEquals(WorldConfiguration.MIN_Y, WeatherSurface.groundY(world, 900, 900));
    }

    @Test
    void aColumnWhoseTopIsNotLoadedUsesTheTrackersEstimate() {
        // A cave deep under LOD-only ground: the surface cube is not loaded, but the column is not open sky.
        World cave = org.mockito.Mockito.mock(World.class);
        org.mockito.Mockito.when(cave.topY(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(140L);
        org.mockito.Mockito.when(cave.isKnown(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        assertEquals(WeatherSurface.UNKNOWN, WeatherSurface.groundY(cave, 5, 5), "not known...");
        assertEquals(140, WeatherSurface.estimatedGroundY(cave, 5, 5), "...but estimated");
        assertEquals(100.0, WeatherSurface.coverAbove(cave, 5, 5, 40.0), 1e-9);
        assertEquals(0.0, WeatherSurface.coverAbove(cave, 5, 5, 150.0), 1e-9, "above the estimate: open");
    }

    @Test
    void aKnownColumnIsNotEstimated() {
        world.setBlockAt(8, 60, 8, BlockType.STONE);
        assertEquals(61, WeatherSurface.estimatedGroundY(world, 8, 8));
        assertEquals(11.0, WeatherSurface.coverAbove(world, 8, 8, 50.0), 1e-9);
    }

    @Test
    void aClosedWorldHasNoEstimate() {
        World closed = World.createDetached(new WorldConfiguration(), 4L);
        closed.cleanup();
        assertEquals(WeatherSurface.UNKNOWN, WeatherSurface.estimatedGroundY(closed, 2, 2));
        assertEquals(0.0, WeatherSurface.coverAbove(closed, 2, 2, 10.0));
    }

    /** Stone over x, z in [x0, x0 + 20] at height y, leaving the listed columns open. */
    private void slab(long x0, long z0, long y, long... openXZ) {
        for (long x = x0; x <= x0 + 20; x++) {
            for (long z = z0; z <= z0 + 20; z++) {
                boolean open = false;
                for (int i = 0; i < openXZ.length; i += 2) open |= openXZ[i] == x && openXZ[i + 1] == z;
                if (!open) world.setBlockAt(x, y, z, BlockType.STONE);
            }
        }
    }

    @Test
    void aCaveUnderASkylightIsStillUnderGround() {
        // A cave 40 blocks under a rock ceiling, with a one-column shaft open to the sky right over the camera.
        slab(100, 100, 100, 110, 110);
        assertEquals(0.0, WeatherSurface.coverAbove(world, 110, 110, 60.0), 1e-9, "the shaft itself is open");
        assertEquals(41.0, WeatherSurface.coverAround(world, 110.5, 60.0, 110.5), 1e-9, "the cave around it is not");
    }

    @Test
    void aSingleOverhangDoesNotBuryTheOpen() {
        world.setBlockAt(300, 70, 300, BlockType.STONE); // one block (a branch, a ledge) over the camera only
        assertEquals(11.0, WeatherSurface.coverAbove(world, 300, 300, 60.0), 1e-9);
        assertEquals(0.0, WeatherSurface.coverAround(world, 300.5, 60.0, 300.5), 1e-9);
    }

    @Test
    void underAWideRoofTheCoverIsTheRoof() {
        slab(500, 500, 64);
        assertEquals(5.0, WeatherSurface.coverAround(world, 510.5, 60.0, 510.5), 1e-9);
        assertEquals(0.0, WeatherSurface.coverAround(world, 510.5, 70.0, 510.5), 1e-9, "on top of it: open");
    }
}
