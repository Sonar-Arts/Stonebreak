package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Server-side passive mob spawner. Authoritative under the two-world model — only the
 * headless server's spawner is ticked (see {@code ServerLevel.tick}); the client render
 * world never owns a live spawner.
 *
 * <p><b>Initial spawning.</b> Triggered once per chunk via {@link #initialChunkSpawn},
 * which the world driver calls only after a chunk's terrain AND features are populated.
 * Reads blocks directly from the chunk (so it works before the chunk is registered with
 * the chunk store) and gates each placement on {@link #isValidSpawnLocation}.
 *
 * <p><b>Continuous spawning.</b> Every {@link #PASSIVE_SPAWN_INTERVAL_SECONDS} seconds the spawner
 * picks targets near each connected player and attempts placements there. A target is
 * rejected outright unless its chunk is resident in the chunk store AND has features
 * populated — this is what prevents mobs from being placed onto unloaded/half-baked
 * chunks where they'd fall through the world or get stuck in soon-to-arrive features.
 *
 * <p><b>Despawning.</b> A mob despawns only when farther than {@link #DESPAWN_RADIUS}
 * from <i>every</i> connected player (so a mob near any one player survives).
 *
 * <p>Public API is preserved: {@link #initialChunkSpawn}, {@link #update},
 * {@link #forceSpawnEntity}, {@link #spawnCowHerd}, {@link #getSpawnStats}.
 */
public class EntitySpawner {

    // ─── Tuning ───────────────────────────────────────────────────────────────
    /**
     * Seconds of sim time between continuous spawn cycles — Minecraft's passive cadence.
     * Time-based (not call-counted) so the cadence holds whether update() is driven by the
     * server's fixed 20 Hz tick or a legacy per-frame caller.
     */
    private static final float PASSIVE_SPAWN_INTERVAL_SECONDS = 20.0f;
    /** Seconds between despawn sweeps; no need to scan every update. */
    private static final float DESPAWN_CHECK_INTERVAL_SECONDS = 1.0f;
    private static final int SPAWN_RADIUS = 128;
    private static final int DESPAWN_RADIUS = 128;

    /**
     * Single per-chunk roll for initial spawning. On success ONE random passive type spawns
     * one herd (Minecraft-like). Rolling per TYPE instead (the old behavior) tripled the
     * density (~1.35 mobs/chunk) and flooded the world as chunks generated.
     */
    private static final float INITIAL_SPAWN_CHANCE = 0.10f;
    private static final int MIN_INITIAL_HERD = 2;
    private static final int MAX_INITIAL_HERD = 4;

    /** Shared passive-mob cap per player; bounds total continuous spawns. */
    private static final int MAX_PASSIVE_MOBS_PER_PLAYER = 10;
    private static final int SPAWN_ATTEMPTS_PER_CYCLE = 3;

    private static final int MIN_SPAWN_HEIGHT = 60;
    private static final int MAX_SPAWN_HEIGHT = 120;

    /** Continuous spawns must land at least this far from the target player. */
    private static final int MIN_SPAWN_DISTANCE = 24;

    /** Passive types rolled independently so chickens spawn as often as cows. */
    private static final EntityType[] PASSIVE_SPAWN_TYPES = {EntityType.COW, EntityType.CHICKEN, EntityType.SHEEP};

    /** Cow texture variants — delegates to EntityType to kill duplication. */
    private static final String[] COW_TEXTURE_VARIANTS = EntityType.COW.getTextureVariants();

    // ─── Collaborators ────────────────────────────────────────────────────────
    private final World world;
    private final EntityManager entityManager;
    private final Random random = new Random();

    /**
     * Supplier of player positions to use for continuous spawning and despawning.
     * Injected by the world owner so this class doesn't reach into the network layer
     * (which would invert the dependency direction — network already imports mobs).
     * Defaults to "the single local player from {@code Game}" for tests / dev runs.
     */
    private volatile Supplier<List<Vector3f>> playerPositionSource = EntitySpawner::defaultLocalPlayer;

    private float spawnTimer = 0f;
    private float despawnTimer = 0f;

    public EntitySpawner(World world, EntityManager entityManager) {
        this.world = world;
        this.entityManager = entityManager;
    }

    /**
     * Replaces the player-position source. Server-side wiring (the integrated server)
     * supplies one that enumerates every connected player.
     */
    public void setPlayerPositionSource(Supplier<List<Vector3f>> source) {
        this.playerPositionSource = (source != null) ? source : EntitySpawner::defaultLocalPlayer;
    }

    private static List<Vector3f> defaultLocalPlayer() {
        Player local = Game.getPlayer();
        return local != null ? List.of(new Vector3f(local.getPosition())) : Collections.emptyList();
    }

    // ─── Tick loop ────────────────────────────────────────────────────────────

    /**
     * Per-tick update. Only the authoritative server world ever calls this (the client
     * render world's spawner is never ticked), so no host/client guard is needed here.
     */
    public void update(float deltaTime) {
        spawnTimer += deltaTime;
        if (spawnTimer >= PASSIVE_SPAWN_INTERVAL_SECONDS) {
            spawnTimer = 0f;
            performContinuousSpawning();
        }
        despawnTimer += deltaTime;
        if (despawnTimer >= DESPAWN_CHECK_INTERVAL_SECONDS) {
            despawnTimer = 0f;
            checkDespawning();
        }
    }

    // ─── Initial spawning (called once per chunk by the world driver) ─────────

    /**
     * Rolls initial mob spawns for a chunk. Should be invoked AFTER the chunk's
     * features are populated (the world chunk store handles this).
     */
    public void initialChunkSpawn(Chunk chunk) {
        // ONE roll per chunk; on success pick ONE passive type and spawn a single herd.
        if (random.nextFloat() > INITIAL_SPAWN_CHANCE) {
            return;
        }
        EntityType type = PASSIVE_SPAWN_TYPES[random.nextInt(PASSIVE_SPAWN_TYPES.length)];
        spawnInitialHerd(chunk, type);
    }

    private void spawnInitialHerd(Chunk chunk, EntityType type) {
        int target = MIN_INITIAL_HERD + random.nextInt(MAX_INITIAL_HERD - MIN_INITIAL_HERD + 1);
        int spawned = 0;
        for (int attempt = 0; attempt < 20 && spawned < target; attempt++) {
            Vector3f pos = findSpawnInChunk(chunk);
            if (pos != null && isValidSpawnLocation(pos, type) && spawnPassiveMob(type, pos) != null) {
                spawned++;
            }
        }
    }

    // ─── Continuous spawning ──────────────────────────────────────────────────

    private void performContinuousSpawning() {
        List<Vector3f> players = collectPlayerPositions();
        if (players.isEmpty()) return;

        for (Vector3f playerPos : players) {
            for (EntityType type : PASSIVE_SPAWN_TYPES) {
                spawnContinuousNear(playerPos, type);
            }
        }
    }

    private void spawnContinuousNear(Vector3f playerPos, EntityType type) {
        if (countPassiveMobsNear(playerPos) >= MAX_PASSIVE_MOBS_PER_PLAYER) {
            return;
        }
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS_PER_CYCLE; attempt++) {
            if (countPassiveMobsNear(playerPos) >= MAX_PASSIVE_MOBS_PER_PLAYER) break;
            Vector3f pos = findSpawnNear(playerPos);
            if (pos != null && isValidSpawnLocation(pos, type)) {
                spawnPassiveMob(type, pos);
            }
        }
    }

    private int countPassiveMobsNear(Vector3f playerPos) {
        int count = 0;
        for (Entity e : entityManager.getEntitiesInRange(playerPos, SPAWN_RADIUS)) {
            EntityType t = e.getType();
            if (t == EntityType.COW || t == EntityType.CHICKEN || t == EntityType.SHEEP) count++;
        }
        return count;
    }

    // ─── Despawning ───────────────────────────────────────────────────────────

    private void checkDespawning() {
        List<Vector3f> players = collectPlayerPositions();
        if (players.isEmpty()) return;

        for (EntityType type : PASSIVE_SPAWN_TYPES) {
            for (Entity mob : entityManager.getEntitiesByType(type)) {
                if (distanceToNearest(mob.getPosition(), players) > DESPAWN_RADIUS) {
                    entityManager.removeEntity(mob);
                }
            }
        }
    }

    private static float distanceToNearest(Vector3f point, List<Vector3f> players) {
        float best = Float.POSITIVE_INFINITY;
        for (Vector3f p : players) {
            float d = point.distance(p);
            if (d < best) best = d;
        }
        return best;
    }

    // ─── Player enumeration (multi-player aware via injected source) ──────────

    private List<Vector3f> collectPlayerPositions() {
        List<Vector3f> result = playerPositionSource.get();
        return result != null ? result : Collections.emptyList();
    }

    // ─── Spawn-location finders ───────────────────────────────────────────────

    /**
     * Picks a random (x,z) inside the chunk and the first valid Y above ground.
     * Reads blocks directly from the chunk so it works before the chunk is in the
     * chunk store (initial spawn runs on a worker thread during generation).
     */
    private Vector3f findSpawnInChunk(Chunk chunk) {
        int worldX = chunk.getChunkX() * 16;
        int worldZ = chunk.getChunkZ() * 16;
        int lx = random.nextInt(16);
        int lz = random.nextInt(16);

        for (int y = MAX_SPAWN_HEIGHT; y >= MIN_SPAWN_HEIGHT; y--) {
            if (isStandableColumn(chunk.getBlock(lx, y, lz),
                                  chunk.getBlock(lx, y + 1, lz),
                                  chunk.getBlock(lx, y + 2, lz))) {
                return new Vector3f(worldX + lx + 0.5f, y + 1, worldZ + lz + 0.5f);
            }
        }
        return null;
    }

    /**
     * Picks a random point on a ring around {@code playerPos} and finds a valid Y.
     * Rejects outright when the target chunk isn't fully ready ({@link #isChunkReadyForSpawn}),
     * which is what prevents mobs being placed on unloaded chunks (where they'd fall
     * through the world) or on terrain-only chunks whose neighbors are still AIR.
     */
    private Vector3f findSpawnNear(Vector3f playerPos) {
        float angle = random.nextFloat() * (float) (Math.PI * 2);
        float distance = MIN_SPAWN_DISTANCE
                + random.nextFloat() * (SPAWN_RADIUS - MIN_SPAWN_DISTANCE);
        int x = (int) (playerPos.x + Math.cos(angle) * distance);
        int z = (int) (playerPos.z + Math.sin(angle) * distance);

        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        if (!isChunkReadyForSpawn(chunkX, chunkZ)) {
            return null;
        }

        for (int y = MAX_SPAWN_HEIGHT; y >= MIN_SPAWN_HEIGHT; y--) {
            if (isStandableColumn(world.getBlockAt(x, y, z),
                                  world.getBlockAt(x, y + 1, z),
                                  world.getBlockAt(x, y + 2, z))) {
                return new Vector3f(x + 0.5f, y + 1, z + 0.5f);
            }
        }
        return null;
    }

    /**
     * A chunk is ready for a spawn iff it's resident in the store AND its features
     * have been populated. Both conditions together mean the chunk is part of the
     * server's working set and its blocks are final.
     */
    private boolean isChunkReadyForSpawn(int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkIfLoaded(chunkX, chunkZ);
        return chunk != null && chunk.areFeaturesPopulated();
    }

    private static boolean isStandableColumn(BlockType ground, BlockType head, BlockType above) {
        if (ground == null || ground == BlockType.AIR || ground == BlockType.WATER) return false;
        return (head == null || head == BlockType.AIR) && (above == null || above == BlockType.AIR);
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    /**
     * Validates a candidate spawn position for the given type. Public so tools and
     * commands can reuse the same gate.
     */
    public boolean isValidSpawnLocation(Vector3f position, EntityType type) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y);
        int z = (int) Math.floor(position.z);
        return switch (type) {
            case COW, CHICKEN, SHEEP -> isValidGroundSpawn(x, y, z, position);
            default -> false;
        };
    }

    private boolean isValidGroundSpawn(int x, int y, int z, Vector3f position) {
        BlockType ground = world.getBlockAt(x, y - 1, z);
        if (ground == null || ground == BlockType.AIR || ground == BlockType.WATER) return false;

        BlockType head = world.getBlockAt(x, y, z);
        BlockType above = world.getBlockAt(x, y + 1, z);
        if (head != null && head != BlockType.AIR) return false;
        if (above != null && above != BlockType.AIR) return false;

        return !isOvercrowded(position);
    }

    private boolean isOvercrowded(Vector3f position) {
        return entityManager.getEntitiesInRange(position, 5.0f).size() >= 3;
    }

    // ─── Spawning primitives ──────────────────────────────────────────────────

    private Entity spawnPassiveMob(EntityType type, Vector3f position) {
        if (type == EntityType.COW) {
            String variant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
            return entityManager.spawnCowWithVariant(position, variant);
        }
        return entityManager.spawnEntity(type, position);
    }

    // ─── Backwards-compatible public helpers ──────────────────────────────────

    /** Spawns a herd near {@code center} for commands/testing — bypasses the spawn cycle. */
    public void spawnCowHerd(Vector3f center, int count) {
        int spawned = 0;
        for (int attempt = 0; attempt < count * 5 && spawned < count; attempt++) {
            float ox = (random.nextFloat() - 0.5f) * 16.0f;
            float oz = (random.nextFloat() - 0.5f) * 16.0f;
            int x = (int) (center.x + ox);
            int z = (int) (center.z + oz);

            int y = findSurfaceY(x, z);
            if (y <= 0) continue;

            Vector3f pos = new Vector3f(x + 0.5f, y, z + 0.5f);
            if (!isValidSpawnLocation(pos, EntityType.COW)) continue;

            String variant = COW_TEXTURE_VARIANTS[random.nextInt(COW_TEXTURE_VARIANTS.length)];
            if (entityManager.spawnCowWithVariant(pos, variant) != null) {
                spawned++;
            }
        }
    }

    private int findSurfaceY(int x, int z) {
        for (int y = MAX_SPAWN_HEIGHT; y >= MIN_SPAWN_HEIGHT; y--) {
            if (isStandableColumn(world.getBlockAt(x, y, z),
                                  world.getBlockAt(x, y + 1, z),
                                  world.getBlockAt(x, y + 2, z))) {
                return y + 1;
            }
        }
        return -1;
    }

    /** Test/command hook — unconditional spawn, no validation. */
    public Entity forceSpawnEntity(EntityType type, Vector3f position) {
        return entityManager.spawnEntity(type, position);
    }

    public String getSpawnStats() {
        int cows = entityManager.getEntitiesByType(EntityType.COW).size();
        int chickens = entityManager.getEntitiesByType(EntityType.CHICKEN).size();
        int sheep = entityManager.getEntitiesByType(EntityType.SHEEP).size();
        return String.format("Cows: %d | Chickens: %d | Sheep: %d | Next cycle: %.1fs",
                cows, chickens, sheep, PASSIVE_SPAWN_INTERVAL_SECONDS - spawnTimer);
    }

    /** @deprecated use {@link #initialChunkSpawn}. */
    @Deprecated
    public void spawnEntitiesInChunk(Chunk chunk) {
        initialChunkSpawn(chunk);
    }
}
