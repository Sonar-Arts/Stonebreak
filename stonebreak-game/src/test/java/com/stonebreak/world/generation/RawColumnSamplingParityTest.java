package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FastLOD's uncarved coarse levels read {@link TerrainGenerationSystem#sampleRawColumns}:
 * batched channel fills whose heights must be the raw terrain height chunk generation uses
 * ({@link TerrainGenerationSystem#getFinalTerrainHeightAt}) and whose surfaces must be the
 * biome surface block ({@link TerrainGenerationSystem#getSurfaceBlockAt}), point for point.
 * A drift here shows as LOD terrain that does not meet the real chunks it hands over to.
 */
class RawColumnSamplingParityTest {

    @Test
    void rawGridMatchesThePerPointApi() {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(12345L);
        int count = 6;
        for (int[] origin : new int[][]{{0, 0}, {-4096, 1536}, {20000, -7777}}) {
            for (int stride : new int[]{8, 16}) {
                int[] heights = new int[count * count];
                BlockType[] surface = new BlockType[count * count];
                terrain.sampleRawColumns(origin[0], origin[1], count, stride, heights, surface);
                for (int ix = 0; ix < count; ix++) {
                    for (int iz = 0; iz < count; iz++) {
                        int wx = origin[0] + ix * stride, wz = origin[1] + iz * stride;
                        String at = "(" + wx + "," + wz + ")";
                        assertEquals(terrain.getFinalTerrainHeightAt(wx, wz), heights[ix * count + iz], "height " + at);
                        int h = heights[ix * count + iz];
                        if (h > 1) {
                            assertEquals(terrain.getSurfaceBlockAt(wx, wz), surface[ix * count + iz], "surface " + at);
                        }
                    }
                }
            }
        }
    }
}
