package com.stonebreak.world.operations;

/**
 * Central configuration class for world generation, rendering, and performance settings.
 * Provides constants and configurable parameters for the chunk-based world system.
 */
public class WorldConfiguration {
    // World constants
    public static final int CHUNK_SIZE = 16;
    public static final int WORLD_HEIGHT = 256;
    public static final int SEA_LEVEL = 64;

    // Rendering settings
    public static final int DEFAULT_RENDER_DISTANCE = 8;

    // LOD (distant terrain) settings
    public static final int DEFAULT_LOD_RANGE = 24;
    public static final int MIN_LOD_RANGE = 0;
    public static final int MAX_LOD_RANGE = 48;
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
    
    // Render/LOD distances are settings-driven and mutable at runtime; worker count is fixed.
    private volatile int renderDistance;
    private final int chunkBuildThreads;
    private volatile int lodRange;
    private volatile boolean lodEnabled;

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

    public int getChunkBuildThreads() {
        return chunkBuildThreads;
    }

    public int getLodRange() {
        return lodRange;
    }

    public boolean isLodEnabled() {
        return lodEnabled;
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