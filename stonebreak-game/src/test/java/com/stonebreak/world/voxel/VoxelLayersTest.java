package com.stonebreak.world.voxel;

import com.stonebreak.world.chunk.ChunkWaterLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The WATER layer's raw encoding is the engine's cv_fluid encoding (level | falling << 3 | source << 4 |
 * (octant + 1) << 5, 0 = source) so the native fluid mesher reads it; the game speaks ChunkWaterLayer values.
 */
class VoxelLayersTest {

    @Test
    void gameValuesRoundTrip() {
        for (int v = ChunkWaterLayer.SOURCE; v <= ChunkWaterLayer.MAX_VALUE; v++) {
            assertEquals(v, VoxelLayers.waterToGame(VoxelLayers.waterToRaw(v)), "value " + v);
        }
    }

    @Test
    void rawEncodingFollowsCvFluid() {
        assertEquals(0, VoxelLayers.waterToRaw(ChunkWaterLayer.SOURCE));
        assertEquals(3, VoxelLayers.waterToRaw(3));
        assertEquals(VoxelLayers.RAW_FALLING, VoxelLayers.waterToRaw(ChunkWaterLayer.FALLING));
        assertEquals((5 + 1) << 5, VoxelLayers.waterToRaw(ChunkWaterLayer.river(5)));
    }

    @Test
    void sourceBitAndZeroAreSources() {
        assertEquals(ChunkWaterLayer.SOURCE, VoxelLayers.waterToGame(0));
        assertEquals(ChunkWaterLayer.SOURCE, VoxelLayers.waterToGame(VoxelLayers.RAW_SOURCE));
        assertEquals(ChunkWaterLayer.river(2), VoxelLayers.waterToGame(VoxelLayers.RAW_SOURCE | (3 << 5)));
    }

    @Test
    void riverValuesForTheGenerator() {
        short[] river = VoxelLayers.riverRawValues();
        for (int o = 0; o < 8; o++) {
            assertEquals(ChunkWaterLayer.river(o), VoxelLayers.waterToGame(river[o]));
        }
    }

    @Test
    void surfaceTableMatchesTheGameRule() {
        float[] surface = VoxelLayers.waterSurfaceByLevel();
        assertEquals(8, surface.length);
        assertEquals(com.stonebreak.blocks.waterSystem.WaterHeightUtil.surfaceFraction(0), surface[0], 0f);
        assertEquals(com.stonebreak.blocks.waterSystem.WaterHeightUtil.surfaceFraction(7), surface[7], 0f);
    }

    @Test
    void badValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VoxelLayers.waterToRaw(ChunkWaterLayer.MAX_VALUE + 1));
    }
}
