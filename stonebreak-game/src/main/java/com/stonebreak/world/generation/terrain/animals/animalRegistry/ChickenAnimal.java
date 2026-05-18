package com.stonebreak.world.generation.terrain.animals.animalRegistry;

import java.util.Random;

import com.stonebreak.world.generation.terrain.animals.Animal;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;

/**
 * Chicken animal implementation for world generation spawning.
 *
 * <p>Registered alongside {@code CowAnimal} in {@code AnimalGenerator}; each
 * registered animal rolls its spawn probability independently, so chickens
 * appear with the same frequency as cows without displacing them.
 */
public class ChickenAnimal implements Animal {

    @Override
    public String getName() {
        return "chicken";
    }

    @Override
    public void spawn(World world, Chunk chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        int chickenCount;
        synchronized (randomLock) {
            chickenCount = getMinSpawnCount() + random.nextInt(getMaxSpawnCount() - getMinSpawnCount() + 1);
        }

        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20;

        while (spawned < chickenCount && attempts < maxAttempts) {
            attempts++;

            int localX, localZ;
            synchronized (randomLock) {
                localX = random.nextInt(WorldConfiguration.CHUNK_SIZE);
                localZ = random.nextInt(WorldConfiguration.CHUNK_SIZE);
            }

            int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + localX;
            int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + localZ;

            // Find surface height
            int surfaceY = 0;
            for (int y = WorldConfiguration.WORLD_HEIGHT - 1; y >= 0; y--) {
                if (chunk.getBlock(localX, y, localZ) != BlockType.AIR) {
                    surfaceY = y + 1;
                    break;
                }
            }

            if (isValidSpawnLocation(chunk, localX, surfaceY, localZ)) {
                Vector3f spawnPos = new Vector3f(
                    worldX + 0.5f,
                    surfaceY,
                    worldZ + 0.5f
                );

                entityManager.spawnEntity(EntityType.CHICKEN, spawnPos);
                spawned++;
            }
        }
    }

    @Override
    public boolean canSpawnInChunk(Chunk chunk) {
        // Chickens spawn in chunks with grass blocks (plains biome), like cows.
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
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
        return 0.3; // Same probability as cows — independent roll.
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
     * Checks if the given location is valid for chicken spawning.
     */
    private boolean isValidSpawnLocation(Chunk chunk, int localX, int y, int localZ) {
        if (localX < 0 || localX >= WorldConfiguration.CHUNK_SIZE
                || localZ < 0 || localZ >= WorldConfiguration.CHUNK_SIZE) {
            return false;
        }
        if (y < 1 || y >= WorldConfiguration.WORLD_HEIGHT - 1) {
            return false;
        }

        // Ground must be grass.
        BlockType groundBlock = chunk.getBlock(localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }

        // Spawn space must be clear.
        BlockType spawnBlock = chunk.getBlock(localX, y, localZ);
        BlockType aboveBlock = chunk.getBlock(localX, y + 1, localZ);

        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
}
