package com.stonebreak.world.operations;

public class WorldConfiguration {
    // World constants
    public static final int CHUNK_SIZE = 16;
    public static final int WORLD_HEIGHT = 256;
    public static final int SEA_LEVEL = 64;
    
    // Rendering settings
    public static final int DEFAULT_RENDER_DISTANCE = 8;
    
    // Memory management settings
    public static final int MAX_CHUNK_POSITION_CACHE_SIZE = 10000;
    public static final int EMERGENCY_CHUNK_THRESHOLD = 800;
    public static final int WARNING_CHUNK_THRESHOLD = 500;
    public static final int HIGH_CHUNK_THRESHOLD = 400;
    
    // Mesh building settings
    public static final int MAX_FAILED_CHUNK_RETRIES = 3;
    
    // Performance settings
    public static final int GPU_CLEANUP_LOG_THRESHOLD = 10;
    public static final int MEMORY_LOG_INTERVAL = 10; // Log every 10th unload
    
    // Threading settings
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
    
    public int getEmergencyUnloadDistance() {
        return renderDistance + 3;
    }
    
    public int getWarningUnloadDistance() {
        return renderDistance + 1;
    }
    
    public int getBorderChunkDistance() {
        return renderDistance + 1;
    }
    
    private static int calculateOptimalThreadCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
    
    public static class Builder {
        private int renderDistance = DEFAULT_RENDER_DISTANCE;
        private int chunkBuildThreads = calculateOptimalThreadCount();
        
        public Builder renderDistance(int renderDistance) {
            this.renderDistance = renderDistance;
            return this;
        }
        
        public Builder chunkBuildThreads(int threads) {
            this.chunkBuildThreads = threads;
            return this;
        }
        
        public WorldConfiguration build() {
            return new WorldConfiguration(renderDistance, chunkBuildThreads);
        }
    }
}