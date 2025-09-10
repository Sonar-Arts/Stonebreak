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
     * 
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier (e.g., "tree", "ore", "flower") to separate random streams
     * @return A Random instance seeded deterministically for this position and feature
     */
    public Random getRandomForPosition(int x, int z, String feature) {
        // Combine world seed, coordinates, and feature hash to create a unique seed
        long positionSeed = worldSeed;
        positionSeed = positionSeed * 31L + x;
        positionSeed = positionSeed * 31L + z;
        positionSeed = positionSeed * 31L + feature.hashCode();
        
        return new Random(positionSeed);
    }
    
    /**
     * Gets a deterministic Random instance for the given world coordinates and Y level.
     * Useful for 3D features like ore generation that need Y-coordinate dependency.
     * 
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param feature Feature identifier to separate random streams
     * @return A Random instance seeded deterministically for this 3D position and feature
     */
    public Random getRandomForPosition3D(int x, int y, int z, String feature) {
        long positionSeed = worldSeed;
        positionSeed = positionSeed * 31L + x;
        positionSeed = positionSeed * 31L + y;
        positionSeed = positionSeed * 31L + z;
        positionSeed = positionSeed * 31L + feature.hashCode();
        
        return new Random(positionSeed);
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