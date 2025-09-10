package com.stonebreak.world.generation.mobs.animals;

import java.util.Random;

import org.joml.Vector3f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.Chunk;
import com.stonebreak.world.World;

/**
 * Handles spawning of animals in the world during chunk generation.
 */
public class AnimalGenerator {
    
    /**
     * Spawns cows in a plains chunk at valid grass locations.
     * 
     * @param world The world instance
     * @param chunk The chunk to spawn cows in
     * @param random Random number generator
     * @param randomLock Synchronization lock for random access
     */
    public static void spawnCows(World world, Chunk chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;
        
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        
        // Determine number of cows to spawn (1-4)
        int cowCount;
        synchronized (randomLock) {
            cowCount = 1 + random.nextInt(4); // 1, 2, 3, or 4 cows
        }
        
        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20; // Limit attempts to prevent infinite loops
        
        while (spawned < cowCount && attempts < maxAttempts) {
            attempts++;
            
            // Random position within chunk
            int localX, localZ;
            synchronized (randomLock) {
                localX = random.nextInt(World.CHUNK_SIZE);
                localZ = random.nextInt(World.CHUNK_SIZE);
            }
            
            int worldX = chunkX * World.CHUNK_SIZE + localX;
            int worldZ = chunkZ * World.CHUNK_SIZE + localZ;
            
            // Find surface height
            int surfaceY = 0;
            for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--) {
                if (chunk.getBlock(localX, y, localZ) != BlockType.AIR) {
                    surfaceY = y + 1; // First air block above ground
                    break;
                }
            }
            
            // Check if valid spawn location
            if (isValidCowSpawnLocation(chunk, localX, surfaceY, localZ)) {
                // Spawn cow at this location
                Vector3f spawnPos = new Vector3f(
                    worldX + 0.5f, // Center of block
                    surfaceY,
                    worldZ + 0.5f  // Center of block
                );
                
                // Select random texture variant for world generation cow spawning
                String[] variants = {"default", "angus", "highland"};
                String textureVariant = variants[(int)(Math.random() * variants.length)];
                entityManager.spawnCowWithVariant(spawnPos, textureVariant);
                spawned++;
            }
        }
    }
    
    /**
     * Checks if the given location is valid for cow spawning.
     * Requires grass block below, air blocks above, and not in water.
     */
    private static boolean isValidCowSpawnLocation(Chunk chunk, int localX, int y, int localZ) {
        // Check bounds
        if (localX < 0 || localX >= World.CHUNK_SIZE || localZ < 0 || localZ >= World.CHUNK_SIZE) {
            return false;
        }
        if (y < 1 || y >= World.WORLD_HEIGHT - 1) {
            return false;
        }
        
        // Check ground block (must be grass)
        BlockType groundBlock = chunk.getBlock(localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }
        
        // Check spawn space (must be air for cow's height)
        BlockType spawnBlock = chunk.getBlock(localX, y, localZ);
        BlockType aboveBlock = chunk.getBlock(localX, y + 1, localZ);
        
        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
}