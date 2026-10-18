package com.stonebreak.world.save.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.weather.WeatherMode;
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
    private final boolean hasExplicitSpawn;
    private final LocalDateTime createdTime;
    private final LocalDateTime lastPlayed;
    private final long totalPlayTimeMillis;
    private final long worldTimeTicks;
    private final boolean cheatsEnabled;
    private final long weatherSeconds;
    private final WeatherMode weatherMode;
    private final int formatVersion;
    private final TerrainGeneratorType generatorType;

    /**
     * Saves from before the generator choice existed were all made by the diffusion
     * generator, so a missing field keeps them generating the terrain they started with.
     */
    public static final TerrainGeneratorType LEGACY_GENERATOR = TerrainGeneratorType.DIFFUSION;

    @JsonCreator
    private WorldData(
            @JsonProperty("seed") long seed,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("spawnPosition") Vector3f spawnPosition,
            @JsonProperty("hasExplicitSpawn") boolean hasExplicitSpawn,
            @JsonProperty("createdTime") @JsonAlias("creationTime") LocalDateTime createdTime,
            @JsonProperty("lastPlayed") LocalDateTime lastPlayed,
            @JsonProperty("totalPlayTimeMillis") long totalPlayTimeMillis,
            @JsonProperty("worldTimeTicks") long worldTimeTicks,
            @JsonProperty("cheatsEnabled") boolean cheatsEnabled,
            @JsonProperty("weatherSeconds") long weatherSeconds,
            @JsonProperty("weatherMode") String weatherMode,
            @JsonProperty("formatVersion") int formatVersion,
            @JsonProperty("generatorType") String generatorType) {
        this(seed, worldName, spawnPosition, hasExplicitSpawn, createdTime, lastPlayed, totalPlayTimeMillis,
             worldTimeTicks, cheatsEnabled, weatherSeconds, parseWeatherMode(weatherMode), formatVersion,
             TerrainGeneratorType.parse(generatorType, LEGACY_GENERATOR));
    }

    /** Saves from before weather existed (or with an unknown mode) start in AUTO. */
    public static WeatherMode parseWeatherMode(String name) {
        WeatherMode mode = WeatherMode.parse(name);
        return mode != null ? mode : WeatherMode.AUTO;
    }

    private WorldData(long seed, String worldName, Vector3f spawnPosition, boolean hasExplicitSpawn,
                      LocalDateTime createdTime, LocalDateTime lastPlayed, long totalPlayTimeMillis,
                      long worldTimeTicks, boolean cheatsEnabled, long weatherSeconds,
                      WeatherMode weatherMode, int formatVersion,
                      TerrainGeneratorType generatorType) {
        this.seed = seed;
        this.worldName = worldName;
        this.spawnPosition = new Vector3f(spawnPosition);
        this.hasExplicitSpawn = hasExplicitSpawn;
        this.createdTime = createdTime;
        this.lastPlayed = lastPlayed;
        this.totalPlayTimeMillis = totalPlayTimeMillis;
        this.worldTimeTicks = worldTimeTicks;
        this.cheatsEnabled = cheatsEnabled;
        this.weatherSeconds = weatherSeconds;
        this.weatherMode = weatherMode;
        this.formatVersion = formatVersion;
        this.generatorType = generatorType;
    }

    private WorldData(Builder builder) {
        this(builder.seed, builder.worldName, builder.spawnPosition,
             builder.hasExplicitSpawn, builder.createdTime, builder.lastPlayed,
             builder.totalPlayTimeMillis, builder.worldTimeTicks, builder.cheatsEnabled,
             builder.weatherSeconds, builder.weatherMode, builder.formatVersion,
             builder.generatorType);
    }

    // Getters
    public long getSeed() { return seed; }
    public String getWorldName() { return worldName; }
    public Vector3f getSpawnPosition() { return new Vector3f(spawnPosition); }
    public boolean hasExplicitSpawn() { return hasExplicitSpawn; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public LocalDateTime getLastPlayed() { return lastPlayed; }
    public long getTotalPlayTimeMillis() { return totalPlayTimeMillis; }
    public long getWorldTimeTicks() { return worldTimeTicks; }
    public boolean isCheatsEnabled() { return cheatsEnabled; }
    /** The weather clock, whole seconds (drives cloud drift; never wraps). */
    public long getWeatherSeconds() { return weatherSeconds; }
    public WeatherMode getWeatherMode() { return weatherMode; }
    public int getFormatVersion() { return formatVersion; }
    public TerrainGeneratorType getGeneratorType() { return generatorType; }

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

    /**
     * Creates a new WorldData with updated cheats flag.
     */
    public WorldData withCheatsEnabled(boolean cheatsEnabled) {
        return new Builder(this)
            .cheatsEnabled(cheatsEnabled)
            .build();
    }

    /**
     * Creates a new WorldData with updated weather clock and mode.
     */
    public WorldData withWeather(long weatherSeconds, WeatherMode weatherMode) {
        return new Builder(this)
            .weatherSeconds(weatherSeconds)
            .weatherMode(weatherMode)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long seed;
        private String worldName;
        private Vector3f spawnPosition = new Vector3f(0, 100, 0);
        private boolean hasExplicitSpawn = false;
        private LocalDateTime createdTime = LocalDateTime.now();
        private LocalDateTime lastPlayed = LocalDateTime.now();
        private long totalPlayTimeMillis = 0;
        private long worldTimeTicks = 6000; // Default to NOON
        private boolean cheatsEnabled = false;
        private long weatherSeconds = 0;
        private WeatherMode weatherMode = WeatherMode.AUTO;
        private int formatVersion = 1;
        private TerrainGeneratorType generatorType = TerrainGeneratorType.STANDARD;

        public Builder() {}

        public Builder(WorldData data) {
            this.seed = data.seed;
            this.worldName = data.worldName;
            this.spawnPosition = new Vector3f(data.spawnPosition);
            this.hasExplicitSpawn = data.hasExplicitSpawn;
            this.createdTime = data.createdTime;
            this.lastPlayed = data.lastPlayed;
            this.totalPlayTimeMillis = data.totalPlayTimeMillis;
            this.worldTimeTicks = data.worldTimeTicks;
            this.cheatsEnabled = data.cheatsEnabled;
            this.weatherSeconds = data.weatherSeconds;
            this.weatherMode = data.weatherMode;
            this.formatVersion = data.formatVersion;
            this.generatorType = data.generatorType;
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

        public Builder hasExplicitSpawn(boolean value) {
            this.hasExplicitSpawn = value;
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

        public Builder cheatsEnabled(boolean cheatsEnabled) {
            this.cheatsEnabled = cheatsEnabled;
            return this;
        }

        public Builder weatherSeconds(long weatherSeconds) {
            this.weatherSeconds = weatherSeconds;
            return this;
        }

        public Builder weatherMode(WeatherMode weatherMode) {
            this.weatherMode = weatherMode;
            return this;
        }

        public Builder formatVersion(int formatVersion) {
            this.formatVersion = formatVersion;
            return this;
        }

        public Builder generatorType(TerrainGeneratorType generatorType) {
            this.generatorType = generatorType;
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
