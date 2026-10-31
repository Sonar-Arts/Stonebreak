package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntitySpawner;
import com.stonebreak.ui.LoadProgressTracker;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.spawn.SpawnLocator;
import org.joml.Vector3f;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** How often the pre-gen wait refreshes the loading screen's chunk count. */
    private static final long PREGEN_PROGRESS_POLL_MILLIS = 100L;

    private final long seed;
    private final World world;
    private final EntityManager entityManager;
    private final EntitySpawner entitySpawner;
    private final TimeOfDay timeOfDay;
    private final com.stonebreak.world.weather.WeatherState weather;
    private final SaveService saveService;
    private volatile WorldData worldData;
    private final Vector3f spawn;
    private final PlayerData loadedPlayerData;
    private final String playerLoadNotice;

    private ServerLevel(long seed, World world, EntityManager entityManager,
                        EntitySpawner entitySpawner, long worldTimeTicks, SaveService saveService,
                        WorldData worldData, Vector3f spawn, PlayerData loadedPlayerData,
                        String playerLoadNotice) {
        this.seed = seed;
        this.world = world;
        this.entityManager = entityManager;
        this.entitySpawner = entitySpawner;
        this.timeOfDay = new TimeOfDay(worldTimeTicks);
        this.weather = new com.stonebreak.world.weather.WeatherState(
                worldData.getWeatherSeconds(), worldData.getWeatherMode(), worldData.getWeatherZones());
        this.saveService = saveService;
        this.worldData = worldData;
        this.spawn = spawn;
        this.loadedPlayerData = loadedPlayerData;
        this.playerLoadNotice = playerLoadNotice;
    }

    /**
     * Load-or-generate the authoritative world for {@code worldName}, opening (and owning) its
     * {@link SaveService}. The seed comes from the saved {@link WorldData} when the world exists
     * on disk, otherwise {@code fallbackSeed} is used for a fresh world. Blocks the caller while
     * the world metadata loads and the spawn area pre-generates (run off the render thread).
     *
     * <p>An interrupt (a session shutdown racing the boot, issue #368) stops the boot at its next
     * wait with {@link InterruptedException}: an interrupted metadata load is never mistaken for
     * a new world, whose save would overwrite the real metadata. A boot that does not finish
     * closes what it opened (save service, headless world) before it throws.
     */
    public static ServerLevel createAndLoad(String worldName, long fallbackSeed) throws InterruptedException {
        String worldPath = com.stonebreak.world.save.WorldStorage.worldPath(worldName);
        SaveService save = new SaveService(worldPath);
        World world = null;
        EntityManager entityManager = null;
        try {
            SaveService.LoadResult lr = loadMetadata(save, worldName);
            throwIfInterrupted(); // the load may have finished before the interrupt was seen

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

            // A first visit (spawn search + pre-gen) loads very differently from a return to saved
            // player data, so each gets its own timing history for the loading screen's estimate.
            // Keyed on player data, not on the world existing: the terrain mapper writes world.json
            // before the first boot, so a brand-new world already "exists" here.
            boolean returning = existing && lr.getPlayerData() != null;
            String loadProfile = worldData.getGeneratorType() + "." + (returning ? "returning" : "new");
            LoadingScreen.report(t -> t.setProfile(loadProfile));

            world = World.createHeadless(new WorldConfiguration(), seed, worldData.getGeneratorType());
            if (worldData.getSpawnPosition() != null) {
                world.setSpawnPosition(worldData.getSpawnPosition());
            }

            // Spawn resolution below loads REAL chunks (issue #250 validation + spawn-area pre-gen),
            // so everything a chunk load consults must be wired BEFORE the first load: the save
            // service (or saved chunks silently regenerate from noise and later clobber the on-disk
            // ones) and this world's entity manager/spawner (or saved entities restore into the
            // Game/client singleton and chunk-gen animal spawns lose the spawner's placement guard).
            entityManager = new EntityManager(world);
            EntitySpawner entitySpawner = new EntitySpawner(world, entityManager);
            world.setEntityManager(entityManager);
            world.setEntitySpawner(entitySpawner);
            save.initialize(worldData, null, world);

            PlayerData playerData = existing ? lr.getPlayerData() : null;

            // The WORLD spawn: where new players appear and where everyone respawns. A returning player
            // still arrives where they saved (their restored PlayerData moves them there); that position
            // is never the world spawn, or every death respawned at the last save spot (#302).
            Vector3f spawn;
            if (worldData != null && worldData.hasExplicitSpawn() && worldData.getSpawnPosition() != null) {
                // Saved / user-chosen spawn: keep the column when its real (carved) surface is
                // usable. A column picked on the map inside a ravine, sinkhole, cave mouth or under
                // water would snap onto the pit floor / lakebed, so it is redirected to the nearest
                // usable surface column instead; only if none is in reach does it fall back to the
                // column's own floor.
                Vector3f saved = worldData.getSpawnPosition();
                // the column that holds the saved point (round would move a block-centred x.5 spawn
                // one block on every load)
                int x = (int) Math.floor(saved.x);
                int z = (int) Math.floor(saved.z);
                int height = world.terrain().getFinalTerrainHeightAt(x, z);
                Vector3f candidate = new Vector3f(x, height + 1, z);
                pregenSpawnArea(world, candidate);
                Vector3f surface = SpawnLocator.nearestSafeSurface(world, x, z, CHOSEN_SPAWN_SEARCH_RADIUS);
                if (surface == null) {
                    spawn = resolveSurfaceSpawn(world, candidate);
                } else {
                    spawn = surface;
                    if ((int) Math.floor(surface.x) != x || (int) Math.floor(surface.z) != z) {
                        System.out.println("[SERVER-LEVEL] Chosen spawn column (" + x + ", " + z
                            + ") is a pit or flooded; moved to nearest surface " + surface);
                    }
                }
            } else {
                // Locate a safe surface spawn, rejecting columns a ravine or sinkhole has carved
                // into a pit well below the pre-carve rim (issue #250).
                spawn = findSafeSurfaceSpawn(world);
            }
            world.setSpawnPosition(spawn);

            // The located spawn is a fresh-world choice (or, for a world saved before spawns were
            // persisted, a first one); persist it so respawns stay put. The save service was
            // initialized before spawn resolution, so refresh its WorldData reference (this is
            // still during creation, before any autosave — later refreshes must go through
            // SaveService.updateWorldData, never initialize).
            if (worldData != null && !worldData.hasExplicitSpawn()) {
                worldData = new WorldData.Builder(worldData).spawnPosition(spawn).hasExplicitSpawn(true).build();
                save.initialize(worldData, null, world);
            }

            ServerLevel level = new ServerLevel(seed, world, entityManager, entitySpawner, timeTicks,
                save, worldData, spawn, playerData, existing ? lr.getPlayerNotice() : null);
            save.setWorldTimeSource(level.timeOfDay);
            save.setWeatherSource(level.weather);
            entitySpawner.setNightSource(level.timeOfDay::isNight);
            world.setDaylightSource(level.timeOfDay::getAmbientLightLevel);
            world.setNightFogSource(() -> com.stonebreak.world.ShadeNight.fogStrength(level.timeOfDay));

            save.startAutoSave();

            System.out.println("[SERVER-LEVEL] Booted '" + worldName + "' (seed=" + seed
                + ", " + (existing ? "loaded" : "new") + "), spawn=" + spawn);
            return level;
        } catch (InterruptedException | RuntimeException | Error e) {
            abandonBoot(save, entityManager, world);
            throw e;
        }
    }

    /**
     * Reads the world metadata. A load that fails or times out is treated as a new world (as
     * before); an interrupt is not a failure — it propagates (issue #368).
     */
    private static SaveService.LoadResult loadMetadata(SaveService save, String worldName)
            throws InterruptedException {
        try {
            return save.loadWorld().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("[SERVER-LEVEL] World metadata load failed for '" + worldName
                + "': " + e.getMessage() + " — treating as a new world.");
            return null;
        }
    }

    /** Boot checkpoint: waits that already completed never see an interrupt, so look for one. */
    private static void throwIfInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("server boot interrupted");
        }
    }

    /**
     * Closes what an unfinished boot opened. The interrupt flag is cleared meanwhile, or the
     * save flush and the executor shutdowns would give up at once.
     */
    private static void abandonBoot(SaveService save, EntityManager entityManager, World world) {
        boolean interrupted = Thread.interrupted();
        try {
            save.close();
        } catch (RuntimeException e) {
            System.err.println("[SERVER-LEVEL] Save service close after a failed boot failed: " + e.getMessage());
        }
        try {
            if (entityManager != null) {
                entityManager.cleanup();
            }
            if (world != null) {
                world.cleanup();
            }
        } catch (RuntimeException e) {
            System.err.println("[SERVER-LEVEL] World cleanup after a failed boot failed: " + e.getMessage());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * How many candidate columns {@link #findSafeSurfaceSpawn} will draw and validate before
     * giving up and snapping the last one to its floor.
     */
    private static final int MAX_SPAWN_ATTEMPTS = 256;

    /**
     * How far (blocks) a chosen spawn may be redirected off a pit or flooded column. Stays inside
     * the {@link #PREGEN_RADIUS} chunk disc, the only terrain resident when the search runs.
     */
    private static final int CHOSEN_SPAWN_SEARCH_RADIUS = PREGEN_RADIUS * 16 - 1;

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
    private static Vector3f findSafeSurfaceSpawn(World world) throws InterruptedException {
        SpawnLocator locator = new SpawnLocator(world);
        Vector3f last = null;
        LoadingScreen.report(t -> t.beginPhase(LoadProgressTracker.Phase.SPAWN_SEARCH));
        for (int attempt = 0; attempt < MAX_SPAWN_ATTEMPTS; attempt++) {
            // The attempt count is open-ended, so the fraction halves the remaining gap per
            // rejected candidate: it keeps moving and never claims the search is finished.
            int tried = attempt;
            LoadingScreen.report(t -> {
                t.setPhaseFraction(1.0 - Math.pow(0.5, tried));
                t.setPhaseDetail(tried == 0 ? null : "Candidate " + (tried + 1));
            });
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
    private static void loadSpawnChunk(World world, Vector3f p) throws InterruptedException {
        throwIfInterrupted();
        int cx = Math.floorDiv((int) Math.floor(p.x), WorldConfiguration.CHUNK_SIZE);
        int cz = Math.floorDiv((int) Math.floor(p.z), WorldConfiguration.CHUNK_SIZE);
        world.getChunkAt(cx, cz);
        try {
            world.awaitPendingChunkLoads().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("[SERVER-LEVEL] Spawn chunk load wait failed: " + e.getMessage());
        }
        world.installCompletedChunkLoads();
    }

    /**
     * Pre-generates a disc of chunks around {@code spawn} so the real (carved) terrain is
     * resident before anything collides with it.
     */
    private static void pregenSpawnArea(World world, Vector3f spawn) throws InterruptedException {
        throwIfInterrupted();
        LoadingScreen.report(t -> t.beginPhase(LoadProgressTracker.Phase.PREGEN));
        int pcx = (int) Math.floor(spawn.x / 16.0);
        int pcz = (int) Math.floor(spawn.z / 16.0);
        for (int dx = -PREGEN_RADIUS; dx <= PREGEN_RADIUS; dx++) {
            for (int dz = -PREGEN_RADIUS; dz <= PREGEN_RADIUS; dz++) {
                world.getChunkAt(pcx + dx, pcz + dz);
            }
        }
        CompletableFuture<Void> pending = world.awaitPendingChunkLoads();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (true) {
                reportPregenProgress(world, pcx, pcz);
                try {
                    pending.get(PREGEN_PROGRESS_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException stillLoading) {
                    if (System.nanoTime() >= deadline) {
                        throw stillLoading;
                    }
                }
            }
            reportPregenProgress(world, pcx, pcz);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("[SERVER-LEVEL] Spawn-area pre-gen wait failed: " + e.getMessage());
        }
    }

    private static void reportPregenProgress(World world, int pcx, int pcz) {
        world.installCompletedChunkLoads(); // level build owns the world until the tick starts
        int side = 2 * PREGEN_RADIUS + 1;
        int total = side * side;
        int loaded = 0;
        for (int dx = -PREGEN_RADIUS; dx <= PREGEN_RADIUS; dx++) {
            for (int dz = -PREGEN_RADIUS; dz <= PREGEN_RADIUS; dz++) {
                if (world.getChunkIfLoaded(pcx + dx, pcz + dz) != null) {
                    loaded++;
                }
            }
        }
        int done = loaded;
        LoadingScreen.report(t -> {
            t.setPhaseFraction((double) done / total);
            t.setPhaseDetail("Chunks " + done + "/" + total);
        });
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
    /** The authoritative weather clock and mode every client mirrors. */
    public com.stonebreak.world.weather.WeatherState weather() { return weather; }
    public SaveService saveService() { return saveService; }
    public WorldData worldData() { return worldData; }
    public Vector3f spawn() { return new Vector3f(spawn); }

    /** The world's persisted cheats flag (issue #318) — the authority every client mirrors. */
    public boolean cheatsEnabled() { return worldData.isCheatsEnabled(); }

    /**
     * Set the world's cheats flag. Updates this level's {@link WorldData} and the save
     * service's running copy (which carries the accumulated play time) so the flag persists
     * with the next save.
     */
    public void setCheatsEnabled(boolean enabled) {
        worldData = worldData.withCheatsEnabled(enabled);
        if (saveService != null) {
            saveService.updateWorldData(d -> d.withCheatsEnabled(enabled));
        }
    }

    /** Whether the world lets players hurt each other (issue #343, persisted; off by default). */
    public boolean pvpEnabled() { return worldData.isPvpEnabled(); }

    /** Set the world's PvP rule; persists with the next save, like {@link #setCheatsEnabled}. */
    public void setPvpEnabled(boolean enabled) {
        worldData = worldData.withPvpEnabled(enabled);
        if (saveService != null) {
            saveService.updateWorldData(d -> d.withPvpEnabled(enabled));
        }
    }

    /** Loaded player data for an integrated server, or null for a fresh world / dedicated boot. */
    public PlayerData loadedPlayerData() { return loadedPlayerData; }

    /** Why the saved player data could not be restored (it was set aside, #358), else null. */
    public String playerLoadNotice() { return playerLoadNotice; }

    /**
     * Register the in-process local player so the save service persists its inventory/position.
     * Rebinds the player only: the service's {@link WorldData} — with the play time accumulated
     * since load and runtime edits — is never replaced here. Same-JVM only.
     */
    public void registerLocalPlayer(com.stonebreak.player.Player player) {
        registerLocalPlayer(player, Runnable::run);
    }

    /**
     * {@link #registerLocalPlayer(com.stonebreak.player.Player)} with the executor of the thread
     * that mutates the player (the client main thread), so the autosave snapshots it there (#356).
     */
    public void registerLocalPlayer(com.stonebreak.player.Player player, java.util.concurrent.Executor owner) {
        if (saveService != null) {
            saveService.bindPlayer(player, owner);
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
        weather.advance(deltaTime);
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
