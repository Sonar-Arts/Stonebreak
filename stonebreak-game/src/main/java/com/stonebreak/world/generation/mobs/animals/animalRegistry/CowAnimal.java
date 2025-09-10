package com.stonebreak.world.generation.mobs.animals.animalRegistry;

import java.util.Random;

import com.stonebreak.world.generation.mobs.animals.Animal;
import org.joml.Vector3f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.Chunk;
import com.stonebreak.world.World;

/**
 * Cow animal implementation for world generation spawning.
 */
public class CowAnimal implements Animal {
    
    private static final String[] COW_VARIANTS = {"default", "angus", "highland", "jersey"};
    
    @Override
    public String getName() {
        return "cow";
    }
    
    @Override
    public void spawn(World world, Chunk chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;
        
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        
        // Determine number of cows to spawn
        int cowCount;
        synchronized (randomLock) {
            cowCount = getMinSpawnCount() + random.nextInt(getMaxSpawnCount() - getMinSpawnCount() + 1);
        }
        
        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20;
        
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
                    surfaceY = y + 1;
                    break;
                }
            }
            
            // Check if valid spawn location
            if (isValidSpawnLocation(chunk, localX, surfaceY, localZ)) {
                Vector3f spawnPos = new Vector3f(
                    worldX + 0.5f,
                    surfaceY,
                    worldZ + 0.5f
                );
                
                // Select random texture variant
                String textureVariant;
                synchronized (randomLock) {
                    textureVariant = COW_VARIANTS[random.nextInt(COW_VARIANTS.length)];
                }
                
                entityManager.spawnCowWithVariant(spawnPos, textureVariant);
                spawned++;
            }
        }
    }
    
    @Override
    public boolean canSpawnInChunk(Chunk chunk) {
        // Cows can spawn in chunks with grass blocks (plains biome)
        // Check if chunk has any grass blocks
        for (int x = 0; x < World.CHUNK_SIZE; x++) {
            for (int z = 0; z < World.CHUNK_SIZE; z++) {
                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    if (chunk.getBlock(x, y, z) == BlockType.GRASS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    @Override
    public double getSpawnProbability() {
        return 0.3; // 30% chance to spawn cows in valid chunks
    }
    
    @Override
    public int getMinSpawnCount() {
        return 1;
    }
    
    @Override
    public int getMaxSpawnCount() {
        return 4;
    }
    
    /**
     * Checks if the given location is valid for cow spawning.
     */
    private boolean isValidSpawnLocation(Chunk chunk, int localX, int y, int localZ) {
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