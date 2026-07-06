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
 * Goose animal implementation for world generation spawning.
 *
 * <p>Geese spawn grounded on grass in small flocks; the {@link com.stonebreak.mobs.goose.GooseAI}
 * handles takeoff and V-formation flight afterwards. Unlike the cow there are no appearance
 * variants, so spawning routes through the generic {@code EntityManager.spawnEntity} path.
 */
public class GooseAnimal implements Animal {

    @Override
    public String getName() {
        return "goose";
    }

    @Override
    public void spawn(World world, Chunk chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        // Determine number of geese to spawn (a small flock)
        int gooseCount;
        synchronized (randomLock) {
            gooseCount = getMinSpawnCount() + random.nextInt(getMaxSpawnCount() - getMinSpawnCount() + 1);
        }

        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20;

        while (spawned < gooseCount && attempts < maxAttempts) {
            attempts++;

            // Random position within chunk
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

            // Check if valid spawn location
            if (isValidSpawnLocation(chunk, localX, surfaceY, localZ)) {
                Vector3f spawnPos = new Vector3f(
                    worldX + 0.5f,
                    surfaceY,
                    worldZ + 0.5f
                );

                // Defense-in-depth: reuse the modern spawner's anti-cave/overhang
                // (sky-exposure) guard so the legacy path can't place geese underground.
                com.stonebreak.mobs.entities.EntitySpawner spawner = world.getEntitySpawner();
                if (spawner != null && !spawner.isValidSpawnLocation(spawnPos, EntityType.GOOSE)) {
                    continue;
                }

                entityManager.spawnEntity(EntityType.GOOSE, spawnPos);
                spawned++;
            }
        }
    }

    @Override
    public boolean canSpawnInChunk(Chunk chunk) {
        // Geese can spawn in chunks with grass blocks (plains biome)
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
        return 0.2; // 20% chance to spawn geese in valid chunks
    }

    @Override
    public int getMinSpawnCount() {
        return 3;
    }

    @Override
    public int getMaxSpawnCount() {
        return 5;
    }

    /**
     * Checks if the given location is valid for goose spawning.
     */
    private boolean isValidSpawnLocation(Chunk chunk, int localX, int y, int localZ) {
        // Check bounds
        if (localX < 0 || localX >= WorldConfiguration.CHUNK_SIZE || localZ < 0 || localZ >= WorldConfiguration.CHUNK_SIZE) {
            return false;
        }
        if (y < 1 || y >= WorldConfiguration.WORLD_HEIGHT - 1) {
            return false;
        }

        // Check ground block (must be grass)
        BlockType groundBlock = chunk.getBlock(localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }

        // Check spawn space (must be air for goose's height)
        BlockType spawnBlock = chunk.getBlock(localX, y, localZ);
        BlockType aboveBlock = chunk.getBlock(localX, y + 1, localZ);

        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
}
