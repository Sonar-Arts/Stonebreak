package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable metadata for a chunk in the CCO API.
 * Contains position, timestamps, and feature flags.
 *
 * Thread-safe through immutability.
 * Designed for save/load system integration.
 */
public final class CcoChunkMetadata {
    private final int chunkX;
    private final int chunkZ;
    private final LocalDateTime lastModified;
    private final boolean featuresPopulated;

    /**
     * Creates chunk metadata.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param lastModified Timestamp of last modification
     * @param featuresPopulated Whether features (trees, structures) are populated
     */
    public CcoChunkMetadata(int chunkX, int chunkZ, LocalDateTime lastModified, boolean featuresPopulated) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.lastModified = Objects.requireNonNull(lastModified, "lastModified cannot be null");
        this.featuresPopulated = featuresPopulated;
    }

    /**
     * Creates new metadata for a freshly created chunk.
     */
    public static CcoChunkMetadata forNewChunk(int chunkX, int chunkZ) {
        return new CcoChunkMetadata(chunkX, chunkZ, LocalDateTime.now(), false);
    }

    /**
     * Creates a copy with updated timestamp.
     */
    public CcoChunkMetadata withUpdatedTimestamp() {
        return new CcoChunkMetadata(chunkX, chunkZ, LocalDateTime.now(), featuresPopulated);
    }

    /**
     * Creates a copy with features marked as populated.
     */
    public CcoChunkMetadata withFeaturesPopulated() {
        return new CcoChunkMetadata(chunkX, chunkZ, lastModified, true);
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public boolean isFeaturesPopulated() {
        return featuresPopulated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CcoChunkMetadata that = (CcoChunkMetadata) o;
        return chunkX == that.chunkX &&
               chunkZ == that.chunkZ &&
               featuresPopulated == that.featuresPopulated &&
               Objects.equals(lastModified, that.lastModified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkX, chunkZ, lastModified, featuresPopulated);
    }

    @Override
    public String toString() {
        return String.format("CcoChunkMetadata{pos=(%d,%d), modified=%s, features=%s}",
                chunkX, chunkZ, lastModified, featuresPopulated);
    }
}
