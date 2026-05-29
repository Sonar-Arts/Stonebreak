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

public class SheepAnimal implements Animal {

    @Override
    public String getName() {
        return "sheep";
    }

    @Override
    public void spawn(World world, Chunk chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        int sheepCount;
        synchronized (randomLock) {
            sheepCount = getMinSpawnCount() + random.nextInt(getMaxSpawnCount() - getMinSpawnCount() + 1);
        }

        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20;

        while (spawned < sheepCount && attempts < maxAttempts) {
            attempts++;

            int localX, localZ;
            synchronized (randomLock) {
                localX = random.nextInt(WorldConfiguration.CHUNK_SIZE);
                localZ = random.nextInt(WorldConfiguration.CHUNK_SIZE);
            }

            int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + localX;
            int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + localZ;

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

                entityManager.spawnEntity(EntityType.SHEEP, spawnPos);
                spawned++;
            }
        }
    }

    @Override
    public boolean canSpawnInChunk(Chunk chunk) {
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                for (int y = WorldConfiguration.WORLD_HEIGHT - 1; y >= 0; y--) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == BlockType.GRASS) return true;
                    if (b != BlockType.AIR) break; // hit a non-grass surface; skip this column
                }
            }
        }
        return false;
    }

    @Override
    public double getSpawnProbability() {
        return 0.3;
    }

    @Override
    public int getMinSpawnCount() {
        return 1;
    }

    @Override
    public int getMaxSpawnCount() {
        return 4;
    }

    private boolean isValidSpawnLocation(Chunk chunk, int localX, int y, int localZ) {
        if (localX < 0 || localX >= WorldConfiguration.CHUNK_SIZE
                || localZ < 0 || localZ >= WorldConfiguration.CHUNK_SIZE) {
            return false;
        }
        if (y < 1 || y >= WorldConfiguration.WORLD_HEIGHT - 1) {
            return false;
        }

        BlockType groundBlock = chunk.getBlock(localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }

        BlockType spawnBlock = chunk.getBlock(localX, y, localZ);
        BlockType aboveBlock = chunk.getBlock(localX, y + 1, localZ);

        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
}
