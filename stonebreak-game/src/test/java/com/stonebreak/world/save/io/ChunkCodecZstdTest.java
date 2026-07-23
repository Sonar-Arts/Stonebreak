package com.stonebreak.world.save.io;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.ChunkData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v5 (zstd) chunk payload round-trip: real generated terrain in, identical
 * blocks out, version byte confirms the zstd path was actually taken.
 */
class ChunkCodecZstdTest {

    @Test
    void zstdRoundTripPreservesEveryBlock() throws Exception {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");

        var chunk = new TerrainGenerationSystem(1337L).generateTerrainOnly(2, -3).chunk();
        ChunkData data = ChunkData.builder()
            .chunkX(2)
            .chunkZ(-3)
            .blocks(chunk.getBlockStorageView())
            .lastModified(LocalDateTime.now())
            .featuresPopulated(false)
            .hasEntitiesGenerated(false)
            .waterMetadata(new HashMap<>())
            .entities(List.of())
            .blockStates(new HashMap<>())
            .snowLayers(new HashMap<>())
            .build();

        byte[] payload = ChunkCodec.encode(data);
        // Bytes 4..5 are the big-endian version; with kernels loaded it must be 5 (zstd).
        int version = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
        assertEquals(5, version, "kernels present => zstd payload version");

        ChunkData restored = ChunkCodec.decode(payload);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockType expected = (BlockType) chunk.getBlockStorageView().get(x, y, z);
                    BlockType actual = (BlockType) restored.getBlockStorage().get(x, y, z);
                    assertEquals(expected, actual, "block @" + x + "/" + y + "/" + z);
                }
            }
        }
    }
}
