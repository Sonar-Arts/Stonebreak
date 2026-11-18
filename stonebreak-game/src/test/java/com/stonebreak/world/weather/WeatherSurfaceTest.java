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
}
