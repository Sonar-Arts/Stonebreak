package com.stonebreak.network.bridge;

import com.openmason.engine.net.protocol.codec.VoxelChunkCodec;
import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.voxel.ChunkDataAdapter;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bulk section fast paths added to the network chunk codec must be
 * byte-identical on encode and cell-identical on decode versus the legacy
 * per-cell paths — old and new builds must interoperate on the wire.
 */
class VoxelChunkBulkCodecTest {

    @Test
    void bulkEncodeIsByteIdenticalAndBulkDecodeCellIdentical() {
        var chunk = new TerrainGenerationSystem(555L).generateTerrainOnly(4, -9).chunk();
        ChunkDataAdapter bulkView = new ChunkDataAdapter(chunk);

        // Per-cell view: same data, but WITHOUT the sections interface, so the
        // codec takes its legacy per-cell path.
        IVoxelChunkData perCellView = new IVoxelChunkData() {
            @Override
            public IBlockType getBlock(int x, int y, int z) {
                return bulkView.getBlock(x, y, z);
            }

            @Override
            public int getChunkX() {
                return bulkView.getChunkX();
            }

            @Override
            public int getChunkZ() {
                return bulkView.getChunkZ();
            }
        };

        byte[] bulkPayload = VoxelChunkCodec.encode(bulkView);
        byte[] perCellPayload = VoxelChunkCodec.encode(perCellView);
        assertArrayEquals(perCellPayload, bulkPayload, "bulk encode must be wire-identical");

        // Decode the same payload through both sink paths.
        GameBlockTypeResolver resolver = GameBlockTypeResolver.INSTANCE;
        CcoPalettedChunkStorage bulkStorage =
            CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
        VoxelChunkCodec.decodeInto(bulkPayload, new StorageBlockSetter(bulkStorage), resolver);

        CcoPalettedChunkStorage perCellStorage =
            CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
        StorageBlockSetter inner = new StorageBlockSetter(perCellStorage);
        BlockSetter perCellOnly = inner::setBlock; // lambda: bulk defaults return false
        VoxelChunkCodec.decodeInto(bulkPayload, perCellOnly, resolver);

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockType original = chunk.getBlock(x, y, z);
                    assertEquals(original, bulkStorage.get(x, y, z), "bulk decode @" + x + "/" + y + "/" + z);
                    assertEquals(original, perCellStorage.get(x, y, z), "per-cell decode @" + x + "/" + y + "/" + z);
                }
            }
        }
    }
}
