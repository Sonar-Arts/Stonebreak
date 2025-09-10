package com.stonebreak.world;

import java.util.Objects;

/**
 * Utility class representing a chunk position with X and Z coordinates.
 * Used for chunk management and save/load operations.
 */
public class ChunkPosition {
    
    private final int x;
    private final int z;
    
    /**
     * Creates a new ChunkPosition with the specified coordinates.
     * 
     * @param x The X coordinate of the chunk
     * @param z The Z coordinate of the chunk
     */
    public ChunkPosition(int x, int z) {
        this.x = x;
        this.z = z;
    }
    
    /**
     * Gets the X coordinate of this chunk position.
     * 
     * @return The X coordinate
     */
    public int getX() {
        return x;
    }
    
    /**
     * Gets the Z coordinate of this chunk position.
     * 
     * @return The Z coordinate
     */
    public int getZ() {
        return z;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ChunkPosition that = (ChunkPosition) obj;
        return x == that.x && z == that.z;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(x, z);
    }
    
    @Override
    public String toString() {
        return "ChunkPosition{x=" + x + ", z=" + z + "}";
    }
}