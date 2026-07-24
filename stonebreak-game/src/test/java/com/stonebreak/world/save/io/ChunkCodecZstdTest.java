package com.stonebreak.world.save.io;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.save.model.ChunkData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * zstd chunk payload round-trip: real generated terrain in, identical
 * blocks out, header bytes confirm the zstd path was actually taken.
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
        // Bytes 4..5 are the big-endian version (5 = paletted sections);
        // byte 24 is the compression flag — with kernels loaded it must be zstd (1).
        int version = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
        assertEquals(5, version, "writer emits the paletted section format");
        assertEquals(1, payload[24], "kernels present => zstd compression flag");

        ChunkData restored = ChunkCodec.decode(payload);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockType expected = (BlockType) chunk.getBlockStorageView().get(x, y, z);
                    BlockType actual = (BlockType) restored.getBlockStorage().get(x, y, z);
                    assertEquals(expected, actual, "block @" + x + "/" + y + "/" + z);
                }
            }
        }
    }
}
