package com.stonebreak.network;

import com.openmason.engine.net.protocol.codec.VoxelChunkCodec;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine's chunk wire codec carries its own copy of the column height because the engine
 * cannot see the game's constants. When the two drifted (world height changed, codec left at
 * the old value) every chunk send threw on the server thread and nothing replicated -- with
 * every other test green, since each side was self-consistent. This pins them together.
 */
class ChunkHeightContractTest {

    @Test
    void wireCodecSpansExactlyTheWorldColumn() {
        assertEquals(WorldConfiguration.WORLD_HEIGHT, VoxelChunkCodec.CHUNK_H);
        assertEquals(WorldConfiguration.WORLD_HEIGHT / VoxelChunkCodec.SECTION_H, VoxelChunkCodec.SECTIONS_PER_CHUNK);
    }
}
