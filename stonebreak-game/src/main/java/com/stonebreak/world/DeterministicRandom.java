package com.stonebreak.world;

import java.util.Random;

/**
 * Provides deterministic random number generation based on world coordinates and seed.
 * This ensures that the same world coordinates with the same seed always produce
 * the same random values, making world generation fully reproducible.
 * 
 * This class is thread-safe and designed for use in world generation features like
 * tree placement, ore generation, flower spawning, etc.
 */
public class DeterministicRandom {
    
    private final long worldSeed;
    
    /**
     * Creates a new DeterministicRandom instance with the given world seed.
     * @param worldSeed The world seed to use for all coordinate-based random generation
     */
    public DeterministicRandom(long worldSeed) {
        this.worldSeed = worldSeed;
    }
    
    /**
     * Gets a deterministic Random instance for the given world coordinates.
     * The same coordinates will always produce the same Random sequence.
     * Uses a high-quality hash function to eliminate coordinate patterns.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier (e.g., "tree", "ore", "flower") to separate random streams
     * @return A Random instance seeded deterministically for this position and feature
     */
    public Random getRandomForPosition(int x, int z, String feature) {
        // Use a high-quality hash function to eliminate coordinate patterns
        // Based on Wang hash algorithm for better distribution
        long hash = worldSeed;

        // Mix in X coordinate with large prime and bit manipulation
        hash ^= (long)x * 0x9e3779b9L;
        hash ^= hash >>> 16;

        // Mix in Z coordinate with different large prime
        hash ^= (long)z * 0x85ebca6bL;
        hash ^= hash >>> 13;

        // Mix in feature hash with another large prime
        hash ^= (long)feature.hashCode() * 0xc2b2ae35L;
        hash ^= hash >>> 16;

        // Final avalanche to ensure good distribution
        hash ^= hash >>> 32;

        return new Random(hash);
    }
    
    /**
     * Gets a deterministic Random instance for the given world coordinates and Y level.
     * Useful for 3D features like ore generation that need Y-coordinate dependency.
     * Uses a high-quality hash function to eliminate coordinate patterns.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier to separate random streams
     * @return A Random instance seeded deterministically for this 3D position and feature
     */
    public Random getRandomForPosition3D(int x, int y, int z, String feature) {
        // Use a high-quality hash function to eliminate coordinate patterns
        // Extended version of Wang hash for 3D coordinates
        long hash = worldSeed;

        // Mix in X coordinate with large prime and bit manipulation
        hash ^= (long)x * 0x9e3779b9L;
        hash ^= hash >>> 16;

        // Mix in Y coordinate with different large prime
        hash ^= (long)y * 0x8f8f8f8fL;
        hash ^= hash >>> 11;

        // Mix in Z coordinate with another large prime
        hash ^= (long)z * 0x85ebca6bL;
        hash ^= hash >>> 13;

        // Mix in feature hash with yet another large prime
        hash ^= (long)feature.hashCode() * 0xc2b2ae35L;
        hash ^= hash >>> 16;

        // Final avalanche to ensure good distribution
        hash ^= hash >>> 32;

        return new Random(hash);
    }
    
    /**
     * Convenience method to get a float value [0.0, 1.0) for the given coordinates.
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @return A float value between 0.0 (inclusive) and 1.0 (exclusive)
     */
    public float getFloat(int x, int z, String feature) {
        return getRandomForPosition(x, z, feature).nextFloat();
    }
    
    /**
     * Convenience method to get a float value [0.0, 1.0) for the given 3D coordinates.
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @return A float value between 0.0 (inclusive) and 1.0 (exclusive)
     */
    public float getFloat3D(int x, int y, int z, String feature) {
        return getRandomForPosition3D(x, y, z, feature).nextFloat();
    }
    
    /**
     * Convenience method to get a boolean value for the given coordinates.
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @return A boolean value
     */
    public boolean getBoolean(int x, int z, String feature) {
        return getRandomForPosition(x, z, feature).nextBoolean();
    }
    
    /**
     * Convenience method to get a boolean value for the given 3D coordinates.
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @return A boolean value
     */
    public boolean getBoolean3D(int x, int y, int z, String feature) {
        return getRandomForPosition3D(x, y, z, feature).nextBoolean();
    }
    
    /**
     * Convenience method to get an integer value for the given coordinates.
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @param bound Upper bound (exclusive)
     * @return An int value between 0 (inclusive) and bound (exclusive)
     */
    public int getInt(int x, int z, String feature, int bound) {
        return getRandomForPosition(x, z, feature).nextInt(bound);
    }
    
    /**
     * Convenience method to get an integer value for the given 3D coordinates.
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @param bound Upper bound (exclusive)
     * @return An int value between 0 (inclusive) and bound (exclusive)
     */
    public int getInt3D(int x, int y, int z, String feature, int bound) {
        return getRandomForPosition3D(x, y, z, feature).nextInt(bound);
    }
    
    /**
     * Convenience method to check if a feature should be generated based on probability.
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @param probability Probability threshold (0.0 to 1.0)
     * @return true if the feature should be generated
     */
    public boolean shouldGenerate(int x, int z, String feature, float probability) {
        return getFloat(x, z, feature) < probability;
    }
    
    /**
     * Convenience method to check if a 3D feature should be generated based on probability.
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier
     * @param probability Probability threshold (0.0 to 1.0)
     * @return true if the feature should be generated
     */
    public boolean shouldGenerate3D(int x, int y, int z, String feature, float probability) {
        return getFloat3D(x, y, z, feature) < probability;
    }
}