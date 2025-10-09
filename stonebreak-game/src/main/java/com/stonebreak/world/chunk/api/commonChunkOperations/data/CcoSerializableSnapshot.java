package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of chunk data for serialization.
 * Designed for efficient conversion to save system's ChunkData model.
 *
 * Lazy deep-copy: blocks are referenced (zero-copy) until ChunkData builder copies them.
 * Thread-safe through immutability.
 *
 * WATER METADATA INTEGRATION:
 * Water flow levels are stored as part of the chunk snapshot to ensure they persist correctly.
 * This follows the CCO principle of keeping all chunk state together.
 */
public final class CcoSerializableSnapshot {
    private final int chunkX;
    private final int chunkZ;
    private final BlockType[][][] blocks;  // Direct reference - ChunkData will copy
    private final LocalDateTime lastModified;
    private final boolean featuresPopulated;
    private final Map<String, ChunkData.WaterBlockData> waterMetadata;  // Water flow levels (non-source only)

    /**
     * Creates a serializable snapshot.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param blocks Block array (will be deep-copied by ChunkData)
     * @param lastModified Last modification timestamp
     * @param featuresPopulated Whether features are populated
     */
    public CcoSerializableSnapshot(int chunkX, int chunkZ, BlockType[][][] blocks,
                                   LocalDateTime lastModified, boolean featuresPopulated) {
        this(chunkX, chunkZ, blocks, lastModified, featuresPopulated, new HashMap<>());
    }

    /**
     * Creates a serializable snapshot with water metadata.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param blocks Block array (will be deep-copied by ChunkData)
     * @param lastModified Last modification timestamp
     * @param featuresPopulated Whether features are populated
     * @param waterMetadata Water flow level metadata (defensive copy made)
     */
    public CcoSerializableSnapshot(int chunkX, int chunkZ, BlockType[][][] blocks,
                                   LocalDateTime lastModified, boolean featuresPopulated,
                                   Map<String, ChunkData.WaterBlockData> waterMetadata) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = Objects.requireNonNull(blocks, "blocks cannot be null");
        this.lastModified = Objects.requireNonNull(lastModified, "lastModified cannot be null");
        this.featuresPopulated = featuresPopulated;
        this.waterMetadata = waterMetadata != null ? new HashMap<>(waterMetadata) : new HashMap<>();
    }

    /**
     * Creates a snapshot from CCO metadata and block array.
     */
    public static CcoSerializableSnapshot from(CcoChunkMetadata metadata, CcoBlockArray blockArray) {
        return new CcoSerializableSnapshot(
                metadata.getChunkX(),
                metadata.getChunkZ(),
                blockArray.getUnderlyingArray(),
                metadata.getLastModified(),
                metadata.isFeaturesPopulated()
        );
    }

    /**
     * Converts this snapshot to the save system's ChunkData model.
     * ChunkData will perform a deep copy of the blocks array.
     *
     * @return ChunkData ready for serialization
     */
    public ChunkData toChunkData() {
        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)  // ChunkData builder will deep-copy
                .lastModified(lastModified)
                .featuresPopulated(featuresPopulated)
                .waterMetadata(waterMetadata)  // CCO-integrated water metadata
                .build();
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public BlockType[][][] getBlocks() {
        return blocks;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public boolean isFeaturesPopulated() {
        return featuresPopulated;
    }

    public Map<String, ChunkData.WaterBlockData> getWaterMetadata() {
        return new HashMap<>(waterMetadata);  // Defensive copy
    }

    @Override
    public String toString() {
        return String.format("CcoSerializableSnapshot{pos=(%d,%d), features=%s, modified=%s}",
                chunkX, chunkZ, featuresPopulated, lastModified);
    }
}
