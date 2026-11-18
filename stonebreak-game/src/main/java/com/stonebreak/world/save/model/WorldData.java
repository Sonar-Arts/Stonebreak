package com.stonebreak.world.save.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.weather.WeatherMode;
import com.stonebreak.world.weather.WeatherZone;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3fc;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pure data model for world metadata.
 * No serialization logic - follows SOLID principles.
 * Immutable for thread safety.
 */
public final class WorldData {
    private final long seed;
    private final String worldName;
    private final Vector3d spawnPosition;
    private final boolean hasExplicitSpawn;
    private final LocalDateTime createdTime;
    private final LocalDateTime lastPlayed;
    private final long totalPlayTimeMillis;
    private final long worldTimeTicks;
    private final boolean cheatsEnabled;
    /** Whether players may hurt each other (issue #343); off unless the host turns it on. */
    private final boolean pvpEnabled;
    private final long weatherSeconds;
    private final WeatherMode weatherMode;
    private final List<WeatherZone> weatherZones;
    private final int formatVersion;
    private final TerrainGeneratorType generatorType;
    /** Terrain scale preset chosen at world creation (#447); missing/unknown = ONE_TO_FOUR. */
    private final com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale;
    /** Vertical bounds [minY, maxY) and cube edge the world was created with (cubic format, #447). */
    private final long minY;
    private final long maxY;
    private final int cubeEdge;

    /**
     * Saves from before the generator choice existed were all made by the diffusion
     * generator, so a missing field keeps them generating the terrain they started with.
     */
    public static final TerrainGeneratorType LEGACY_GENERATOR = TerrainGeneratorType.DIFFUSION;

    @JsonCreator
    private WorldData(
            @JsonProperty("seed") long seed,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("spawnPosition") Vector3d spawnPosition,
            @JsonProperty("hasExplicitSpawn") boolean hasExplicitSpawn,
            @JsonProperty("createdTime") @JsonAlias("creationTime") LocalDateTime createdTime,
            @JsonProperty("lastPlayed") LocalDateTime lastPlayed,
            @JsonProperty("totalPlayTimeMillis") long totalPlayTimeMillis,
            @JsonProperty("worldTimeTicks") long worldTimeTicks,
            @JsonProperty("cheatsEnabled") boolean cheatsEnabled,
            @JsonProperty("pvpEnabled") boolean pvpEnabled,
            @JsonProperty("weatherSeconds") long weatherSeconds,
            @JsonProperty("weatherMode") String weatherMode,
            @JsonProperty("weatherZones") String weatherZones,
            @JsonProperty("formatVersion") int formatVersion,
            @JsonProperty("generatorType") String generatorType,
            @JsonProperty("terrainScale") String terrainScale,
            @JsonProperty("minY") Long minY,
            @JsonProperty("maxY") Long maxY,
            @JsonProperty("cubeEdge") Integer cubeEdge) {
        this(seed, worldName, spawnPosition != null ? spawnPosition : new Vector3d(0, 100, 0), hasExplicitSpawn,
             createdTime, lastPlayed, totalPlayTimeMillis,
             worldTimeTicks, cheatsEnabled, pvpEnabled, weatherSeconds, parseWeatherMode(weatherMode),
             WeatherZone.parseAll(weatherZones), formatVersion,
             TerrainGeneratorType.parse(generatorType, LEGACY_GENERATOR), parseTerrainScale(terrainScale),
             // Missing bounds = a world written before the cubic format: the old 0..256 column world.
             minY != null ? minY : com.stonebreak.world.save.WorldFormat.LEGACY_MIN_Y,
             maxY != null ? maxY : com.stonebreak.world.save.WorldFormat.LEGACY_MAX_Y,
             cubeEdge != null ? cubeEdge : com.stonebreak.world.save.WorldFormat.LEGACY_EDGE);
    }

    /** A persisted terrain scale name; missing or unknown names (older worlds) read as ONE_TO_FOUR. */
    public static com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset parseTerrainScale(String name) {
        if (name != null) {
            for (com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset p : com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.values()) {
                if (p.name().equalsIgnoreCase(name.trim())) {
                    return p;
                }
            }
        }
        return com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR;
    }

    /** Saves from before weather existed (or with an unknown mode) start in AUTO. */
    public static WeatherMode parseWeatherMode(String name) {
        WeatherMode mode = WeatherMode.parse(name);
        return mode != null ? mode : WeatherMode.AUTO;
    }

    private WorldData(long seed, String worldName, Vector3dc spawnPosition, boolean hasExplicitSpawn,
                      LocalDateTime createdTime, LocalDateTime lastPlayed, long totalPlayTimeMillis,
                      long worldTimeTicks, boolean cheatsEnabled, boolean pvpEnabled, long weatherSeconds,
                      WeatherMode weatherMode, List<WeatherZone> weatherZones, int formatVersion,
                      TerrainGeneratorType generatorType, com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                      long minY, long maxY, int cubeEdge) {
        this.seed = seed;
        this.worldName = worldName;
        this.spawnPosition = new Vector3d(spawnPosition);
        this.hasExplicitSpawn = hasExplicitSpawn;
        this.createdTime = createdTime;
        this.lastPlayed = lastPlayed;
        this.totalPlayTimeMillis = totalPlayTimeMillis;
        this.worldTimeTicks = worldTimeTicks;
        this.cheatsEnabled = cheatsEnabled;
        this.pvpEnabled = pvpEnabled;
        this.weatherSeconds = weatherSeconds;
        this.weatherMode = weatherMode;
        this.weatherZones = List.copyOf(weatherZones);
        this.formatVersion = formatVersion;
        this.generatorType = generatorType;
        this.terrainScale = terrainScale != null ? terrainScale : com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR;
        this.minY = minY;
        this.maxY = maxY;
        this.cubeEdge = cubeEdge;
    }

    private WorldData(Builder builder) {
        this(builder.seed, builder.worldName, builder.spawnPosition,
             builder.hasExplicitSpawn, builder.createdTime, builder.lastPlayed,
             builder.totalPlayTimeMillis, builder.worldTimeTicks, builder.cheatsEnabled,
             builder.pvpEnabled, builder.weatherSeconds, builder.weatherMode, builder.weatherZones, builder.formatVersion,
             builder.generatorType, builder.terrainScale, builder.minY, builder.maxY, builder.cubeEdge);
    }

    // Getters
    public long getSeed() { return seed; }
    public String getWorldName() { return worldName; }
    /** The world spawn (double world position, Phase 4). A copy. */
    public Vector3d getSpawnPosition() { return new Vector3d(spawnPosition); }
    public boolean hasExplicitSpawn() { return hasExplicitSpawn; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public LocalDateTime getLastPlayed() { return lastPlayed; }
    public long getTotalPlayTimeMillis() { return totalPlayTimeMillis; }
    public long getWorldTimeTicks() { return worldTimeTicks; }
    public boolean isCheatsEnabled() { return cheatsEnabled; }
    public boolean isPvpEnabled() { return pvpEnabled; }
    /** The weather clock, whole seconds (drives cloud drift; never wraps). */
    public long getWeatherSeconds() { return weatherSeconds; }
    public WeatherMode getWeatherMode() { return weatherMode; }
    /** Regions held at their own weather by {@code /weather}. */
    public List<WeatherZone> getWeatherZones() { return weatherZones; }
    public int getFormatVersion() { return formatVersion; }
    public TerrainGeneratorType getGeneratorType() { return generatorType; }
    public com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset getTerrainScale() { return terrainScale; }
    /** Lowest block y of the world (inclusive). */
    public long getMinY() { return minY; }
    /** Block y above the world (exclusive). */
    public long getMaxY() { return maxY; }
    /** Cube edge in blocks the saved cubes use. */
    public int getCubeEdge() { return cubeEdge; }

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
     * Creates a new WorldData with updated PvP rule.
     */
    public WorldData withPvpEnabled(boolean pvpEnabled) {
        return new Builder(this)
            .pvpEnabled(pvpEnabled)
            .build();
    }

    /**
     * Creates a new WorldData with updated weather clock, mode and zones.
     */
    public WorldData withWeather(long weatherSeconds, WeatherMode weatherMode, List<WeatherZone> weatherZones) {
        return new Builder(this)
            .weatherSeconds(weatherSeconds)
            .weatherMode(weatherMode)
            .weatherZones(weatherZones)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long seed;
        private String worldName;
        private Vector3d spawnPosition = new Vector3d(0, 100, 0);
        private boolean hasExplicitSpawn = false;
        private LocalDateTime createdTime = LocalDateTime.now();
        private LocalDateTime lastPlayed = LocalDateTime.now();
        private long totalPlayTimeMillis = 0;
        private long worldTimeTicks = 6000; // Default to NOON
        private boolean cheatsEnabled = false;
        private boolean pvpEnabled = false;
        private long weatherSeconds = 0;
        private WeatherMode weatherMode = WeatherMode.AUTO;
        private List<WeatherZone> weatherZones = List.of();
        /** New worlds are always written in the current format (a loaded record keeps its own). */
        private int formatVersion = com.stonebreak.world.save.WorldFormat.CURRENT_VERSION;
        private TerrainGeneratorType generatorType = TerrainGeneratorType.STANDARD;
        private com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale = com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR;
        private long minY = com.stonebreak.world.operations.WorldConfiguration.MIN_Y;
        private long maxY = com.stonebreak.world.operations.WorldConfiguration.MAX_Y;
        private int cubeEdge = com.stonebreak.world.operations.WorldConfiguration.CUBE_EDGE;

        public Builder() {}

        public Builder(WorldData data) {
            this.seed = data.seed;
            this.worldName = data.worldName;
            this.spawnPosition = new Vector3d(data.spawnPosition);
            this.hasExplicitSpawn = data.hasExplicitSpawn;
            this.createdTime = data.createdTime;
            this.lastPlayed = data.lastPlayed;
            this.totalPlayTimeMillis = data.totalPlayTimeMillis;
            this.worldTimeTicks = data.worldTimeTicks;
            this.cheatsEnabled = data.cheatsEnabled;
            this.pvpEnabled = data.pvpEnabled;
            this.weatherSeconds = data.weatherSeconds;
            this.weatherMode = data.weatherMode;
            this.weatherZones = data.weatherZones;
            this.formatVersion = data.formatVersion;
            this.generatorType = data.generatorType;
            this.terrainScale = data.terrainScale;
            this.minY = data.minY;
            this.maxY = data.maxY;
            this.cubeEdge = data.cubeEdge;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder worldName(String worldName) {
            this.worldName = worldName;
            return this;
        }

        public Builder spawnPosition(Vector3dc spawnPosition) {
            this.spawnPosition = new Vector3d(spawnPosition);
            return this;
        }

        /** Float convenience (widened exactly); prefer {@link #spawnPosition(Vector3dc)}. */
        public Builder spawnPosition(Vector3fc spawnPosition) {
            this.spawnPosition = new Vector3d(spawnPosition);
            return this;
        }

        public Builder spawnPosition(double x, double y, double z) {
            this.spawnPosition = new Vector3d(x, y, z);
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

        public Builder pvpEnabled(boolean pvpEnabled) {
            this.pvpEnabled = pvpEnabled;
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

        public Builder weatherZones(List<WeatherZone> weatherZones) {
            this.weatherZones = List.copyOf(weatherZones);
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

        public Builder terrainScale(com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale) {
            this.terrainScale = terrainScale != null ? terrainScale : com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR;
            return this;
        }

        /** Vertical bounds [minY, maxY) and cube edge (defaults: the running game's {@code WorldConfiguration}). */
        public Builder bounds(long minY, long maxY, int cubeEdge) {
            this.minY = minY;
            this.maxY = maxY;
            this.cubeEdge = cubeEdge;
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
