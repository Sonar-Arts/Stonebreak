package com.stonebreak.network.bridge;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Deterministic content hash of a chunk's block ids, used by the desync audit: the client
 * periodically hashes resident chunks and sends them ({@code ChunkHashesC2S}); the server
 * compares against its own (cached) hashes and re-streams any mismatch.
 *
 * <p>FNV-1a over the 65 536 block ids in fixed Y→Z→X order. Both sides MUST iterate
 * identically — this class is the single implementation for both. Metadata (snow layers,
 * block states) is deliberately excluded: it self-heals on the re-stream anyway and keeping
 * the hash blocks-only keeps it cheap (~0.1 ms/chunk, zero allocation).
 */
public final class ChunkHasher {

    private static final int FNV_OFFSET = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    private ChunkHasher() {}

    public static int hash(Chunk chunk) {
        int h = FNV_OFFSET;
        for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    int id = b != null ? b.getId() : 0;
                    h ^= id & 0xFF;
                    h *= FNV_PRIME;
                    h ^= (id >>> 8) & 0xFF;
                    h *= FNV_PRIME;
                }
            }
        }
        return h;
    }
}
