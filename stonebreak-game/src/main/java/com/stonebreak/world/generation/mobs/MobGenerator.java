package com.stonebreak.world.generation.mobs;

import java.util.Random;

import com.stonebreak.world.biomes.BiomeType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.mobs.animals.AnimalGenerator;

/**
 * Central coordinator for mob generation during world generation.
 * Handles spawning of various mob types based on biome and other conditions.
 */
public class MobGenerator {
    
    /**
     * Processes mob spawning for a chunk based on its biome and conditions.
     *
     * @param world The world instance
     * @param chunk The chunk to process for mob spawning
     * @param biome The biome type of the chunk
     * @param random Random number generator for animal spawning
     * @param randomLock Synchronization lock for random access
     */
    public static void processChunkMobSpawning(World world, Chunk chunk, BiomeType biome, Random random, Object randomLock) {
        // Process animal spawning based on biome
        processAnimalSpawning(world, chunk, biome, random, randomLock);
        
        // Future: Add other mob types here
        // processHostileSpawning(world, chunk, biome, random, randomLock);
        // processNeutralSpawning(world, chunk, biome, random, randomLock);
    }
    
    /**
     * Handles spawning of animals in the chunk based on biome type.
     */
    private static void processAnimalSpawning(World world, Chunk chunk, BiomeType biome, Random random, Object randomLock) {
        switch (biome) {
            case PLAINS:
                // Spawn cows in PLAINS biome (10% chance per chunk)
                float cowSpawnChance;
                synchronized (randomLock) {
                    cowSpawnChance = random.nextFloat();
                }

                if (cowSpawnChance < 0.1f) { // 10% chance per chunk
                    AnimalGenerator.spawnCows(world, chunk, random, randomLock);
                }
                break;
                
            case SNOWY_PLAINS:
                // Future: Add cold-adapted animals
                break;
                
            case DESERT:
            case RED_SAND_DESERT:
                // Future: Add desert animals
                break;
                
            default:
                // No animal spawning for other biomes currently
                break;
        }
    }
}