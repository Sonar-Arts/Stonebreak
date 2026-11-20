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
import org.joml.Vector3d;
import org.joml.Vector3dc;
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
    private final Vector3d spawn;
    private final PlayerData loadedPlayerData;
    private final String loadNotice;
    /** Ephemeral (main menu showcase) level: never saved, its local player is an observer camera. */
    private boolean ephemeral;
    /** Where the client's FastLOD cache lives for this world (its save folder), or null for none. */
    private String lodCachePath;

    private ServerLevel(long seed, World world, EntityManager entityManager,
                        EntitySpawner entitySpawner, long worldTimeTicks, SaveService saveService,
                        WorldData worldData, Vector3d spawn, PlayerData loadedPlayerData,
                        String loadNotice) {
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
        this.loadNotice = loadNotice;
    }

    /**
     * An ephemeral world for the main menu's live backdrop: generated from {@code seed}, never saved
     * (no {@link SaveService} — chunks that unload are simply regenerated), its spawn the camera
     * anchor as given (no spawn search, no pre-gen: the server streams around the camera the moment
     * it reports in). The local player of such a level is an observer: mobs neither see nor hurt it.
     *
     * @param lodCacheDir where the client keeps its FastLOD cache for this seed (survives launches,
     *                    so distant terrain is instant the second time), or null for none
     */
    public static ServerLevel createEphemeral(long seed, Vector3f anchor, long timeTicks,
                                              java.nio.file.Path lodCacheDir) {
        WorldData worldData = WorldData.builder().seed(seed)
                .worldName(com.stonebreak.network.MultiplayerSession.SHOWCASE_WORLD_NAME)
                .spawnPosition(new Vector3f(anchor)).hasExplicitSpawn(true).build();
        World world = World.createHeadless(new WorldConfiguration(), seed, worldData.getGeneratorType(),
                worldData.getTerrainScale(), worldData.getClimateLayout(), worldData.getHeightCurve(),
                worldData.getWorldFeatures(),
                // the menu's flyover (resources/menu/showcase.json) was scouted on Standard shape 1 terrain: keep it
                com.stonebreak.world.generation.StandardShapeVersion.V1,
                null, false); // ephemeral: no cube store
        world.setSpawnPosition(new Vector3f(anchor));
        EntityManager entityManager = new EntityManager(world);
        EntitySpawner entitySpawner = new EntitySpawner(world, entityManager);
        world.setEntityManager(entityManager);
        world.setEntitySpawner(entitySpawner);
        ServerLevel level = new ServerLevel(seed, world, entityManager, entitySpawner, timeTicks,
                null, worldData, new Vector3d(anchor), null, null);
        level.ephemeral = true;
        level.lodCachePath = lodCacheDir != null ? lodCacheDir.toString() : null;
        entitySpawner.setNightSource(level.timeOfDay::isNight);
        System.out.println("[SERVER-LEVEL] Booted ephemeral showcase world (seed=" + seed + "), anchor=" + anchor);
        return level;
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

            if (!lr.isSuccess() && !lr.isWorldMissing()) {
                // The world is there but could not be opened safely: booting it as new would
                // overwrite it on the first save. SaveService left every file in place.
                throw new IllegalStateException("World '" + worldName + "' was not opened: " + lr.getError());
            }
            boolean existing = lr.isSuccess() && lr.getWorldData() != null;
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

            // A DaedalusTGM-Exp world runs the terrain model it was made with (review-python HIGH-1): chosen before the
            // world's generator first asks TGMPipe for tiles. Not installed = the default model + a chat warning.
            String modelNotice = null;
            if (worldData.getGeneratorType() == com.stonebreak.world.generation.TerrainGeneratorType.DIFFUSION) {
                modelNotice = com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe.getInstance()
                    .useWorldModel(worldData.getTerrainModel()).notice();
            }

            world = World.createHeadless(new WorldConfiguration(), seed, worldData.getGeneratorType(),
                    worldData.getTerrainScale(), worldData.getClimateLayout(), worldData.getHeightCurve(),
                worldData.getWorldFeatures(), worldData.getStandardShape(),
                    java.nio.file.Path.of(worldPath), false);
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
            Vector3d spawn;
            if (worldData != null && worldData.hasExplicitSpawn() && worldData.getSpawnPosition() != null) {
                // Saved / user-chosen spawn: keep the column when its real (carved) surface is
                // usable. A column picked on the map inside a ravine, sinkhole, cave mouth or under
                // water would snap onto the pit floor / lakebed, so it is redirected to the nearest
                // usable surface column instead; only if none is in reach does it fall back to the
                // column's own floor.
                org.joml.Vector3d saved = worldData.getSpawnPosition();
                // the column that holds the saved point (round would move a block-centred x.5 spawn
                // one block on every load)
                int x = (int) Math.floor(saved.x);
                int z = (int) Math.floor(saved.z);
                int height = world.terrain().getFinalTerrainHeightAt(x, z);
                Vector3d candidate = new Vector3d(x, height + 1, z);
                pregenSpawnArea(world, candidate);
                Vector3d surface = SpawnLocator.nearestSafeSurface(world, x, z, CHOSEN_SPAWN_SEARCH_RADIUS);
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
                save, worldData, spawn, playerData, withNotice(existing ? combinedNotice(lr) : null, modelNotice));
            level.lodCachePath = worldPath;
            save.setWorldTimeSource(level.timeOfDay);
            save.setWeatherSource(level.weather);
            entitySpawner.setNightSource(level.timeOfDay::isNight);

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
     * Reads the world metadata. Only {@link SaveService.LoadResult#isWorldMissing()} may boot a
     * new world: a load that fails or times out is NOT one — treating it as new used to let the
     * first save overwrite the real metadata — so it stops the boot. An interrupt propagates
     * (issue #368).
     */
    private static SaveService.LoadResult loadMetadata(SaveService save, String worldName)
            throws InterruptedException {
        try {
            return save.loadWorld().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("World '" + worldName + "' was not opened: its metadata load failed ("
                + e + ")", e);
        }
    }

    /** The load notices for the host's chat (world first: it explains a reset clock), else null. */
    private static String combinedNotice(SaveService.LoadResult lr) {
        String world = lr.getWorldNotice();
        String player = lr.getPlayerNotice();
        if (world == null) {
            return player;
        }
        return player == null ? world : world + " " + player;
    }

    /** {@code notice} followed by {@code extra} (either may be null). */
    private static String withNotice(String notice, String extra) {
        if (extra == null) {
            return notice;
        }
        return notice == null ? extra : notice + " " + extra;
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
    private static Vector3d findSafeSurfaceSpawn(World world) throws InterruptedException {
        SpawnLocator locator = new SpawnLocator(world);
        Vector3d last = null;
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
            Vector3d accepted = SpawnLocator.acceptIfSafeSurface(world, last);
            if (accepted != null) {
                pregenSpawnArea(world, accepted);
                return accepted;
            }
        }
        pregenSpawnArea(world, last);
        return resolveSurfaceSpawn(world, last);
    }

    /**
     * Streams the cube column around {@code p} in (blocking, on the level-build thread, which owns the world
     * until the tick starts) so its real surface can be read: one stream center at {@code p}, ticked with an
     * unlimited budget until the column is READY or 15 s pass.
     */
    private static void loadSpawnChunk(World world, Vector3dc p) throws InterruptedException {
        throwIfInterrupted();
        long x = (long) Math.floor(p.x());
        long z = (long) Math.floor(p.z());
        streamUntil(world, p, () -> world.isColumnReadyAt(x, z), null);
    }

    /**
     * Streams the area around {@code spawn} in so the real (carved, populated) terrain is resident before
     * anything collides with it (blocking, on the level-build thread). Once the tick runs, every player's own
     * stream center takes over ({@link #updateStreamCenters}).
     */
    private static void pregenSpawnArea(World world, Vector3dc spawn) throws InterruptedException {
        throwIfInterrupted();
        LoadingScreen.report(t -> t.beginPhase(LoadProgressTracker.Phase.PREGEN));
        int pcx = (int) Math.floor(spawn.x() / 16.0);
        int pcz = (int) Math.floor(spawn.z() / 16.0);
        streamUntil(world, spawn, () -> world.areaDrawnFraction(pcx, pcz, PREGEN_RADIUS) >= 1f,
            () -> reportPregenProgress(world, pcx, pcz));
    }

    private static void streamUntil(World world, Vector3dc center, java.util.function.BooleanSupplier done,
                                    Runnable progress) throws InterruptedException {
        if (world.stream() == null) {
            return;
        }
        world.setStreamCenters(java.util.List.of(world.centerAt(
            (long) Math.floor(center.x()), (long) Math.floor(center.y()), (long) Math.floor(center.z()))));
        world.setStreamBudget(com.openmason.engine.voxel.world.StreamBudget.UNLIMITED);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        long nextReport = 0;
        try {
            while (true) {
                throwIfInterrupted();
                world.tickStream();
                if (done.getAsBoolean()) {
                    break;
                }
                long now = System.nanoTime();
                if (progress != null && now >= nextReport) {
                    progress.run();
                    nextReport = now + TimeUnit.MILLISECONDS.toNanos(PREGEN_PROGRESS_POLL_MILLIS);
                }
                if (now >= deadline) {
                    System.err.println("[SERVER-LEVEL] Spawn-area streaming did not settle within 15 s");
                    break;
                }
                Thread.sleep(5);
            }
        } finally {
            world.setStreamBudget(World.DEFAULT_BUDGET);
        }
        if (progress != null) {
            progress.run();
        }
    }

    private static void reportPregenProgress(World world, int pcx, int pcz) {
        float fraction = world.areaDrawnFraction(pcx, pcz, PREGEN_RADIUS);
        LoadingScreen.report(t -> {
            t.setPhaseFraction(fraction);
            t.setPhaseDetail("Terrain " + Math.round(fraction * 100) + "%");
        });
    }

    /**
     * Snaps the candidate spawn's Y down onto the real top solid block of its column once the
     * chunk is resident. {@code getFinalTerrainHeightAt} samples pre-carve noise and ignores the
     * ravine/sinkhole masks, so a column cut open to the ravine floor would otherwise resolve to
     * rim height. Uses the same standable-column predicate as {@link EntitySpawner}. Falls back
     * to the candidate unchanged if no standable surface is found (e.g. open air to the floor).
     */
    private static Vector3d resolveSurfaceSpawn(World world, Vector3dc candidate) {
        long x = (long) Math.floor(candidate.x());
        long z = (long) Math.floor(candidate.z());
        long top = Math.min(WorldConfiguration.MAX_Y - 1, world.topY(x, z));
        long standY = SpawnLocator.resolveStandingY(world, x, z, top);
        if (standY == SpawnLocator.NO_STAND) {
            return new Vector3d(candidate);
        }
        return new Vector3d(x + 0.5, standY, z + 0.5);
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
    public Vector3d spawn() { return new Vector3d(spawn); }

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

    /** Why saved data could not be restored as-is (world metadata rebuilt, player data set aside, #358), else null. */
    public String loadNotice() { return loadNotice; }

    /** True for the main menu showcase's ephemeral level ({@link #createEphemeral}). */
    public boolean isEphemeral() { return ephemeral; }

    /** The folder the client's FastLOD cache for this world lives in, or null for none. */
    public String lodCachePath() { return lodCachePath; }

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
        world.updateSimulation(deltaTime); // stream tick first (installs, unloads, events), then the sims
        entityManager.update(deltaTime);
        entitySpawner.update(deltaTime);
        timeOfDay.update(deltaTime);
        weather.advance(deltaTime);
    }

    private java.util.List<com.openmason.engine.voxel.world.StreamCenter> lastCenters = java.util.List.of();

    /**
     * Server tick: one stream viewer per positioned player with THAT player's radii (its render distance x 16
     * blocks horizontally, its vertical render distance; {@code ViewDistanceC2S}) - the same shape the
     * client's REMOTE stream plans with, so the server generates/loads what the client will ask for
     * ({@code ServerChunkHandler}). The world spawn holds a viewer while nobody has reported a position, so
     * the spawn area stays resident. Re-planned only when a center changed.
     */
    public void updateStreamCenters(java.util.Collection<ServerPlayer> players) {
        java.util.List<com.openmason.engine.voxel.world.StreamCenter> centers = new java.util.ArrayList<>();
        for (ServerPlayer sp : players) {
            if (!sp.hasReportedPosition()) {
                continue;
            }
            centers.add(centerFor(sp));
        }
        if (centers.isEmpty()) {
            var s = world.getSpawnPosition();
            centers.add(world.centerAt((long) Math.floor(s.x), (long) Math.floor(s.y), (long) Math.floor(s.z)));
        }
        if (!centers.equals(lastCenters)) {
            lastCenters = java.util.List.copyOf(centers);
            world.setStreamCenters(lastCenters);
        }
    }

    /** The stream viewer of one player: its block position and its own view radii. */
    static com.openmason.engine.voxel.world.StreamCenter centerFor(ServerPlayer sp) {
        return new com.openmason.engine.voxel.world.StreamCenter(
            (long) Math.floor(sp.x()), (long) Math.floor(sp.y()), (long) Math.floor(sp.z()),
            sp.viewDistanceChunks() * WorldConfiguration.CHUNK_SIZE, sp.verticalViewBlocks(),
            World.BAND_BELOW, World.BAND_ABOVE);
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
