package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.world.Chunk;
import com.stonebreak.blocks.BlockType;

import java.util.Random;

/**
 * Handles natural entity spawning during world generation and runtime.
 * Manages spawn rules, biome restrictions, and population limits.
 */
public class EntitySpawner {
    private final World world;
    private final EntityManager entityManager;
    private final Random random;
    
    // Spawning rules for cows
    private static final int MAX_COWS_PER_CHUNK = 4;
    private static final float COW_SPAWN_CHANCE = 0.1f; // 10% chance per chunk generation
    private static final int MIN_SPAWN_HEIGHT = 60;
    private static final int MAX_SPAWN_HEIGHT = 120;
    
    // Biome types where cows can spawn (basic implementation)
    // Note: Stonebreak doesn't have complex biomes yet, so we'll use basic terrain checks
    
    /**
     * Creates a new entity spawner for the specified world.
     */
    public EntitySpawner(World world, EntityManager entityManager) {
        this.world = world;
        this.entityManager = entityManager;
        this.random = new Random();
    }
    
    /**
     * Called when a chunk is generated to spawn entities.
     */
    public void spawnEntitiesInChunk(Chunk chunk) {
        if (chunk == null) return;
        
        // Check if we should spawn cows in this chunk
        if (random.nextFloat() < COW_SPAWN_CHANCE) {
            spawnCowsInChunk(chunk);
        }
    }
    
    /**
     * Spawns cows in a specific chunk based on terrain suitability.
     */
    private void spawnCowsInChunk(Chunk chunk) {
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        
        // Convert chunk coordinates to world coordinates
        int worldX = chunkX * 16;
        int worldZ = chunkZ * 16;
        
        // Try to spawn 1-4 cows in this chunk
        int cowsToSpawn = 1 + random.nextInt(MAX_COWS_PER_CHUNK);
        int cowsSpawned = 0;
        
        // Attempt spawning with limited tries to avoid infinite loops
        int maxAttempts = 20;
        for (int attempt = 0; attempt < maxAttempts && cowsSpawned < cowsToSpawn; attempt++) {
            // Pick random position within chunk
            int x = worldX + random.nextInt(16);
            int z = worldZ + random.nextInt(16);
            
            // Find suitable Y position
            int y = findSuitableSpawnHeight(x, z);
            if (y > 0) {
                Vector3f spawnPos = new Vector3f(x + 0.5f, y, z + 0.5f);
                
                if (isValidSpawnLocation(spawnPos, EntityType.COW)) {
                    // Spawn the cow
                    Entity cow = entityManager.spawnEntity(EntityType.COW, spawnPos);
                    if (cow != null) {
                        cowsSpawned++;
                    }
                }
            }
        }
    }
    
    /**
     * Finds a suitable spawn height at the given x,z coordinates.
     */
    private int findSuitableSpawnHeight(int x, int z) {
        // Search from top down to find the first solid surface with air above
        for (int y = MAX_SPAWN_HEIGHT; y >= MIN_SPAWN_HEIGHT; y--) {
            BlockType groundBlock = world.getBlockAt(x, y, z);
            BlockType airBlock1 = world.getBlockAt(x, y + 1, z);
            BlockType airBlock2 = world.getBlockAt(x, y + 2, z);
            
            // Check for solid ground with 2 blocks of air above
            if (groundBlock != null && groundBlock != BlockType.AIR && groundBlock != BlockType.WATER &&
                (airBlock1 == null || airBlock1 == BlockType.AIR) &&
                (airBlock2 == null || airBlock2 == BlockType.AIR)) {
                
                return y + 1; // Spawn on top of the ground block
            }
        }
        
        return -1; // No suitable spawn height found
    }
    
    /**
     * Checks if a location is valid for spawning the specified entity type.
     */
    public boolean isValidSpawnLocation(Vector3f position, EntityType type) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y);
        int z = (int) Math.floor(position.z);
        
        return switch (type) {
            case COW -> isValidCowSpawnLocation(x, y, z);
            default -> false;
        };
    }
    
    /**
     * Checks if a location is suitable for cow spawning.
     */
    private boolean isValidCowSpawnLocation(int x, int y, int z) {
        // Check ground block
        BlockType groundBlock = world.getBlockAt(x, y - 1, z);
        if (groundBlock == null || groundBlock == BlockType.AIR || groundBlock == BlockType.WATER) {
            return false;
        }
        
        // Check for enough space (cow is 1.4 blocks tall)
        BlockType airBlock1 = world.getBlockAt(x, y, z);
        BlockType airBlock2 = world.getBlockAt(x, y + 1, z);
        if ((airBlock1 != null && airBlock1 != BlockType.AIR) ||
            (airBlock2 != null && airBlock2 != BlockType.AIR)) {
            return false;
        }
        
        // Check not in water
        if (world.getBlockAt(x, y, z) == BlockType.WATER ||
            world.getBlockAt(x, y + 1, z) == BlockType.WATER) {
            return false;
        }
        
        // Check for nearby entities to avoid overcrowding
        if (hasNearbyEntities(new Vector3f(x, y, z), 5.0f, 3)) {
            return false;
        }
        
        // Check terrain suitability (prefer grassy areas)
        if (isGrassyTerrain(x, y - 1, z)) {
            return true;
        }
        
        // Accept other solid terrain as backup
        return true;
    }
    
    /**
     * Checks if the terrain at a location is grassy (preferred for cows).
     */
    private boolean isGrassyTerrain(int x, int y, int z) {
        BlockType block = world.getBlockAt(x, y, z);
        // In Stonebreak, we'll check for grass or dirt blocks
        // This is a basic implementation that can be expanded
        return block == BlockType.GRASS || block == BlockType.DIRT;
    }
    
    /**
     * Checks if there are too many entities nearby.
     */
    private boolean hasNearbyEntities(Vector3f position, float radius, int maxCount) {
        int nearbyCount = entityManager.getEntitiesInRange(position, radius).size();
        return nearbyCount >= maxCount;
    }
    
    /**
     * Spawns a specific number of cows near a center position (for testing/commands).
     */
    public void spawnCowHerd(Vector3f center, int count) {
        int spawned = 0;
        int maxAttempts = count * 5; // 5 attempts per cow
        
        for (int attempt = 0; attempt < maxAttempts && spawned < count; attempt++) {
            // Generate random position within 8 blocks of center
            float offsetX = (random.nextFloat() - 0.5f) * 16.0f;
            float offsetZ = (random.nextFloat() - 0.5f) * 16.0f;
            
            Vector3f spawnPos = new Vector3f(
                center.x + offsetX,
                center.y,
                center.z + offsetZ
            );
            
            // Find ground level
            int groundY = findSuitableSpawnHeight((int)spawnPos.x, (int)spawnPos.z);
            if (groundY > 0) {
                spawnPos.y = groundY;
                
                if (isValidSpawnLocation(spawnPos, EntityType.COW)) {
                    Entity cow = entityManager.spawnEntity(EntityType.COW, spawnPos);
                    if (cow != null) {
                        spawned++;
                    }
                }
            }
        }
    }
    
    /**
     * Gets spawn statistics for debugging.
     */
    public String getSpawnStats() {
        int totalCows = entityManager.getEntitiesByType(EntityType.COW).size();
        return String.format("Total cows: %d", totalCows);
    }
    
    /**
     * Forces spawning of entities for testing (ignores normal spawn rules).
     */
    public Entity forceSpawnEntity(EntityType type, Vector3f position) {
        return entityManager.spawnEntity(type, position);
    }
    
    // Getters
    public float getCowSpawnChance() { return COW_SPAWN_CHANCE; }
    public int getMaxCowsPerChunk() { return MAX_COWS_PER_CHUNK; }
}