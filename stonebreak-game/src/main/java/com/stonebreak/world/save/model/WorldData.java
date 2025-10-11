package com.stonebreak.world.save.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.joml.Vector3f;
import java.time.LocalDateTime;

/**
 * Pure data model for world metadata.
 * No serialization logic - follows SOLID principles.
 * Immutable for thread safety.
 */
public final class WorldData {
    private final long seed;
    private final String worldName;
    private final Vector3f spawnPosition;
    private final LocalDateTime createdTime;
    private final LocalDateTime lastPlayed;
    private final long totalPlayTimeMillis;
    private final long worldTimeTicks;
    private final int formatVersion;

    @JsonCreator
    private WorldData(
            @JsonProperty("seed") long seed,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("spawnPosition") Vector3f spawnPosition,
            @JsonProperty("createdTime") @JsonAlias("creationTime") LocalDateTime createdTime,
            @JsonProperty("lastPlayed") LocalDateTime lastPlayed,
            @JsonProperty("totalPlayTimeMillis") long totalPlayTimeMillis,
            @JsonProperty("worldTimeTicks") long worldTimeTicks,
            @JsonProperty("formatVersion") int formatVersion) {
        this.seed = seed;
        this.worldName = worldName;
        this.spawnPosition = new Vector3f(spawnPosition);
        this.createdTime = createdTime;
        this.lastPlayed = lastPlayed;
        this.totalPlayTimeMillis = totalPlayTimeMillis;
        this.worldTimeTicks = worldTimeTicks;
        this.formatVersion = formatVersion;
    }

    private WorldData(Builder builder) {
        this(builder.seed, builder.worldName, builder.spawnPosition,
             builder.createdTime, builder.lastPlayed, builder.totalPlayTimeMillis,
             builder.worldTimeTicks, builder.formatVersion);
    }

    // Getters
    public long getSeed() { return seed; }
    public String getWorldName() { return worldName; }
    public Vector3f getSpawnPosition() { return new Vector3f(spawnPosition); }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public LocalDateTime getLastPlayed() { return lastPlayed; }
    public long getTotalPlayTimeMillis() { return totalPlayTimeMillis; }
    public long getWorldTimeTicks() { return worldTimeTicks; }
    public int getFormatVersion() { return formatVersion; }

    /**
     * Creates a new WorldData with updated last played time.
     */
    public WorldData withLastPlayed(LocalDateTime lastPlayed) {
        return new Builder(this)
            .lastPlayed(lastPlayed)
            .build();
    }

    /**
     * Creates a new WorldData with added play time.
     */
    public WorldData withAddedPlayTime(long additionalMillis) {
        return new Builder(this)
            .totalPlayTimeMillis(this.totalPlayTimeMillis + additionalMillis)
            .lastPlayed(LocalDateTime.now())
            .build();
    }

    /**
     * Creates a new WorldData with updated world time.
     */
    public WorldData withWorldTime(long timeTicks) {
        return new Builder(this)
            .worldTimeTicks(timeTicks)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long seed;
        private String worldName;
        private Vector3f spawnPosition = new Vector3f(0, 100, 0);
        private LocalDateTime createdTime = LocalDateTime.now();
        private LocalDateTime lastPlayed = LocalDateTime.now();
        private long totalPlayTimeMillis = 0;
        private long worldTimeTicks = 6000; // Default to NOON
        private int formatVersion = 1;

        public Builder() {}

        public Builder(WorldData data) {
            this.seed = data.seed;
            this.worldName = data.worldName;
            this.spawnPosition = new Vector3f(data.spawnPosition);
            this.createdTime = data.createdTime;
            this.lastPlayed = data.lastPlayed;
            this.totalPlayTimeMillis = data.totalPlayTimeMillis;
            this.worldTimeTicks = data.worldTimeTicks;
            this.formatVersion = data.formatVersion;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder worldName(String worldName) {
            this.worldName = worldName;
            return this;
        }

        public Builder spawnPosition(Vector3f spawnPosition) {
            this.spawnPosition = new Vector3f(spawnPosition);
            return this;
        }

        public Builder createdTime(LocalDateTime createdTime) {
            this.createdTime = createdTime;
            return this;
        }

        public Builder lastPlayed(LocalDateTime lastPlayed) {
            this.lastPlayed = lastPlayed;
            return this;
        }

        public Builder totalPlayTimeMillis(long totalPlayTimeMillis) {
            this.totalPlayTimeMillis = totalPlayTimeMillis;
            return this;
        }

        public Builder worldTimeTicks(long worldTimeTicks) {
            this.worldTimeTicks = worldTimeTicks;
            return this;
        }

        public Builder formatVersion(int formatVersion) {
            this.formatVersion = formatVersion;
            return this;
        }

        public WorldData build() {
            if (worldName == null || worldName.isEmpty()) {
                throw new IllegalStateException("World name cannot be null or empty");
            }
            return new WorldData(this);
        }
    }
}
