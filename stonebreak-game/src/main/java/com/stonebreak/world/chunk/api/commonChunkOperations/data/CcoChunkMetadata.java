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
    private final long createdTime;
    private final long lastModifiedTime;
    private final long generationSeed;
    private final boolean hasStructures;
    private final boolean needsDecoration;
    private final boolean hasEntities;

    /**
     * Creates chunk metadata with full parameters.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param createdTime Creation timestamp (millis since epoch)
     * @param lastModifiedTime Last modification timestamp (millis since epoch)
     * @param generationSeed World generation seed
     * @param hasStructures Has generated structures
     * @param needsDecoration Needs decoration pass
     * @param hasEntities Has spawned entities
     */
    public CcoChunkMetadata(int chunkX, int chunkZ, long createdTime, long lastModifiedTime,
                           long generationSeed, boolean hasStructures, boolean needsDecoration,
                           boolean hasEntities) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.createdTime = createdTime;
        this.lastModifiedTime = lastModifiedTime;
        this.generationSeed = generationSeed;
        this.hasStructures = hasStructures;
        this.needsDecoration = needsDecoration;
        this.hasEntities = hasEntities;
    }

    /**
     * Creates chunk metadata (legacy 4-parameter constructor for backward compatibility).
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param lastModified Timestamp of last modification
     * @param featuresPopulated Whether features (trees, structures) are populated
     */
    public CcoChunkMetadata(int chunkX, int chunkZ, LocalDateTime lastModified, boolean featuresPopulated) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        long timestamp = java.time.ZoneOffset.UTC.getRules()
            .getOffset(lastModified)
            .getTotalSeconds() * 1000;
        this.createdTime = timestamp;
        this.lastModifiedTime = timestamp;
        this.generationSeed = 0;
        this.hasStructures = featuresPopulated;
        this.needsDecoration = false;
        this.hasEntities = false;
    }

    /**
     * Creates new metadata for a freshly created chunk.
     */
    public static CcoChunkMetadata forNewChunk(int chunkX, int chunkZ) {
        long now = System.currentTimeMillis();
        return new CcoChunkMetadata(chunkX, chunkZ, now, now, 0, false, false, false);
    }

    /**
     * Creates new metadata for a chunk with generation seed.
     */
    public static CcoChunkMetadata forNewChunk(int chunkX, int chunkZ, long generationSeed) {
        long now = System.currentTimeMillis();
        return new CcoChunkMetadata(chunkX, chunkZ, now, now, generationSeed, false, false, false);
    }

    /**
     * Creates a copy with updated timestamp.
     */
    public CcoChunkMetadata withUpdatedTimestamp() {
        return new CcoChunkMetadata(chunkX, chunkZ, createdTime, System.currentTimeMillis(),
            generationSeed, hasStructures, needsDecoration, hasEntities);
    }

    /**
     * Creates a copy with features marked as populated.
     */
    public CcoChunkMetadata withFeaturesPopulated() {
        return new CcoChunkMetadata(chunkX, chunkZ, createdTime, lastModifiedTime,
            generationSeed, true, needsDecoration, hasEntities);
    }

    /**
     * Creates a copy with structures flag set.
     */
    public CcoChunkMetadata withStructures(boolean hasStructures) {
        return new CcoChunkMetadata(chunkX, chunkZ, createdTime, lastModifiedTime,
            generationSeed, hasStructures, needsDecoration, hasEntities);
    }

    /**
     * Creates a copy with decoration flag set.
     */
    public CcoChunkMetadata withDecoration(boolean needsDecoration) {
        return new CcoChunkMetadata(chunkX, chunkZ, createdTime, lastModifiedTime,
            generationSeed, hasStructures, needsDecoration, hasEntities);
    }

    /**
     * Creates a copy with entities flag set.
     */
    public CcoChunkMetadata withEntities(boolean hasEntities) {
        return new CcoChunkMetadata(chunkX, chunkZ, createdTime, lastModifiedTime,
            generationSeed, hasStructures, needsDecoration, hasEntities);
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public LocalDateTime getLastModified() {
        return LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModifiedTime),
            java.time.ZoneId.systemDefault()
        );
    }

    public long getGenerationSeed() {
        return generationSeed;
    }

    public boolean hasStructures() {
        return hasStructures;
    }

    public boolean needsDecoration() {
        return needsDecoration;
    }

    public boolean hasEntities() {
        return hasEntities;
    }

    public boolean isFeaturesPopulated() {
        return hasStructures;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CcoChunkMetadata that = (CcoChunkMetadata) o;
        return chunkX == that.chunkX &&
               chunkZ == that.chunkZ &&
               createdTime == that.createdTime &&
               lastModifiedTime == that.lastModifiedTime &&
               generationSeed == that.generationSeed &&
               hasStructures == that.hasStructures &&
               needsDecoration == that.needsDecoration &&
               hasEntities == that.hasEntities;
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkX, chunkZ, createdTime, lastModifiedTime, generationSeed,
            hasStructures, needsDecoration, hasEntities);
    }

    @Override
    public String toString() {
        return String.format("CcoChunkMetadata{pos=(%d,%d), created=%d, modified=%d, seed=%d, " +
                "structures=%s, decoration=%s, entities=%s}",
                chunkX, chunkZ, createdTime, lastModifiedTime, generationSeed,
                hasStructures, needsDecoration, hasEntities);
    }
}
