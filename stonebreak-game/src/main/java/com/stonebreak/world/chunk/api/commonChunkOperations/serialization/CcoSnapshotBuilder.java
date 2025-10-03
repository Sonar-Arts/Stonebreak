package com.stonebreak.world.chunk.api.commonChunkOperations.serialization;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.*;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Builder for creating serializable snapshots from CCO chunk components.
 * Provides a clean API for assembling snapshot data.
 *
 * Thread-safe through immutability of created snapshots.
 */
public final class CcoSnapshotBuilder {

    private CcoChunkMetadata metadata;
    private CcoBlockArray blockArray;

    /**
     * Sets metadata for the snapshot.
     *
     * @param metadata Chunk metadata
     * @return This builder
     */
    public CcoSnapshotBuilder metadata(CcoChunkMetadata metadata) {
        this.metadata = metadata;
        return this;
    }

    /**
     * Sets block array for the snapshot.
     *
     * @param blockArray Block array
     * @return This builder
     */
    public CcoSnapshotBuilder blocks(CcoBlockArray blockArray) {
        this.blockArray = blockArray;
        return this;
    }

    /**
     * Sets metadata from individual components.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param lastModified Last modification timestamp
     * @param featuresPopulated Features populated flag
     * @return This builder
     */
    public CcoSnapshotBuilder metadata(int chunkX, int chunkZ,
                                       LocalDateTime lastModified, boolean featuresPopulated) {
        this.metadata = new CcoChunkMetadata(chunkX, chunkZ, lastModified, featuresPopulated);
        return this;
    }

    /**
     * Builds the serializable snapshot.
     *
     * @return Immutable snapshot
     * @throws IllegalStateException if required fields are missing
     */
    public CcoSerializableSnapshot build() {
        Objects.requireNonNull(metadata, "metadata is required");
        Objects.requireNonNull(blockArray, "blockArray is required");

        return CcoSerializableSnapshot.from(metadata, blockArray);
    }

    /**
     * Creates a snapshot builder from existing CCO components.
     *
     * @param metadata Chunk metadata
     * @param blockArray Block array
     * @return New snapshot builder
     */
    public static CcoSnapshotBuilder from(CcoChunkMetadata metadata, CcoBlockArray blockArray) {
        return new CcoSnapshotBuilder()
                .metadata(metadata)
                .blocks(blockArray);
    }

    /**
     * Quickly builds a snapshot without using builder pattern.
     *
     * @param metadata Chunk metadata
     * @param blockArray Block array
     * @return Immutable snapshot
     */
    public static CcoSerializableSnapshot create(CcoChunkMetadata metadata, CcoBlockArray blockArray) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(blockArray, "blockArray cannot be null");
        return CcoSerializableSnapshot.from(metadata, blockArray);
    }
}
