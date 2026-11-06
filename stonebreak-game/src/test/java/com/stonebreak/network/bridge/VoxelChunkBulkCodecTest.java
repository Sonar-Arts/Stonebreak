package com.stonebreak.network.bridge;

import com.openmason.engine.voxel.net.VoxelChunkCodec;
import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.voxel.ChunkDataAdapter;
import com.stonebreak.world.generation.diffusion.DiffusionTerrainGenerator;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The bulk section fast paths added to the network chunk codec must be
 * byte-identical on encode and cell-identical on decode versus the legacy
 * per-cell paths — old and new builds must interoperate on the wire.
 *
 * <p>The test chunk comes from {@value TGMPipe#MODEL_NAME} (through TGMPipe). Without it — no venv,
 * the service won't start, or it can't produce the chunk in {@link #GENERATE_TIMEOUT_SECONDS}
 * (a GPU too small for it falls back to the CPU and stalls) — the test is skipped, not failed.
 */
@Tag("regression")
class VoxelChunkBulkCodecTest {

    private static final long GENERATE_TIMEOUT_SECONDS = 120;

    private static Chunk chunk;

    /** The world dimensions the game installs at startup (GameBootstrap): the chunk codec reads them (#385). */
    @org.junit.jupiter.api.BeforeAll
    static void installWorldDimensions() {
        com.openmason.engine.voxel.cco.coordinates.CcoBounds.configure(
            com.stonebreak.world.operations.WorldConfiguration.voxelConfig());
    }

    @org.junit.jupiter.api.BeforeAll
    static void generateChunkWithDaedalus() throws InterruptedException {
        // Off the test thread: a stalled service blocks tile requests with no timeout of its own.
        CompletableFuture<Chunk> generation = CompletableFuture.supplyAsync(
            () -> new DiffusionTerrainGenerator(555L).generateTerrainOnly(4, -9).chunk());
        try {
            chunk = generation.get(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            TGMPipe.getInstance().shutdown(); // fails the stuck request and stops the service
            abort(TGMPipe.MODEL_NAME + " did not generate a chunk within " + GENERATE_TIMEOUT_SECONDS + " s");
        } catch (ExecutionException e) {
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof TGMPipeException) {
                    abort(TGMPipe.MODEL_NAME + " is not available: " + t.getMessage());
                }
            }
            throw new AssertionError("chunk generation failed", e.getCause());
        }
    }


    @Test
    void bulkEncodeIsByteIdenticalAndBulkDecodeCellIdentical() {
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
            CcoPalettedChunkStorage.createEmpty(16, WorldConfiguration.WORLD_HEIGHT, 16, BlockType.AIR);
        VoxelChunkCodec.decodeInto(bulkPayload, new StorageBlockSetter(bulkStorage), resolver);

        CcoPalettedChunkStorage perCellStorage =
            CcoPalettedChunkStorage.createEmpty(16, WorldConfiguration.WORLD_HEIGHT, 16, BlockType.AIR);
        StorageBlockSetter inner = new StorageBlockSetter(perCellStorage);
        BlockSetter perCellOnly = inner::setBlock; // lambda: bulk defaults return false
        VoxelChunkCodec.decodeInto(bulkPayload, perCellOnly, resolver);

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockType original = chunk.getBlock(x, y, z);
                    assertEquals(original, bulkStorage.get(x, y, z), "bulk decode @" + x + "/" + y + "/" + z);
                    assertEquals(original, perCellStorage.get(x, y, z), "per-cell decode @" + x + "/" + y + "/" + z);
                }
            }
        }
    }
}
