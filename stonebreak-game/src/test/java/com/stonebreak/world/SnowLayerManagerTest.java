package com.stonebreak.world;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.voxel.VoxelLayers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snow layer counts on the SNOW layer of the native world (#447). The rules worth pinning: untracked snow
 * reads as one layer, stacking caps at eight, the mutation listener fires only on real changes (it drives
 * replication), hydration ({@code putRaw}) is silent, negative coordinates keep their own cells, and a type
 * change clears the count.
 */
class SnowLayerManagerTest {

    private final World world = World.createDetached(new WorldConfiguration(), 1L);
    private final SnowLayerManager snow = world.getSnowLayerManager();

    @AfterEach
    void close() {
        world.cleanup();
    }

    private void snowAt(long x, long y, long z) {
        world.setBlockAt(x, y, z, BlockType.SNOW);
    }

    @Test
    void untrackedSnowReadsAsOneLayer() {
        snowAt(5, 70, 5);
        assertEquals(1, snow.getSnowLayers(5, 70, 5));
        assertEquals(0.125f, snow.getSnowHeight(5, 70, 5), 1e-6f);
    }

    @Test
    void layersStoreAndConvertToHeight() {
        snowAt(5, 70, 5);
        snow.setSnowLayers(5, 70, 5, 6);
        assertEquals(6, snow.getSnowLayers(5, 70, 5));
        assertEquals(0.75f, world.getSnowHeight(5, 70, 5), 1e-6f);
        assertEquals(6, world.voxels().getLayer(VoxelLayers.SNOW, 5, 70, 5));
    }

    @Test
    void negativeCoordinatesKeepTheirOwnEntries() {
        snowAt(-17, 70, -33);
        snowAt(17, 70, 33);
        snow.setSnowLayers(-17, 70, -33, 4);
        assertEquals(4, snow.getSnowLayers(-17, 70, -33));
        assertEquals(1, snow.getSnowLayers(17, 70, 33));
    }

    @Test
    void stackingStopsAtEightLayers() {
        snowAt(2, 70, 2);
        for (int i = 0; i < 7; i++) {
            assertTrue(snow.addSnowLayer(2, 70, 2), "layer " + (i + 2) + " should stack");
        }
        assertFalse(snow.addSnowLayer(2, 70, 2));
        assertEquals(8, snow.getSnowLayers(2, 70, 2));
        assertThrows(IllegalArgumentException.class, () -> snow.setSnowLayers(2, 70, 2, 9));
    }

    @Test
    void listenerFiresOnRealChangesOnly() {
        snowAt(1, 70, 1);
        List<Integer> fired = new ArrayList<>();
        snow.setMutationListener((x, y, z, layers) -> fired.add(layers));
        snow.setSnowLayers(1, 70, 1, 3);
        snow.setSnowLayers(1, 70, 1, 3);
        snow.putRaw(1, 70, 1, 5);
        snow.removeSnowLayers(1, 70, 1);
        snow.removeSnowLayers(1, 70, 1);
        assertEquals(List.of(3, 0), fired);
    }

    @Test
    void replacingSnowClearsTheCount() {
        snowAt(3, 70, 3);
        snow.setSnowLayers(3, 70, 3, 7);
        world.setBlockAt(3, 70, 3, BlockType.STONE);
        assertEquals(0, world.voxels().getLayer(VoxelLayers.SNOW, 3, 70, 3));
        snowAt(3, 70, 3);
        assertEquals(1, snow.getSnowLayers(3, 70, 3));
    }

    @Test
    void outOfBoundsIsIgnored() {
        snow.setSnowLayers(0, WorldConfiguration.MAX_Y + 5, 0, 3);
        assertEquals(1, snow.getSnowLayers(0, WorldConfiguration.MAX_Y + 5, 0));
    }
}
