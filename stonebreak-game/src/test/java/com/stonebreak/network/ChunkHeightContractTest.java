package com.stonebreak.network;

import com.openmason.engine.voxel.net.VoxelChunkCodec;
import com.openmason.engine.voxel.VoxelWorldLimits;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine's chunk wire codec once carried its own copy of the column height because the
 * engine could not see the game's constants. When the two drifted (world height changed, codec
 * left at the old value) every chunk send threw on the server thread and nothing replicated --
 * with every other test green, since each side was self-consistent. Since #385 the codec takes
 * the world's {@code VoxelWorldConfig}; this pins the game's config to the layout the codec uses
 * and to the engine's limits.
 */
class ChunkHeightContractTest {

    @Test
    void wireCodecSpansExactlyTheWorldColumn() {
        VoxelChunkCodec.Layout layout = VoxelChunkCodec.Layout.of(WorldConfiguration.voxelConfig());
        assertEquals(WorldConfiguration.WORLD_HEIGHT, layout.worldHeight());
        assertEquals(WorldConfiguration.CHUNK_SIZE, layout.chunkSize());
        assertEquals(WorldConfiguration.WORLD_HEIGHT / VoxelChunkCodec.SECTION_H, layout.sections());
    }

    @Test
    void theGamesDimensionsAreWithinTheEnginesLimits() {
        assertEquals(java.util.List.of(), VoxelWorldLimits.problems(WorldConfiguration.voxelConfig()));
    }
}
