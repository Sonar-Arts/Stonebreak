package com.stonebreak.world.generation.animals;

import java.util.Random;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;

/**
 * Interface for animal types that can be spawned during world generation.
 * Each animal implementation defines its own spawning logic and requirements.
 */
public interface Animal {
    
    /**
     * Gets the name identifier for this animal type.
     * 
     * @return The animal's name (e.g., "cow", "sheep", "pig")
     */
    String getName();
    
    /**
     * Spawns this animal type in the specified chunk.
     * 
     * @param world The world instance
     * @param chunk The chunk to spawn animals in
     * @param random Random number generator for spawning decisions
     * @param randomLock Synchronization lock for thread-safe random access
     */
    void spawn(World world, Chunk chunk, Random random, Object randomLock);
    
    /**
     * Checks if this animal can spawn in the given chunk based on biome and other conditions.
     * 
     * @param chunk The chunk to check
     * @return true if this animal can spawn in the chunk, false otherwise
     */
    boolean canSpawnInChunk(Chunk chunk);
    
    /**
     * Gets the spawn probability for this animal type (0.0 to 1.0).
     * Used to determine how frequently this animal should spawn.
     * 
     * @return Spawn probability between 0.0 (never) and 1.0 (always)
     */
    double getSpawnProbability();
    
    /**
     * Gets the minimum number of animals to spawn when this type is selected.
     * 
     * @return Minimum spawn count
     */
    int getMinSpawnCount();
    
    /**
     * Gets the maximum number of animals to spawn when this type is selected.
     * 
     * @return Maximum spawn count
     */
    int getMaxSpawnCount();
}