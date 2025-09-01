package com.stonebreak.world;

/**
 * Exception thrown when chunk generation fails.
 * Provides context about which chunk failed and why.
 */
public class ChunkGenerationException extends Exception {
    private final int chunkX;
    private final int chunkZ;
    
    public ChunkGenerationException(String message, int chunkX, int chunkZ) {
        super(message);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }
    
    public ChunkGenerationException(String message, int chunkX, int chunkZ, Throwable cause) {
        super(message, cause);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }
    
    public int getChunkX() {
        return chunkX;
    }
    
    public int getChunkZ() {
        return chunkZ;
    }
    
    @Override
    public String toString() {
        return String.format("ChunkGenerationException at (%d, %d): %s", chunkX, chunkZ, getMessage());
    }
}