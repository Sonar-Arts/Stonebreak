package com.stonebreak.world.generation.terrain.animals.animalRegistry;

import java.util.Random;

import com.stonebreak.world.generation.terrain.animals.Animal;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3d;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.chunk.Cube;
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
    public void spawn(World world, Cube chunk, Random random, Object randomLock) {
        com.stonebreak.mobs.entities.EntityManager entityManager = world.getEntityManager(); // this world's own (#340)
        if (entityManager == null) return;

        long chunkX = chunk.cx();
        long chunkZ = chunk.cz();

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
                localX = random.nextInt(Cube.EDGE);
                localZ = random.nextInt(Cube.EDGE);
            }

            long worldX = chunkX * Cube.EDGE + localX;
            long worldZ = chunkZ * Cube.EDGE + localZ;

            // Find surface height
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
                // path can't place chickens inside terrain, in water, or in a tree.
                com.stonebreak.mobs.entities.EntitySpawner spawner = world.getEntitySpawner();
                if (spawner != null && !spawner.isValidSpawnLocation(spawnPos, EntityType.CHICKEN)) {
                    continue;
                }

                entityManager.spawnEntity(EntityType.CHICKEN, spawnPos);
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
    private boolean isValidSpawnLocation(Cube chunk, int localX, long y, int localZ) {
        if (localX < 0 || localX >= Cube.EDGE
                || localZ < 0 || localZ >= Cube.EDGE) {
            return false;
        }
        if (y <= WorldConfiguration.MIN_Y || y >= WorldConfiguration.MAX_Y - 1) {
            return false;
        }

        // Ground must be grass.
        BlockType groundBlock = Animal.blockAt(chunk, localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }

        // Spawn space must be clear.
        BlockType spawnBlock = Animal.blockAt(chunk, localX, y, localZ);
        BlockType aboveBlock = Animal.blockAt(chunk, localX, y + 1, localZ);

        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
}
