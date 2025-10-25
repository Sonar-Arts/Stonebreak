package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.player.Player;
import com.stonebreak.core.Game;

import java.util.Random;
import java.util.List;
import java.util.ArrayList;

/**
 * Handles natural entity spawning following Minecraft mechanics:
 *
 * INITIAL SPAWNING:
 * - Most animals spawn within chunks when they are generated
 *
 * CONTINUOUS SPAWNING:
 * - Passive mobs have a spawning cycle every 400 game ticks (20 seconds)
 * - Mobs spawn within chunks that have a player horizontally within 128 blocks
 * - Can only spawn within 128 block radius sphere centered on player
 *
 * DESPAWNING:
 * - Mobs that move farther than 128 blocks from nearest player despawn
 */
public class EntitySpawner {
    private final World world;
    private final EntityManager entityManager;
    private final Random random;

    // Minecraft spawning mechanics
    private static final int PASSIVE_MOB_SPAWN_TICKS = 400; // 20 seconds at 20 ticks/second
    private static final int SPAWN_RADIUS = 128; // blocks from player
    private static final int DESPAWN_RADIUS = 128; // blocks from nearest player

    // Initial spawning during chunk generation
    private static final float INITIAL_SPAWN_CHANCE = 0.15f; // 15% chance per chunk
    private static final int MIN_INITIAL_COWS = 2;
    private static final int MAX_INITIAL_COWS = 4;

    // Continuous spawning limits
    private static final int MAX_PASSIVE_MOBS_PER_PLAYER = 10; // Minecraft uses mob cap per player
    private static final int SPAWN_ATTEMPTS_PER_CYCLE = 3; // Number of spawn attempts per cycle

    // Spawn height limits
    private static final int MIN_SPAWN_HEIGHT = 60;
    private static final int MAX_SPAWN_HEIGHT = 120;

    // Cow texture variants
    private static final String[] COW_TEXTURE_VARIANTS = {"default", "angus", "highland", "jersey"};

    // Tick counter for spawning cycle
    private int tickCounter = 0;

    /**
     * Creates a new entity spawner for the specified world.
     */
    public EntitySpawner(World world, EntityManager entityManager) {
        this.world = world;
        this.entityManager = entityManager;
        this.random = new Random();
    }

    /**
     * Updates the spawner - handles continuous spawning cycle and despawning.
     * Should be called every game tick (20 times per second).
     */
    public void update(float deltaTime) {
        tickCounter++;

        // Passive mob spawning cycle every 400 ticks (20 seconds)
        if (tickCounter >= PASSIVE_MOB_SPAWN_TICKS) {
            tickCounter = 0;
            performContinuousSpawning();
        }

        // Check for mobs that need to despawn
        checkDespawning();
    }

    /**
     * Initial spawning when a chunk is generated.
     * "Most animals spawn within chunks when they are generated"
     */
    public void initialChunkSpawn(Chunk chunk) {
        // Roll for spawn chance
        if (random.nextFloat() > INITIAL_SPAWN_CHANCE) {
            return; // This chunk won't have initial cows
        }

        int cowsToSpawn = MIN_INITIAL_COWS + random.nextInt(MAX_INITIAL_COWS - MIN_INITIAL_COWS + 1);
        int cowsSpawned = 0;
        int maxAttempts = 20;

        for (int attempt = 0; attempt < maxAttempts && cowsSpawned < cowsToSpawn; attempt++) {
            Vector3f spawnPos = findSpawnLocationInChunk(chunk);
            if (spawnPos != null && isValidSpawnLocation(spawnPos, EntityType.COW)) {
                String variant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
                Entity cow = entityManager.spawnCowWithVariant(spawnPos, variant);
                if (cow != null) {
                    cowsSpawned++;
                }
            }
        }
    }

    /**
     * Continuous spawning cycle for passive mobs.
     * Spawns mobs near players following Minecraft rules.
     */
    private void performContinuousSpawning() {
        Player player = Game.getPlayer();
        if (player == null) {
            return;
        }

        // Check mob cap for this player
        int passiveMobCount = countPassiveMobsNearPlayer(player);
        if (passiveMobCount >= MAX_PASSIVE_MOBS_PER_PLAYER) {
            return; // Mob cap reached
        }

        // Attempt to spawn mobs near player
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS_PER_CYCLE; attempt++) {
            if (passiveMobCount >= MAX_PASSIVE_MOBS_PER_PLAYER) {
                break; // Mob cap reached during spawning
            }

            Vector3f spawnPos = findSpawnLocationNearPlayer(player);
            if (spawnPos != null && isValidSpawnLocation(spawnPos, EntityType.COW)) {
                String variant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
                Entity cow = entityManager.spawnCowWithVariant(spawnPos, variant);
                if (cow != null) {
                    passiveMobCount++;
                }
            }
        }
    }

    /**
     * Checks for mobs that are too far from players and despawns them.
     */
    private void checkDespawning() {
        Player player = Game.getPlayer();
        if (player == null) {
            return;
        }

        List<Entity> cows = entityManager.getEntitiesByType(EntityType.COW);
        for (Entity cow : cows) {
            float distance = cow.getPosition().distance(player.getPosition());
            if (distance > DESPAWN_RADIUS) {
                entityManager.removeEntity(cow);
            }
        }
    }

    /**
     * Finds a random spawn location within a chunk.
     */
    private Vector3f findSpawnLocationInChunk(Chunk chunk) {
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        int worldX = chunkX * 16;
        int worldZ = chunkZ * 16;

        // Pick random position within chunk
        int x = worldX + random.nextInt(16);
        int z = worldZ + random.nextInt(16);

        // Find suitable Y position
        int y = findSuitableSpawnHeight(x, z);
        if (y > 0) {
            return new Vector3f(x + 0.5f, y, z + 0.5f);
        }

        return null;
    }

    /**
     * Finds a spawn location near a player within 128 blocks.
     */
    private Vector3f findSpawnLocationNearPlayer(Player player) {
        Vector3f playerPos = player.getPosition();

        // Random angle and distance within spawn radius
        float angle = random.nextFloat() * (float)(Math.PI * 2);
        float distance = 24 + random.nextFloat() * (SPAWN_RADIUS - 24); // Spawn between 24-128 blocks

        int x = (int)(playerPos.x + Math.cos(angle) * distance);
        int z = (int)(playerPos.z + Math.sin(angle) * distance);

        // Find suitable Y position
        int y = findSuitableSpawnHeight(x, z);
        if (y > 0) {
            return new Vector3f(x + 0.5f, y, z + 0.5f);
        }

        return null;
    }

    /**
     * Finds a suitable spawn height at the given x,z coordinates.
     */
    private int findSuitableSpawnHeight(int x, int z) {
        for (int y = MAX_SPAWN_HEIGHT; y >= MIN_SPAWN_HEIGHT; y--) {
            BlockType groundBlock = world.getBlockAt(x, y, z);
            BlockType airBlock1 = world.getBlockAt(x, y + 1, z);
            BlockType airBlock2 = world.getBlockAt(x, y + 2, z);

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

        // Check for enough space (cow needs 2 blocks of height)
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

        return true;
    }

    /**
     * Counts passive mobs near a player (within spawn radius).
     */
    private int countPassiveMobsNearPlayer(Player player) {
        Vector3f playerPos = player.getPosition();
        List<Entity> cows = entityManager.getEntitiesInRange(playerPos, SPAWN_RADIUS);
        return (int) cows.stream().filter(e -> e.getType() == EntityType.COW).count();
    }

    /**
     * Checks if there are too many entities nearby.
     */
    private boolean hasNearbyEntities(Vector3f position, float radius, int maxCount) {
        int nearbyCount = entityManager.getEntitiesInRange(position, radius).size();
        return nearbyCount >= maxCount;
    }

    /**
     * Legacy method - redirects to initial chunk spawn
     * @deprecated Use initialChunkSpawn() instead
     */
    @Deprecated
    public void spawnEntitiesInChunk(Chunk chunk) {
        initialChunkSpawn(chunk);
    }

    /**
     * Spawns a specific number of cows near a center position (for testing/commands).
     */
    public void spawnCowHerd(Vector3f center, int count) {
        int spawned = 0;
        int maxAttempts = count * 5;

        for (int attempt = 0; attempt < maxAttempts && spawned < count; attempt++) {
            float offsetX = (random.nextFloat() - 0.5f) * 16.0f;
            float offsetZ = (random.nextFloat() - 0.5f) * 16.0f;

            Vector3f spawnPos = new Vector3f(
                center.x + offsetX,
                center.y,
                center.z + offsetZ
            );

            int groundY = findSuitableSpawnHeight((int)spawnPos.x, (int)spawnPos.z);
            if (groundY > 0) {
                spawnPos.y = groundY;

                if (isValidSpawnLocation(spawnPos, EntityType.COW)) {
                    String textureVariant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
                    Entity cow = entityManager.spawnCowWithVariant(spawnPos, textureVariant);
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
        return String.format("Total cows: %d | Next spawn cycle in: %d ticks",
            totalCows, PASSIVE_MOB_SPAWN_TICKS - tickCounter);
    }

    /**
     * Forces spawning of entities for testing (ignores normal spawn rules).
     */
    public Entity forceSpawnEntity(EntityType type, Vector3f position) {
        return entityManager.spawnEntity(type, position);
    }
}
