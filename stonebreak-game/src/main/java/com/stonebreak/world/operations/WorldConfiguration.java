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

    // Memory management
    public static final int MAX_CHUNK_POSITION_CACHE_SIZE = 10000;

    // Mesh generation
    public static final int MAX_FAILED_CHUNK_RETRIES = 3;

    // Performance logging
    public static final int GPU_CLEANUP_LOG_THRESHOLD = 10;
    public static final int MEMORY_LOG_INTERVAL = 10;

    // Threading
    public static final int CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS = 2;
    
    private final int renderDistance;
    private final int chunkBuildThreads;

    public WorldConfiguration() {
        this(DEFAULT_RENDER_DISTANCE, calculateOptimalThreadCount());
    }

    public WorldConfiguration(int renderDistance, int chunkBuildThreads) {
        this.renderDistance = Math.max(1, renderDistance);
        this.chunkBuildThreads = Math.max(1, chunkBuildThreads);
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public int getChunkBuildThreads() {
        return chunkBuildThreads;
    }



    public int getBorderChunkDistance() {
        return renderDistance + 1;
    }

    private static int calculateOptimalThreadCount() {
        // Use all available cores for maximum mesh generation throughput
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    public static class Builder {
        private int renderDistance = DEFAULT_RENDER_DISTANCE;
        private int chunkBuildThreads = calculateOptimalThreadCount();


        public WorldConfiguration build() {
            return new WorldConfiguration(renderDistance, chunkBuildThreads);
        }
    }
}