package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntitySpawner;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.spawn.SpawnLocator;
import org.joml.Vector3f;

import java.util.concurrent.TimeUnit;

/**
 * The authoritative, headless server world — the Minecraft {@code ServerLevel} analog. Owns
 * its own {@link World} (built with {@code testMode=true}, so no mesh pipeline / OpenGL),
 * {@link EntityManager}, {@link EntitySpawner}, {@link TimeOfDay}, and the {@link SaveService}
 * for {@code worlds/<name>/}. It is the single source of truth for blocks, entities, time, and
 * persistence; each client renders a SEPARATE world.
 *
 * <p>Because the headless world has no {@code chunkManager}, the server drives chunk loading
 * itself by calling {@code world.getChunkAt(...)} from its view-distance streaming loop
 * (see {@code ServerChunkHandler}).
 *
 * <p><b>Persistence ownership:</b> only ONE world may persist to {@code worlds/<name>/}. Once
 * this level is booted (the two-world cutover), it owns the {@link SaveService} and the
 * co-located {@code Game} world must NOT have one (its render world is created via
 * {@code World.createClientView}, which carries no save service).
 *
 * <p><b>Player data:</b> the server boots before any client player exists, so the
 * {@link SaveService} starts with {@code player == null} (chunks + world metadata still
 * persist). For an integrated (singleplayer/host) server the loaded {@link PlayerData} is
 * exposed via {@link #loadedPlayerData()} so the in-process local player can be restored and
 * registered with the save service ({@link #registerLocalPlayer}).
 */
public final class ServerLevel {

    /** Spawn-area chunk radius to pre-generate so the server has terrain to stream/collide. */
    private static final int PREGEN_RADIUS = 4;

    private final long seed;
    private final World world;
    private final EntityManager entityManager;
    private final EntitySpawner entitySpawner;
    private final TimeOfDay timeOfDay;
    private final SaveService saveService;
    private volatile WorldData worldData;
    private final Vector3f spawn;
    private final PlayerData loadedPlayerData;

    private ServerLevel(long seed, World world, long worldTimeTicks, SaveService saveService,
                        WorldData worldData, Vector3f spawn, PlayerData loadedPlayerData) {
        this.seed = seed;
        this.world = world;
        this.entityManager = new EntityManager(world);
        this.entitySpawner = new EntitySpawner(world, entityManager);
        this.timeOfDay = new TimeOfDay(worldTimeTicks);
        this.saveService = saveService;
        this.worldData = worldData;
        this.spawn = spawn;
        this.loadedPlayerData = loadedPlayerData;
        // Route initial chunk-gen mob spawning AND saved-chunk entity loading into THIS world's
        // manager/spawner (not the Game/client singleton, which during boot is the wrong one).
        world.setEntitySpawner(entitySpawner);
        world.setEntityManager(entityManager);
    }

    /**
     * Load-or-generate the authoritative world for {@code worldName}, opening (and owning) its
     * {@link SaveService}. The seed comes from the saved {@link WorldData} when the world exists
     * on disk, otherwise {@code fallbackSeed} is used for a fresh world. Blocks the caller while
     * the world metadata loads and the spawn area pre-generates (run off the render thread).
     */
    public static ServerLevel createAndLoad(String worldName, long fallbackSeed) {
        String worldPath = com.stonebreak.world.save.WorldStorage.worldPath(worldName);
        SaveService save = new SaveService(worldPath);

        SaveService.LoadResult lr = null;
        try {
            lr = save.loadWorld().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[SERVER-LEVEL] World metadata load failed for '" + worldName
                + "': " + e.getMessage() + " — treating as a new world.");
        }

        boolean existing = lr != null && lr.isSuccess() && lr.getWorldData() != null;
        WorldData worldData;
        long seed;
        long timeTicks;
        if (existing) {
            worldData = lr.getWorldData();
            seed = worldData.getSeed();
            timeTicks = worldData.getWorldTimeTicks();
        } else {
            seed = fallbackSeed;
            worldData = WorldData.builder().seed(seed).worldName(worldName).build();
            timeTicks = TimeOfDay.NOON;
        }

        World world = World.createHeadless(new WorldConfiguration(), seed);
        if (worldData.getSpawnPosition() != null) {
            world.setSpawnPosition(worldData.getSpawnPosition());
        }

        PlayerData playerData = existing ? lr.getPlayerData() : null;

        // Resolve the authoritative spawn: saved player position > explicit world spawn >
        // located safe surface spawn. getFinalTerrainHeightAt samples noise without loading.
        Vector3f spawn = resolveSpawn(world, worldData, playerData);
        world.setSpawnPosition(spawn);

        // Persist the chosen spawn back into the world data so it is stable across reloads.
        if (!worldData.hasExplicitSpawn() && playerData == null) {
            worldData = new WorldData.Builder(worldData).spawnPosition(spawn).hasExplicitSpawn(true).build();
        }

        // Save service is bound to the (headless) world; player is registered later (slice 5).
        save.initialize(worldData, null, world);

        ServerLevel level = new ServerLevel(seed, world, timeTicks, save, worldData, spawn, playerData);
        save.setWorldTimeSource(level.timeOfDay);

        level.pregenSpawnArea();
        save.startAutoSave();

        System.out.println("[SERVER-LEVEL] Booted '" + worldName + "' (seed=" + seed
            + ", " + (existing ? "loaded" : "new") + "), spawn=" + spawn);
        return level;
    }

    private static Vector3f resolveSpawn(World world, WorldData worldData, PlayerData playerData) {
        if (playerData != null && playerData.getPosition() != null) {
            return new Vector3f(playerData.getPosition());
        }
        if (worldData != null && worldData.hasExplicitSpawn() && worldData.getSpawnPosition() != null) {
            Vector3f saved = worldData.getSpawnPosition();
            int x = Math.round(saved.x);
            int z = Math.round(saved.z);
            int height = world.getFinalTerrainHeightAt(x, z);
            return new Vector3f(x, height + 1, z);
        }
        return new SpawnLocator(world).findSafeSurfaceSpawn();
    }

    private void pregenSpawnArea() {
        int pcx = (int) Math.floor(spawn.x / 16.0);
        int pcz = (int) Math.floor(spawn.z / 16.0);
        for (int dx = -PREGEN_RADIUS; dx <= PREGEN_RADIUS; dx++) {
            for (int dz = -PREGEN_RADIUS; dz <= PREGEN_RADIUS; dz++) {
                world.getChunkAt(pcx + dx, pcz + dz);
            }
        }
        try {
            world.awaitPendingChunkLoads().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[SERVER-LEVEL] Spawn-area pre-gen wait failed: " + e.getMessage());
        }
    }

    public long seed() { return seed; }
    public World world() { return world; }
    public EntityManager entityManager() { return entityManager; }
    public EntitySpawner entitySpawner() { return entitySpawner; }
    public TimeOfDay timeOfDay() { return timeOfDay; }
    public SaveService saveService() { return saveService; }
    public WorldData worldData() { return worldData; }
    public Vector3f spawn() { return new Vector3f(spawn); }

    /** Loaded player data for an integrated server, or null for a fresh world / dedicated boot. */
    public PlayerData loadedPlayerData() { return loadedPlayerData; }

    /**
     * Register the in-process local player so the save service persists its inventory/position.
     * Called from the cutover once the Local client has created the player. Same-JVM only.
     */
    public void registerLocalPlayer(com.stonebreak.player.Player player) {
        if (saveService != null) {
            saveService.initialize(worldData, player, world);
        }
    }

    /**
     * Authoritative simulation step, run on the server tick (fixed 20 Hz). Advances world
     * sim (water/furnace/features), entity AI + physics, mob spawning, and time — all on the
     * headless server world, never touching GL.
     */
    public void tick(float deltaTime) {
        world.updateSimulation(deltaTime);
        entityManager.update(deltaTime);
        entitySpawner.update(deltaTime);
        timeOfDay.update(deltaTime);
    }

    public void cleanup() {
        if (saveService != null) {
            try {
                saveService.close();
            } catch (Exception e) {
                System.err.println("[SERVER-LEVEL] Save service close failed: " + e.getMessage());
            }
        }
        entityManager.cleanup();
        world.cleanup();
    }
}
