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

    private ServerLevel(long seed, World world, EntityManager entityManager,
                        EntitySpawner entitySpawner, long worldTimeTicks, SaveService saveService,
                        WorldData worldData, Vector3f spawn, PlayerData loadedPlayerData) {
        this.seed = seed;
        this.world = world;
        this.entityManager = entityManager;
        this.entitySpawner = entitySpawner;
        this.timeOfDay = new TimeOfDay(worldTimeTicks);
        this.saveService = saveService;
        this.worldData = worldData;
        this.spawn = spawn;
        this.loadedPlayerData = loadedPlayerData;
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

        // Spawn resolution below loads REAL chunks (issue #250 validation + spawn-area pre-gen),
        // so everything a chunk load consults must be wired BEFORE the first load: the save
        // service (or saved chunks silently regenerate from noise and later clobber the on-disk
        // ones) and this world's entity manager/spawner (or saved entities restore into the
        // Game/client singleton and chunk-gen animal spawns lose the spawner's placement guard).
        EntityManager entityManager = new EntityManager(world);
        EntitySpawner entitySpawner = new EntitySpawner(world, entityManager);
        world.setEntityManager(entityManager);
        world.setEntitySpawner(entitySpawner);
        save.initialize(worldData, null, world);

        PlayerData playerData = existing ? lr.getPlayerData() : null;

        Vector3f spawn;
        if (playerData != null && playerData.getPosition() != null) {
            // Loaded player restore: their saved position is authoritative — don't move it.
            spawn = new Vector3f(playerData.getPosition());
        } else if (worldData != null && worldData.hasExplicitSpawn() && worldData.getSpawnPosition() != null) {
            // Saved / user-chosen spawn: keep the column, but snap it onto the real (carved)
            // surface so a pre-issue-#250 saved pit doesn't drop the player. A chosen spawn is
            // deliberately never moved elsewhere.
            Vector3f saved = worldData.getSpawnPosition();
            int x = Math.round(saved.x);
            int z = Math.round(saved.z);
            int height = world.terrain().getFinalTerrainHeightAt(x, z);
            Vector3f candidate = new Vector3f(x, height + 1, z);
            pregenSpawnArea(world, candidate);
            spawn = resolveSurfaceSpawn(world, candidate);
        } else {
            // Locate a safe surface spawn, rejecting columns a ravine or sinkhole has carved
            // into a pit well below the pre-carve rim (issue #250).
            spawn = findSafeSurfaceSpawn(world);
        }
        world.setSpawnPosition(spawn);

        // The located spawn is a fresh-world choice; persist it so respawns stay put. The save
        // service was initialized before spawn resolution, so refresh its WorldData reference
        // (initialize doubles as a refresh — see registerLocalPlayer).
        if (worldData != null && !worldData.hasExplicitSpawn() && playerData == null) {
            worldData = new WorldData.Builder(worldData).spawnPosition(spawn).hasExplicitSpawn(true).build();
            save.initialize(worldData, null, world);
        }

        ServerLevel level = new ServerLevel(seed, world, entityManager, entitySpawner, timeTicks,
            save, worldData, spawn, playerData);
        save.setWorldTimeSource(level.timeOfDay);

        save.startAutoSave();

        System.out.println("[SERVER-LEVEL] Booted '" + worldName + "' (seed=" + seed
            + ", " + (existing ? "loaded" : "new") + "), spawn=" + spawn);
        return level;
    }

    /**
     * How many candidate columns {@link #findSafeSurfaceSpawn} will draw and validate before
     * giving up and snapping the last one to its floor.
     */
    private static final int MAX_SPAWN_ATTEMPTS = 256;

    /**
     * Locates a world spawn that is not a carved pit (issue #250). Draws noise-sampled
     * candidate columns from a single {@link SpawnLocator} (so its internal random advances
     * between draws), loads each candidate's chunk just long enough to validate its real
     * surface, and accepts the first column {@link SpawnLocator#acceptIfSafeSurface} okays —
     * i.e. one whose real surface matches the pre-carve rim rather than a ravine/sinkhole
     * floor. The winning column's spawn area is then pre-generated for collision/streaming. If
     * every draw lands in a pit, falls back to snapping the last candidate to its real floor so
     * the player never drops / takes fall damage.
     */
    private static Vector3f findSafeSurfaceSpawn(World world) {
        SpawnLocator locator = new SpawnLocator(world);
        Vector3f last = null;
        for (int attempt = 0; attempt < MAX_SPAWN_ATTEMPTS; attempt++) {
            last = locator.findSafeSurfaceSpawn();
            loadSpawnChunk(world, last);
            Vector3f accepted = SpawnLocator.acceptIfSafeSurface(world, last);
            if (accepted != null) {
                pregenSpawnArea(world, accepted);
                return accepted;
            }
        }
        pregenSpawnArea(world, last);
        return resolveSurfaceSpawn(world, last);
    }

    /** Loads {@code p}'s chunk (blocking until generated) so its real surface can be read. */
    private static void loadSpawnChunk(World world, Vector3f p) {
        int cx = Math.floorDiv((int) Math.floor(p.x), WorldConfiguration.CHUNK_SIZE);
        int cz = Math.floorDiv((int) Math.floor(p.z), WorldConfiguration.CHUNK_SIZE);
        world.getChunkAt(cx, cz);
        try {
            world.awaitPendingChunkLoads().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[SERVER-LEVEL] Spawn chunk load wait failed: " + e.getMessage());
        }
    }

    /**
     * Pre-generates a disc of chunks around {@code spawn} so the real (carved) terrain is
     * resident before anything collides with it.
     */
    private static void pregenSpawnArea(World world, Vector3f spawn) {
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

    /**
     * Snaps the candidate spawn's Y down onto the real top solid block of its column once the
     * chunk is resident. {@code getFinalTerrainHeightAt} samples pre-carve noise and ignores the
     * ravine/sinkhole masks, so a column cut open to the ravine floor would otherwise resolve to
     * rim height. Uses the same standable-column predicate as {@link EntitySpawner}. Falls back
     * to the candidate unchanged if no standable surface is found (e.g. open air to the floor).
     */
    private static Vector3f resolveSurfaceSpawn(World world, Vector3f candidate) {
        int x = (int) Math.floor(candidate.x);
        int z = (int) Math.floor(candidate.z);
        int standY = SpawnLocator.resolveStandingY(world, x, z, WorldConfiguration.WORLD_HEIGHT - 1);
        if (standY < 1) {
            return new Vector3f(candidate);
        }
        return new Vector3f(x + 0.5f, standY, z + 0.5f);
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
