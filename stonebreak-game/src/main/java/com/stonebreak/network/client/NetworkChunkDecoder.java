package com.stonebreak.network.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openmason.engine.net.protocol.codec.VoxelChunkCodec;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.bridge.GameBlockTypeResolver;
import com.stonebreak.network.bridge.StorageBlockSetter;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The thread-safe half of installing a streamed chunk: wire payload to detached storage, plus the
 * sky heightmap derived from it.
 *
 * <p>Nothing here touches world state, so the client chunk handler runs it on a decode worker and
 * the render thread pays only for the install and swap. Without this split, the per-chunk column
 * rescan (~1 ms) ran on the main thread and serialized install throughput.</p>
 */
public final class NetworkChunkDecoder {

    private static final Logger logger = LoggerFactory.getLogger(NetworkChunkDecoder.class);

    private NetworkChunkDecoder() {
    }

    /**
     * Decodes a chunk payload into detached paletted storage.
     *
     * @return the decoded storage, or null on decode failure — the caller should request a resync
     */
    public static CcoPalettedChunkStorage decodeBlocks(int chunkX, int chunkZ, byte[] payload) {
        CcoPalettedChunkStorage decoded = CcoPalettedChunkStorage.createEmpty(
                WorldConfiguration.CHUNK_SIZE, WorldConfiguration.WORLD_HEIGHT,
                WorldConfiguration.CHUNK_SIZE, BlockType.AIR);
        try {
            VoxelChunkCodec.decodeInto(payload, new StorageBlockSetter(decoded), GameBlockTypeResolver.INSTANCE);
        } catch (Exception e) {
            logger.error("[NETWORK] Failed to decode chunk ({},{}): {}", chunkX, chunkZ, e.getMessage());
            return null;
        }
        return decoded;
    }

    /** Height of the first non-opaque cell above each column, indexed {@code z * CHUNK_SIZE + x}. */
    public static int[] computeSkyHeights(CcoPalettedChunkStorage decoded) {
        int size = WorldConfiguration.CHUNK_SIZE;
        int[] heights = new int[size * size];
        for (int lz = 0; lz < size; lz++) {
            for (int lx = 0; lx < size; lx++) {
                int top = 0;
                for (int y = WorldConfiguration.WORLD_HEIGHT - 1; y >= 0; y--) {
                    if (BlockOpacity.isOpaque((BlockType) decoded.get(lx, y, lz))) {
                        top = y + 1;
                        break;
                    }
                }
                heights[lz * size + lx] = top;
            }
        }
        return heights;
    }
}
