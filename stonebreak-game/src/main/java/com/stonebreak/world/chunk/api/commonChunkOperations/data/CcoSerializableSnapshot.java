package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable snapshot of chunk data for serialization.
 * Designed for efficient conversion to save system's ChunkData model.
 *
 * Lazy deep-copy: blocks are referenced (zero-copy) until ChunkData builder copies them.
 * Thread-safe through immutability.
 */
public final class CcoSerializableSnapshot {
    private final int chunkX;
    private final int chunkZ;
    private final BlockType[][][] blocks;  // Direct reference - ChunkData will copy
    private final LocalDateTime lastModified;
    private final boolean featuresPopulated;

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
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = Objects.requireNonNull(blocks, "blocks cannot be null");
        this.lastModified = Objects.requireNonNull(lastModified, "lastModified cannot be null");
        this.featuresPopulated = featuresPopulated;
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

    @Override
    public String toString() {
        return String.format("CcoSerializableSnapshot{pos=(%d,%d), features=%s, modified=%s}",
                chunkX, chunkZ, featuresPopulated, lastModified);
    }
}
