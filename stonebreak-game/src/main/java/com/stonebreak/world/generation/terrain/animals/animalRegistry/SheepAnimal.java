package com.stonebreak.world.generation.terrain.animals.animalRegistry;

import java.util.Random;

import com.stonebreak.world.generation.terrain.animals.Animal;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3d;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.World;

public class SheepAnimal implements Animal {

    @Override
    public String getName() {
        return "sheep";
    }

    @Override
    public void spawn(World world, Cube chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = world.getEntityManager(); // this world's own (#340)
        if (entityManager == null) return;

        long chunkX = chunk.cx();
        long chunkZ = chunk.cz();

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
                localX = random.nextInt(Cube.EDGE);
                localZ = random.nextInt(Cube.EDGE);
            }

            long worldX = chunkX * Cube.EDGE + localX;
            long worldZ = chunkZ * Cube.EDGE + localZ;

            long surfaceY = Long.MIN_VALUE; // none found: rejected by isValidSpawnLocation
            for (long y = chunk.world().topY(worldX, worldZ) - 1; y >= WorldConfiguration.MIN_Y; y--) {
                if (Animal.blockAt(chunk, localX, y, localZ) != BlockType.AIR) {
                    surfaceY = y + 1;
                    break;
                }
            }

            if (isValidSpawnLocation(chunk, localX, surfaceY, localZ)) {
                Vector3d spawnPos = new Vector3d(
                    worldX + 0.5,
                    surfaceY,
                    worldZ + 0.5
                );

                // Defense-in-depth: reuse the modern spawner's footing/headroom guard
                // (solid non-trunk block below, two passable cells above) so the legacy
                // path can't place sheep inside terrain, in water, or in a tree.
                com.stonebreak.mobs.entities.EntitySpawner spawner = world.getEntitySpawner();
                if (spawner != null && !spawner.isValidSpawnLocation(spawnPos, EntityType.SHEEP)) {
                    continue;
                }

                entityManager.spawnEntity(EntityType.SHEEP, spawnPos);
                spawned++;
            }
        }
    }

    @Override
    public boolean canSpawnInChunk(Cube chunk) {
        // Called by mobs/entities/CubeEntityStore when the cube's column becomes READY (initial animals).
        return Animal.surfaceHas(chunk, BlockType.GRASS);
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

    private boolean isValidSpawnLocation(Cube chunk, int localX, long y, int localZ) {
        if (localX < 0 || localX >= Cube.EDGE
                || localZ < 0 || localZ >= Cube.EDGE) {
            return false;
        }
        if (y <= WorldConfiguration.MIN_Y || y >= WorldConfiguration.MAX_Y - 1) {
            return false;
        }

        BlockType groundBlock = Animal.blockAt(chunk, localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }

        BlockType spawnBlock = Animal.blockAt(chunk, localX, y, localZ);
        BlockType aboveBlock = Animal.blockAt(chunk, localX, y + 1, localZ);

        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
}
