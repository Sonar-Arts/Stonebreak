package com.stonebreak.world.operations;

/**
 * Central configuration class for world generation, rendering, and performance settings.
 * Provides constants and configurable parameters for the chunk-based world system.
 */
public class WorldConfiguration {
    // World shape (cubic voxel runtime, Stonebreak #447 / OpenMason #89 G5). The world is 1024 blocks
    // tall, Y in [MIN_Y, MAX_Y), stored as 32^3 cubes in the engine's native CubeWorld. There is no
    // column height any more: code that used WORLD_HEIGHT asks MIN_Y / MAX_Y (or the world's own
    // CubeWorld bounds) instead.
    /** Lowest block y (inclusive): the bedrock floor. */
    public static final int MIN_Y = -384;
    /** One past the highest block y. */
    public static final int MAX_Y = 640;
    /** Total world height in blocks ({@code MAX_Y - MIN_Y}). */
    public static final int HEIGHT = MAX_Y - MIN_Y;
    public static final int SEA_LEVEL = 64;
    /** Cube edge in blocks ({@code CubeWorld.CUBE_EDGE}); cube coordinate = block coordinate {@code >> CUBE_SHIFT}. */
    public static final int CUBE_EDGE = 32;
    public static final int CUBE_SHIFT = 5;
    /** Lowest / one past the highest cube y ({@code MIN_Y >> 5}, {@code MAX_Y >> 5}). */
    public static final int MIN_CUBE_Y = MIN_Y >> CUBE_SHIFT;
    public static final int MAX_CUBE_Y = MAX_Y >> CUBE_SHIFT;

    /**
     * The column-era 16-block chunk edge. Nothing in the world model uses it any more (cubes are
     * {@link #CUBE_EDGE}); it survives only for code that still buckets by 16 (FastLOD L0 nodes,
     * network keys, entity bookkeeping) until the 3B agents move those to cubes.
     */
    @Deprecated
    public static final int CHUNK_SIZE = 16;

    /** True when {@code y} is inside the world's vertical bounds. */
    public static boolean inBounds(long y) {
        return y >= MIN_Y && y < MAX_Y;
    }

    /**
     * Sentinel water level for a column that holds no standing water. {@code Integer.MIN_VALUE}: it can never be
     * a height (the world reaches y = -384, so the old -1 was a real water level), equal to the native
     * generator's own sentinel. Stored formats map it to their own value (FastLOD i16: {@code Short.MIN_VALUE});
     * the DaedalusTGM tile planes keep their -1 ({@code TerrainTile.NO_WATER}, tile domain only).
     */
    public static final int NO_WATER = Integer.MIN_VALUE;

    // Rendering settings
    public static final int DEFAULT_RENDER_DISTANCE = 8;

    // LOD (distant terrain) settings
    public static final int DEFAULT_LOD_RANGE = 24;
    public static final int MIN_LOD_RANGE = 0;
    /** Slider ceiling (chunks). Dev: {@code -Dstonebreak.lod.maxRange} lifts it for measurements (never saved past 64 by the UI). */
    public static final int MAX_LOD_RANGE = Integer.getInteger("stonebreak.lod.maxRange", 64);
    public static final boolean DEFAULT_LOD_ENABLED = true;

    // Render-distance bounds for settings UI / runtime tuning.
    public static final int MIN_RENDER_DISTANCE = 4;
    public static final int MAX_RENDER_DISTANCE = 24;

    // Memory management
    public static final int MAX_CHUNK_POSITION_CACHE_SIZE = 10000;

    // Mesh generation
    public static final int MAX_FAILED_CHUNK_RETRIES = 3;

    // Performance logging
    public static final int GPU_CLEANUP_LOG_THRESHOLD = 10;
    public static final int MEMORY_LOG_INTERVAL = 10;

    // Threading
    public static final int CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS = 2;
    
    /** Default vertical half-height of the streaming ellipsoid ("Vertical Render Distance"), blocks. */
    public static final int DEFAULT_VERTICAL_RENDER_DISTANCE = 128;

    // Render/LOD distances are settings-driven and mutable at runtime; worker count is fixed.
    private volatile int renderDistance;
    private volatile int verticalRenderDistance = DEFAULT_VERTICAL_RENDER_DISTANCE;
    private final int chunkBuildThreads;
    private volatile int lodRange;
    private volatile boolean lodEnabled;
    private volatile com.stonebreak.world.fastlod.FastLodQuality lodQuality =
        com.stonebreak.world.fastlod.FastLodQuality.DEFAULT;

    public WorldConfiguration() {
        this(DEFAULT_RENDER_DISTANCE, calculateOptimalThreadCount());
    }

    public WorldConfiguration(int renderDistance, int chunkBuildThreads) {
        this(renderDistance, chunkBuildThreads, DEFAULT_LOD_RANGE, DEFAULT_LOD_ENABLED);
    }

    public WorldConfiguration(int renderDistance, int chunkBuildThreads, int lodRange, boolean lodEnabled) {
        this.renderDistance = Math.max(1, renderDistance);
        this.chunkBuildThreads = Math.max(1, chunkBuildThreads);
        this.lodRange = Math.max(0, lodRange);
        this.lodEnabled = lodEnabled;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    /** Vertical half-height of the streaming ellipsoid, in blocks. */
    public int getVerticalRenderDistance() {
        return verticalRenderDistance;
    }

    public void setVerticalRenderDistance(int blocks) {
        this.verticalRenderDistance = Math.max(32, Math.min(HEIGHT, blocks));
    }

    /** Horizontal streaming radius in blocks (render distance is still counted in 16-block steps). */
    public int getHorizontalRenderRadiusBlocks() {
        return renderDistance * 16;
    }

    public int getChunkBuildThreads() {
        return chunkBuildThreads;
    }

    public int getLodRange() {
        return lodRange;
    }

    public boolean isLodEnabled() {
        return lodEnabled;
    }

    public com.stonebreak.world.fastlod.FastLodQuality getLodQuality() {
        return lodQuality;
    }

    public int getBorderChunkDistance() {
        return renderDistance + 1;
    }

    public void setRenderDistance(int value) {
        this.renderDistance = Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, value));
    }

    public void setLodRange(int value) {
        this.lodRange = Math.max(MIN_LOD_RANGE, Math.min(MAX_LOD_RANGE, value));
    }

    public void setLodEnabled(boolean value) {
        this.lodEnabled = value;
    }

    public void setLodQuality(com.stonebreak.world.fastlod.FastLodQuality value) {
        this.lodQuality = value != null ? value : com.stonebreak.world.fastlod.FastLodQuality.DEFAULT;
    }

    private static int calculateOptimalThreadCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    public static class Builder {
        private int renderDistance = DEFAULT_RENDER_DISTANCE;
        private int chunkBuildThreads = calculateOptimalThreadCount();
        private int lodRange = DEFAULT_LOD_RANGE;
        private boolean lodEnabled = DEFAULT_LOD_ENABLED;

        public Builder lodRange(int value) { this.lodRange = value; return this; }
        public Builder lodEnabled(boolean value) { this.lodEnabled = value; return this; }

        public WorldConfiguration build() {
            return new WorldConfiguration(renderDistance, chunkBuildThreads, lodRange, lodEnabled);
        }
    }
}