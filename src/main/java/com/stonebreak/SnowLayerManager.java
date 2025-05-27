package com.stonebreak;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Manages snow layer data for the world.
 * This is a simplified implementation - a full system would integrate this into the chunk system.
 */
public class SnowLayerManager {
    
    // Map from world position to snow layer count (1-8)
    private final Map<String, Integer> snowLayers = new ConcurrentHashMap<>();
    
    /**
     * Gets the snow layer count at a specific position
     * @param x World X coordinate
     * @param y World Y coordinate  
     * @param z World Z coordinate
     * @return Number of snow layers (1-8), or 0 if no snow
     */
    public int getSnowLayers(int x, int y, int z) {
        String key = x + "," + y + "," + z;
        return snowLayers.getOrDefault(key, 1); // Default to 1 layer if snow exists
    }
    
    /**
     * Sets the snow layer count at a specific position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate  
     * @param layers Number of snow layers (1-8)
     */
    public void setSnowLayers(int x, int y, int z, int layers) {
        if (layers < 1 || layers > 8) {
            throw new IllegalArgumentException("Snow layers must be between 1 and 8");
        }
        String key = x + "," + y + "," + z;
        snowLayers.put(key, layers);
    }
    
    /**
     * Removes snow layer data at a specific position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     */
    public void removeSnowLayers(int x, int y, int z) {
        String key = x + "," + y + "," + z;
        snowLayers.remove(key);
    }
    
    /**
     * Attempts to add a snow layer at the specified position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return true if layer was added, false if already at max layers (8)
     */
    public boolean addSnowLayer(int x, int y, int z) {
        int currentLayers = getSnowLayers(x, y, z);
        if (currentLayers < 8) {
            setSnowLayers(x, y, z, currentLayers + 1);
            return true;
        }
        return false;
    }
    
    /**
     * Gets the visual height of snow at a position (0.125 to 1.0)
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return Height as fraction of full block (0.125 * layers)
     */
    public float getSnowHeight(int x, int y, int z) {
        int layers = getSnowLayers(x, y, z);
        return layers * 0.125f;
    }
    
    /**
     * Clears all snow layer data (for chunk unloading, etc.)
     */
    public void clear() {
        snowLayers.clear();
    }
}