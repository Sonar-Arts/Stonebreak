package com.stonebreak.rendering.lighting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Indirect bounce volumes over a detached cube world (native Cenda voxel world required). */
class TorchIndirectLightingTest {
    @Test void cachesStationaryLightButDetectsOpacityEdits() {
        World world = World.createDetached(new WorldConfiguration(), 1L);
        try {
            TorchIndirectLighting.Entry cache = new TorchIndirectLighting.Entry();
            Vector3f position = new Vector3f(-.2f, 64.6f, -16.2f);
            assertTrue(cache.update(world, position));
            assertFalse(cache.update(world, position), "nothing changed");
            for (int x = -3; x <= 3; x++) for (int z = -20; z <= -14; z++) world.setBlockAt(x, 62, z, BlockType.DIRT);
            assertTrue(cache.update(world, position));
            assertTrue(java.util.stream.IntStream.range(0, cache.volume.data().length)
                    .anyMatch(i -> cache.volume.data()[i] > 0), "new floor reflects light");
            // A flower on the floor: its cube exists and the cells above it stay known air (above the column
            // top), so nothing about occupancy or reflectance changes.
            world.setBlockAt(0, 63, -17, BlockType.ROSE);
            assertFalse(cache.update(world, position), "opacity-neutral edits do not rebuild radiance");
            for (int x = -3; x <= 3; x++) for (int z = -20; z <= -14; z++) world.setBlockAt(x, 62, z, BlockType.AIR);
            assertTrue(cache.update(world, position), "removing the reflector is visible");
            for (float value : cache.volume.data()) assertEquals(0, value, "removed reflector leaves no stale light");
            assertFalse(cache.update(world, new Vector3f(position).add(.01f, 0, 0)), "sub-probe motion reuses indirect fill");
            assertTrue(cache.update(world, new Vector3f(position).add(.3f, 0, 0)), "moving held light updates");
        } finally {
            world.cleanup();
        }
    }
}
