package com.stonebreak.network.protocol;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;

import java.nio.ByteBuffer;

/**
 * Compact chunk serialization for network transfer.
 * Format: 16*256*16 unsigned shorts (block IDs), little-endian-agnostic via ByteBuffer big-endian default.
 */
public final class NetworkChunkCodec {

    public static final int CHUNK_W = 16;
    public static final int CHUNK_H = 256;
    public static final int BLOCK_COUNT = CHUNK_W * CHUNK_H * CHUNK_W;
    public static final int PAYLOAD_BYTES = BLOCK_COUNT * 2;

    private NetworkChunkCodec() {}

    public static byte[] encode(Chunk chunk) {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_BYTES);
        for (int x = 0; x < CHUNK_W; x++) {
            for (int y = 0; y < CHUNK_H; y++) {
                for (int z = 0; z < CHUNK_W; z++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    buf.putShort((short) (b == null ? 0 : b.getId()));
                }
            }
        }
        return buf.array();
    }

    /**
     * Apply payload bytes onto an existing chunk's block array.
     * Caller is responsible for triggering mesh rebuild after decode.
     */
    public static void decodeInto(byte[] payload, Chunk chunk) {
        if (payload.length != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Bad chunk payload size: " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        for (int x = 0; x < CHUNK_W; x++) {
            for (int y = 0; y < CHUNK_H; y++) {
                for (int z = 0; z < CHUNK_W; z++) {
                    int id = buf.getShort() & 0xFFFF;
                    BlockType b = BlockType.getById(id);
                    chunk.setBlock(x, y, z, b == null ? BlockType.AIR : b);
                }
            }
        }
    }
}
