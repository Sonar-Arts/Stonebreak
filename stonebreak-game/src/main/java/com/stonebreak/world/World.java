package com.stonebreak.world;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeStore;
import com.openmason.engine.voxel.world.CubeStream;
import com.openmason.engine.voxel.world.CubeWorld;
import com.openmason.engine.voxel.world.StreamBudget;
import com.openmason.engine.voxel.world.StreamCenter;
import com.openmason.engine.voxel.world.StreamEvents;
import com.openmason.engine.voxel.world.StreamMesh;
import com.openmason.engine.voxel.world.StreamStats;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterSim;
import com.stonebreak.blocks.waterSystem.WorldFlowWorld;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.ChunkWaterLayer;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.fastlod.FastLodManager;
import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.leaves.LeafDecaySystem;
import com.stonebreak.world.leaves.WorldLeafWorld;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.voxel.BlockIds;
import com.stonebreak.world.voxel.BlockStateRegistry;
import com.stonebreak.world.voxel.CubeEvents;
import com.stonebreak.world.voxel.CubeRenderAssets;
import com.stonebreak.world.voxel.StonebreakBlockTable;
import com.stonebreak.world.voxel.VoxelLayers;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Stonebreak world over the Open Mason native cubic voxel runtime (Stonebreak #447, OpenMason #89 G5).
 *
 * <p><b>Storage.</b> Blocks and per-cell game layers (water, block-state ids, snow - {@link VoxelLayers}) live
 * in one native {@link CubeWorld} ({@link #voxels()}): 32^3 cubes, 64-bit block coordinates, Y in
 * [{@link WorldConfiguration#MIN_Y}, {@link WorldConfiguration#MAX_Y}). What exists is decided by one native
 * {@link CubeStream} ({@link #stream()}): on the authoritative (server / singleplayer-host) world it generates
 * with the game's native generator ({@link NativeTerrain}) and loads/saves through a {@link CubeStore} in the
 * world directory; on a client render world it is REMOTE (cubes arrive from the server through
 * {@code voxels().decode}). A DETACHED world (tests, tools) has neither stream nor store: cubes are created on
 * demand by writes, and never-written space is known air (its surface callback answers the floor).
 *
 * <p><b>Threads.</b> Block reads are thread-safe. Writes, {@link #tickStream()} and every event callback
 * belong to the OWNER thread (client: the main/GL thread; server: the tick thread).
 *
 * <p><b>Known air.</b> {@link #getBlockAt} answers AIR outside the bounds, in known air (a missing cube above
 * its column's top) and for unknown (unloaded) cells; {@link #getBlockIdAt}/{@link #isLoaded}/{@link #isKnown}
 * tell them apart.
 *
 * <p>The full Phase 3 API (what every 3B agent codes against) is listed in
 * {@code Dev Working/docs/Cubic-Voxel-Runtime.md}, section "Phase 3 API".
 */
public class World {

    private static final Logger LOGGER = LoggerFactory.getLogger(World.class);

    /** How a world is built. */
    public enum Mode {
        /** Authoritative: native generator stream (+ store when persisted). */
        SERVER,
        /** Client render view: REMOTE stream, cubes from the server. */
        CLIENT,
        /** No stream, no store: cubes are created by writes (tests, tools, the battle arena). */
        DETACHED
    }

    /**
     * Default per-tick stream budget. Installs are the owner-thread cost of a generating/loading (SERVER) stream:
     * the native swap is ~8-15 us, the game's INSTALLED handling (blob decode, physics, sims, registries,
     * listeners) adds to it; {@value #DEFAULT_MAX_INSTALLS} keeps the server tick's stream step around a
     * millisecond during a cold load (measured: see {@code WorldStreamBudgetTest}). A REMOTE (client) stream's
     * installs are the network decodes, budgeted per frame by the network layer instead; meshes / mesh bytes bound
     * the client's per-frame GPU uploads.
     */
    public static final int DEFAULT_MAX_INSTALLS = 48;
    public static final StreamBudget DEFAULT_BUDGET = new StreamBudget(DEFAULT_MAX_INSTALLS, 96, 16L << 20, 64);
    /** Surface band kept loaded around each column's top (blocks below / above). */
    public static final int BAND_BELOW = 16;
    public static final int BAND_ABOVE = 8;
    /** File of the block-state table next to the cube store. */
    public static final String BLOCK_STATES_FILE = "block_states.txt";

    // Configuration and core systems
    private final WorldConfiguration config;
    private final Mode mode;
    private final long seed;
    private final TerrainGeneratorType generatorType;
    private final TerrainScalePreset terrainScale;
    private final ClimateLayout climateLayout;
    private final HeightCurveVersion heightCurve;
    /** World features (boulders, ...) of a DIFFUSION world: part of its identity, like the height curve. */
    private final com.stonebreak.world.generation.WorldFeatureVersion worldFeatures;
    /** The Standard shape of a STANDARD world (saved in world.json; DIFFUSION worlds ignore it). */
    private final com.stonebreak.world.generation.StandardShapeVersion standardShape;
    private final BlockStateRegistry blockStates = new BlockStateRegistry();
    private final CubeWorld voxels;
    private final CubeStream stream;
    private final CubeStore store;
    private final Path storeDir;
    private volatile NativeTerrain terrain;
    private final Object terrainLock = new Object();
    private volatile StreamBudget budget = DEFAULT_BUDGET;

    private final Map<CubePos, Cube> cubes = new ConcurrentHashMap<>();
    private final Set<CubePos> readyColumns = ConcurrentHashMap.newKeySet();

    private final SnowLayerManager snowLayerManager;
    private final com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry;
    // Per-world crafting-table grids (issue #307), same two-world split as the furnaces.
    private final com.stonebreak.blocks.workbench.WorkbenchStateRegistry workbenchRegistry =
            new com.stonebreak.blocks.workbench.WorkbenchStateRegistry();
    private final com.stonebreak.blocks.anim.AnimatedBlockRegistry animatedBlockRegistry =
            new com.stonebreak.blocks.anim.AnimatedBlockRegistry();

    private final WaterSim waterSim;
    private final LeafDecaySystem leafDecay;
    private final com.stonebreak.world.growth.SaplingGrowthSystem saplingGrowth;
    private final com.stonebreak.blocks.cactus.CactusContactSystem cactusContact;
    private final FastLodLifecycle fastLod;

    // Event listeners (Phase 3 API).
    private volatile java.util.function.Consumer<CubePos> localCubeListener;
    private final List<CubeEvents.InstallListener> installListeners = new CopyOnWriteArrayList<>();
    private final List<CubeEvents.UnloadPendingHook> unloadPendingHooks = new CopyOnWriteArrayList<>();
    private final List<CubeEvents.SaveHook> saveHooks = new CopyOnWriteArrayList<>();
    /** Serializes {@link #commitCubes()} (block-state file + store commit) between save threads. */
    private final Object commitLock = new Object();
    /** Block-state table size last written (the table only grows), -1 = never written. */
    private int savedStateCount = -1;
    private final List<CubeEvents.UnloadListener> unloadListeners = new CopyOnWriteArrayList<>();
    private final List<CubeEvents.MeshConsumer> meshConsumers = new CopyOnWriteArrayList<>();
    private final List<CubeEvents.ChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private final List<CubeEvents.ColumnReadinessListener> readinessListeners = new CopyOnWriteArrayList<>();

    /**
     * Mob path searching for this world. Created on first use and closed in {@link #cleanup()}.
     */
    private volatile com.stonebreak.mobs.entities.ai.nav.PathfindingService pathfinding;
    private final Object pathfindingLock = new Object();
    /** Set once {@link #cleanup()} has run, so a late caller cannot resurrect a torn-down world's service. */
    private volatile boolean cleanedUp;

    // World spawn position
    private Vector3f spawnPosition = new Vector3f(0, 100, 0);

    // Per-world persistence (world/player JSON, autosave; 3B-S). Null = not persisted.
    private volatile com.stonebreak.world.save.SaveService saveService;

    // Torch support sweep re-entry guard (issue #247).
    private volatile boolean sweepingTorches = false;

    private volatile com.stonebreak.mobs.entities.EntitySpawner entitySpawner;
    private volatile com.stonebreak.mobs.entities.EntityManager entityManager;
    private volatile com.stonebreak.mobs.entities.ai.behavior.PlayerLocator playerLocator =
        com.stonebreak.mobs.entities.ai.behavior.PlayerLocator.NONE;

    /** Authoritative simulation mutations reported for replication (headless server world only). */
    private final ServerMutationSinks serverSinks = new ServerMutationSinks();

    // Client stream center bookkeeping (only re-plan when the viewer moved a block or the radii changed).
    private StreamCenter lastClientCenter;
    private volatile List<StreamCenter> streamCenters = List.of();

    public ServerMutationSinks serverSinks() {
        return serverSinks;
    }

    // ===== construction ==========================================================================================

    /** A detached world (no stream): tests and tools. */
    public World() {
        this(new WorldConfiguration());
    }

    /** A detached world (no stream): tests and tools. */
    public World(WorldConfiguration config) {
        this(config, System.currentTimeMillis());
    }

    /** A detached world (no stream): tests and tools. */
    public World(WorldConfiguration config, long seed) {
        this(config, seed, true);
    }

    /**
     * Test constructor kept for subclasses (TestWorld, BattleTestWorld): always DETACHED now (the flag
     * used to choose a GL-backed world).
     */
    protected World(WorldConfiguration config, long seed, boolean testMode) {
        this(config, seed, testMode, TerrainGeneratorType.STANDARD);
    }

    protected World(WorldConfiguration config, long seed, boolean testMode, TerrainGeneratorType generatorType) {
        this(Mode.DETACHED, config, seed, generatorType, TerrainScalePreset.ONE_TO_FOUR, ClimateLayout.DEFAULT, null,
            null, null, null, false);
    }

    /**
     * The authoritative world (server tick thread / singleplayer host) without persistence: generated cubes
     * that unload are regenerated (the menu showcase, tests).
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType) {
        return createHeadless(config, seed, generatorType, TerrainScalePreset.ONE_TO_FOUR, null);
    }

    /**
     * The authoritative world. {@code worldDir} = the world's save directory (its cube store opens in
     * {@code <worldDir>/cubes}, holding cube + column records and the block-state table), or null for an
     * unsaved world.
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, Path worldDir) {
        return createHeadless(config, seed, generatorType, scale, worldDir, false);
    }

    /**
     * {@link #createHeadless(WorldConfiguration, long, TerrainGeneratorType, TerrainScalePreset, Path)} choosing
     * whether its stream builds GPU meshes. The server / integrated world never does ({@code CubeStream.NO_MESH}:
     * no mesh jobs, no REMESHED events; COLUMN_READY = band installed and complete); {@code buildMeshes} is for
     * headless benches that measure meshes of generated terrain (chunk lab).
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, Path worldDir, boolean buildMeshes) {
        return createHeadless(config, seed, generatorType, scale, ClimateLayout.DEFAULT, worldDir, buildMeshes);
    }

    /**
     * The authoritative world with the climate {@code layout} (part of the world's identity, like the seed; only
     * DIFFUSION worlds use it). See
     * {@link #createHeadless(WorldConfiguration, long, TerrainGeneratorType, TerrainScalePreset, Path)}.
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, ClimateLayout layout, Path worldDir) {
        return createHeadless(config, seed, generatorType, scale, layout, worldDir, false);
    }

    /**
     * {@link #createHeadless(WorldConfiguration, long, TerrainGeneratorType, TerrainScalePreset, ClimateLayout, Path)}
     * choosing whether its stream builds GPU meshes.
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, ClimateLayout layout, Path worldDir,
                                       boolean buildMeshes) {
        return createHeadless(config, seed, generatorType, scale, layout, null, worldDir, buildMeshes);
    }

    /**
     * The authoritative world created with the height curve {@code curve} (part of the world's identity, like the
     * seed; null = {@link HeightCurveVersion#CURRENT}, a saved world passes its own; only DIFFUSION worlds use it).
     * See {@link #createHeadless(WorldConfiguration, long, TerrainGeneratorType, TerrainScalePreset, ClimateLayout,
     * Path, boolean)}.
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve,
                                       Path worldDir, boolean buildMeshes) {
        return createHeadless(config, seed, generatorType, scale, layout, curve, null, worldDir, buildMeshes);
    }

    /**
     * The authoritative world created with the feature set {@code features} (boulders, ...; part of the world's
     * identity: null = {@link WorldFeatureVersion#CURRENT}, a saved world passes its own; only DIFFUSION worlds use it).
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve,
                                       com.stonebreak.world.generation.WorldFeatureVersion features,
                                       Path worldDir, boolean buildMeshes) {
        return createHeadless(config, seed, generatorType, scale, layout, curve, features, null, worldDir,
            buildMeshes);
    }

    /**
     * The authoritative world created with the Standard shape {@code standard} (part of a STANDARD world's identity:
     * null = {@link com.stonebreak.world.generation.StandardShapeVersion#CURRENT}, a saved world passes its own).
     */
    public static World createHeadless(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                       TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve,
                                       com.stonebreak.world.generation.WorldFeatureVersion features,
                                       com.stonebreak.world.generation.StandardShapeVersion standard,
                                       Path worldDir, boolean buildMeshes) {
        return new World(Mode.SERVER, config, seed, generatorType, scale, layout, curve, features, standard, worldDir,
            buildMeshes);
    }

    /**
     * A client render-view world: REMOTE stream (cubes arrive from the server, 3B-N), no simulation, no
     * persistence. Its {@link #terrain()} (native generator, same seed) only serves FastLOD and queries.
     */
    public static World createClientView(WorldConfiguration config, long seed, TerrainGeneratorType generatorType) {
        return createClientView(config, seed, generatorType, TerrainScalePreset.ONE_TO_FOUR);
    }

    public static World createClientView(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                         TerrainScalePreset scale) {
        return createClientView(config, seed, generatorType, scale, ClimateLayout.DEFAULT);
    }

    /** A client render-view world of a server world at {@code scale} with the climate {@code layout}. */
    public static World createClientView(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                         TerrainScalePreset scale, ClimateLayout layout) {
        return createClientView(config, seed, generatorType, scale, layout, null);
    }

    /** A client render-view world of a server world created with the height curve {@code curve} (null = current). */
    public static World createClientView(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                         TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve) {
        return createClientView(config, seed, generatorType, scale, layout, curve, null);
    }

    /** A client render-view world of a server world created with the Standard shape {@code standard} (null = current). */
    public static World createClientView(WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                                         TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve,
                                         com.stonebreak.world.generation.StandardShapeVersion standard) {
        return new World(Mode.CLIENT, config, seed, generatorType, scale, layout, curve, null, standard, null, true);
    }

    /** A detached world (no stream, no store; cubes created by writes) for tests and tools. */
    public static World createDetached(WorldConfiguration config, long seed) {
        return new World(Mode.DETACHED, config, seed, TerrainGeneratorType.STANDARD, TerrainScalePreset.ONE_TO_FOUR,
            ClimateLayout.DEFAULT, null, null, null, null, false);
    }

    private World(Mode mode, WorldConfiguration config, long seed, TerrainGeneratorType generatorType,
                  TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve,
                  com.stonebreak.world.generation.WorldFeatureVersion features,
                  com.stonebreak.world.generation.StandardShapeVersion standard, Path worldDir, boolean buildMeshes) {
        this.mode = mode;
        this.config = config;
        this.seed = seed;
        this.generatorType = generatorType == null ? TerrainGeneratorType.STANDARD : generatorType;
        this.terrainScale = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        this.climateLayout = ClimateLayout.orDefault(layout);
        this.heightCurve = HeightCurveVersion.orCurrent(curve);
        this.worldFeatures = com.stonebreak.world.generation.WorldFeatureVersion.orCurrent(features);
        this.standardShape = com.stonebreak.world.generation.StandardShapeVersion.orCurrent(standard);

        if (mode != Mode.DETACHED) {
            applyUserSettings(config);
        }

        this.storeDir = worldDir != null ? worldDir.resolve("cubes") : null;
        if (storeDir != null) {
            try {
                blockStates.load(storeDir.resolve(BLOCK_STATES_FILE));
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Block-state table of {} unreadable; starting fresh", storeDir, e);
            }
        }

        CubeWorld v;
        CubeStream s = null;
        CubeStore st = null;
        NativeTerrain t = null;
        long minY = WorldConfiguration.MIN_Y;
        long maxY = WorldConfiguration.MAX_Y;
        int air = BlockType.AIR.getId();
        int meshFlags = buildMeshes ? CubeStream.MESH_SMOOTH_LIGHTING | CubeStream.MESH_GREEDY : CubeStream.NO_MESH;
        switch (mode) {
            case SERVER -> {
                t = NativeTerrain.create(seed, this.generatorType, this.terrainScale, this.climateLayout,
                    this.heightCurve, this.worldFeatures, this.standardShape, blockStates);
                v = CubeWorld.createForGenerator(minY, maxY, air, t.cvGenerator());
                StonebreakBlockTable.apply(v);
                st = storeDir != null ? CubeStore.open(storeDir) : null;
                s = CubeStream.create(v, st, t.cvGenerator(), config.getChunkBuildThreads(), meshFlags);
            }
            case CLIENT -> {
                v = CubeWorld.create(minY, maxY, air, null);
                StonebreakBlockTable.apply(v);
                s = CubeStream.createRemote(v, Math.max(1, config.getChunkBuildThreads()), meshFlags);
                CubeRenderAssets.attach(v, blockStates);
            }
            default -> {
                // No generator: never-generated space is empty, so its surface and top sit on the floor and
                // every missing cube above it is KNOWN AIR (a detached world reads as an empty world).
                v = CubeWorld.create(minY, maxY, air, (cx, cz, sky, top) -> {
                    java.util.Arrays.fill(sky, minY);
                    java.util.Arrays.fill(top, minY);
                    return true;
                });
                StonebreakBlockTable.apply(v);
            }
        }
        this.voxels = v;
        this.stream = s;
        this.store = st;
        this.terrain = t;

        this.snowLayerManager = new SnowLayerManager(this);
        com.stonebreak.crafting.SmeltingManager smelting = null;
        try {
            Game g = Game.getInstance();
            if (g != null) {
                smelting = g.getSmeltingManager();
            }
        } catch (Exception ignored) {
            // very early bootstrap / tests
        }
        this.furnaceRegistry = new com.stonebreak.blocks.furnace.FurnaceStateRegistry(smelting);
        this.snowLayerManager.setMutationListener((x, y, z, layers) -> {
            invalidatePhysicsCell(x, y, z);
            Cube cube = getCubeAt(x, y, z);
            if (cube != null) {
                cube.markPayloadDirty();
            }
            ServerMutationSinks.SnowSink sink = serverSinks.snow();
            if (sink != null && fitsInt(x, y, z)) {
                sink.onServerSnowChange(i(x), i(y), i(z), layers);
            }
        });

        this.waterSim = new WaterSim(new WorldFlowWorld(this));
        this.leafDecay = new LeafDecaySystem(new WorldLeafWorld(this));
        this.saplingGrowth = new com.stonebreak.world.growth.SaplingGrowthSystem(
            new com.stonebreak.world.growth.WorldSaplingWorld(this), new java.util.Random());
        this.cactusContact = new com.stonebreak.blocks.cactus.CactusContactSystem(this);
        this.fastLod = new FastLodLifecycle(config, this::terrain);
        LOGGER.info("World created: mode={} seed={} generator={} scale={} climate={} store={}", mode, seed,
            this.generatorType, this.terrainScale, this.climateLayout, storeDir);
    }

    private static void applyUserSettings(WorldConfiguration config) {
        try {
            com.stonebreak.config.Settings s = com.stonebreak.config.Settings.getInstance();
            config.setRenderDistance(s.getRenderDistance());
            config.setVerticalRenderDistance(s.getVerticalRenderDistance());
            config.setLodRange(s.getLodDistance());
            config.setLodEnabled(s.getLodEnabled());
            config.setLodQuality(com.stonebreak.world.fastlod.FastLodQuality.parse(s.getLodQuality()));
        } catch (Exception ignored) {
            // Settings singleton unavailable (e.g. very early bootstrap) - config defaults.
        }
    }

    static int i(long v) {
        return Math.toIntExact(v);
    }

    /**
     * True when a block position fits the int-coordinate subsystems that are not ported to 64-bit yet
     * (animated blocks, torch support, replication sinks, MultiplayerSession - 3B/Phase 4). Edits beyond
     * +-2^31 skip their notifications instead of throwing.
     */
    static boolean fitsInt(long x, long y, long z) {
        return x == (int) x && y == (int) y && z == (int) z;
    }

    // ===== accessors =============================================================================================

    public Mode mode() {
        return mode;
    }

    /** The native voxel world (blocks, layers, blobs, surface tracker, encode/decode). */
    public CubeWorld voxels() {
        return voxels;
    }

    /** The native stream, or null for a detached world. */
    public CubeStream stream() {
        return stream;
    }

    /** The cube store, or null when the world is not persisted. */
    public CubeStore cubeStore() {
        return store;
    }

    /** Directory of the cube store (and the block-state table), or null. */
    public Path cubeStoreDir() {
        return storeDir;
    }

    /** This world's block-state names &lt;-&gt; STATE layer ids. */
    public BlockStateRegistry blockStates() {
        return blockStates;
    }

    public TerrainScalePreset getTerrainScale() {
        return terrainScale;
    }

    /**
     * The world's climate layout: fixed at creation, part of its identity like the seed (DIFFUSION worlds build
     * their terrain from it; Standard worlds carry it and ignore it).
     */
    public ClimateLayout getClimateLayout() {
        return climateLayout;
    }

    /**
     * The world's height-curve version: fixed at creation, part of its identity (DIFFUSION worlds build their terrain
     * with it - saved worlds keep theirs, so new cubes never meet old ones at a seam; Standard worlds ignore it).
     */
    public HeightCurveVersion getHeightCurve() {
        return heightCurve;
    }

    /** The world's feature set (boulders, ...): fixed at creation, part of its identity (DIFFUSION worlds only). */
    public com.stonebreak.world.generation.WorldFeatureVersion getWorldFeatures() {
        return worldFeatures;
    }

    /** The world's Standard shape (STANDARD worlds' terrain; DIFFUSION worlds carry it and ignore it). */
    public com.stonebreak.world.generation.StandardShapeVersion getStandardShape() {
        return standardShape;
    }

    public void setStreamBudget(StreamBudget budget) {
        this.budget = budget == null ? DEFAULT_BUDGET : budget;
    }

    /**
     * True when this is a client render-view world ({@link #createClientView}): generates no terrain, runs no
     * authoritative sim.
     */
    public boolean isRenderOnly() {
        return mode == Mode.CLIENT;
    }

    /** True for the authoritative world (server mode or a detached test world): sims run here. */
    public boolean isAuthoritative() {
        return mode != Mode.CLIENT;
    }

    // ===== Phase 3 events ========================================================================================

    public void addInstallListener(CubeEvents.InstallListener l) { installListeners.add(l); }
    public void removeInstallListener(CubeEvents.InstallListener l) { installListeners.remove(l); }
    public void addUnloadPendingHook(CubeEvents.UnloadPendingHook h) { unloadPendingHooks.add(h); }
    public void removeUnloadPendingHook(CubeEvents.UnloadPendingHook h) { unloadPendingHooks.remove(h); }
    public void addSaveHook(CubeEvents.SaveHook h) { saveHooks.add(h); }
    public void removeSaveHook(CubeEvents.SaveHook h) { saveHooks.remove(h); }
    public void addUnloadListener(CubeEvents.UnloadListener l) { unloadListeners.add(l); }
    public void removeUnloadListener(CubeEvents.UnloadListener l) { unloadListeners.remove(l); }
    public void addMeshConsumer(CubeEvents.MeshConsumer c) { meshConsumers.add(c); }
    public void removeMeshConsumer(CubeEvents.MeshConsumer c) { meshConsumers.remove(c); }
    public void addChangeListener(CubeEvents.ChangeListener l) { changeListeners.add(l); }
    public void removeChangeListener(CubeEvents.ChangeListener l) { changeListeners.remove(l); }
    public void addColumnReadinessListener(CubeEvents.ColumnReadinessListener l) { readinessListeners.add(l); }
    public void removeColumnReadinessListener(CubeEvents.ColumnReadinessListener l) { readinessListeners.remove(l); }

    // ===== streaming ============================================================================================

    /**
     * Replaces the stream's viewers (any thread). Server: one per player (3B-N wires ServerLevel); client: the
     * world sets its own from the local player in {@link #updateMainThread()}.
     */
    public void setStreamCenters(List<StreamCenter> centers) {
        if (stream != null && !cleanedUp) {
            streamCenters = List.copyOf(centers);
            stream.setCenters(centers);
        }
    }

    /** The stream's current viewers (as last set; empty before the first {@link #setStreamCenters}). */
    public List<StreamCenter> streamCenters() {
        return streamCenters;
    }

    /** A viewer at a block position with this world's radii (render distance, vertical render distance). */
    public StreamCenter centerAt(long x, long y, long z) {
        return new StreamCenter(x, y, z, config.getHorizontalRenderRadiusBlocks(),
            config.getVerticalRenderDistance(), BAND_BELOW, BAND_ABOVE);
    }

    /**
     * OWNER THREAD. One stream tick within {@link #setStreamBudget the budget}: installs finished cubes, unloads,
     * hands out meshes, and dispatches every event (see {@link CubeEvents}). No-op for a detached world.
     */
    public void tickStream() {
        CubeStream s = stream;
        if (s == null || cleanedUp) {
            return;
        }
        StreamEvents events = s.tick(budget);
        int n = events.count();
        for (int k = 0; k < n; k++) {
            int type = events.type(k);
            long cx = events.cx(k), cy = events.cy(k), cz = events.cz(k);
            try {
                switch (type) {
                    case CubeStream.EV_INSTALLED -> onInstalled(new CubePos(cx, cy, cz));
                    case CubeStream.EV_UNLOAD_PENDING -> onUnloadPending(new CubePos(cx, cy, cz));
                    case CubeStream.EV_UNLOADED -> onUnloaded(new CubePos(cx, cy, cz));
                    case CubeStream.EV_REMESHED -> onRemeshed(s, events.mesh(k));
                    case CubeStream.EV_CHANGED -> onChanged(new CubePos(cx, cy, cz));
                    case CubeStream.EV_COLUMN_READY -> {
                        readyColumns.add(new CubePos(cx, 0, cz));
                        for (CubeEvents.ColumnReadinessListener l : readinessListeners) l.onColumnReady(this, cx, cz);
                    }
                    case CubeStream.EV_COLUMN_UNREADY -> {
                        readyColumns.remove(new CubePos(cx, 0, cz));
                        for (CubeEvents.ColumnReadinessListener l : readinessListeners) l.onColumnUnready(this, cx, cz);
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                LOGGER.error("Stream event {} at ({}, {}, {}) failed", type, cx, cy, cz, e);
            }
        }
    }

    private void onInstalled(CubePos pos) {
        Cube cube = cubes.computeIfAbsent(pos, p -> new Cube(this, p));
        byte[] blob = voxels.getBlob(pos.x(), pos.y(), pos.z());
        cube.decodeBlob(blob);
        invalidatePhysicsCube(pos.x(), pos.y(), pos.z());
        fireInstalled(cube);
    }

    /** Runs the built-in and registered install listeners for a cube (detached worlds: call after writing). */
    public void fireInstalled(Cube cube) {
        if (isAuthoritative()) {
            waterSim.onCubeInstalled(cube);
            leafDecay.onCubeInstalled(cube);
            saplingGrowth.onCubeInstalled(cube);
        }
        furnaceRegistry.onCubeInstalled(cube);
        workbenchRegistry.onCubeInstalled(cube);
        animatedBlockRegistry.onCubeInstalled(cube);
        for (CubeEvents.InstallListener l : installListeners) {
            l.onCubeInstalled(this, cube);
        }
    }

    private void onUnloadPending(CubePos pos) {
        Cube cube = cubes.get(pos);
        if (cube == null) {
            return;
        }
        furnaceRegistry.onCubeUnloadPending(cube);
        workbenchRegistry.onCubeUnloadPending(cube);
        runSaveHooks(cube);
        for (CubeEvents.UnloadPendingHook h : unloadPendingHooks) {
            h.onCubeUnloadPending(this, cube);
        }
        writeBlob(cube);
    }

    private void writeBlob(Cube cube) {
        if (!isAuthoritative() || !cube.isPayloadDirty()) {
            return;
        }
        voxels.setBlob(cube.cx(), cube.cy(), cube.cz(), cube.encodeBlob());
    }

    private void onUnloaded(CubePos pos) {
        Cube cube = cubes.remove(pos);
        invalidatePhysicsCube(pos.x(), pos.y(), pos.z());
        if (cube == null) {
            return;
        }
        if (isAuthoritative()) {
            waterSim.onCubeUnloaded(cube);
            leafDecay.onCubeUnloaded(cube);
            saplingGrowth.onCubeUnloaded(cube);
        }
        furnaceRegistry.onCubeUnloaded(cube);
        workbenchRegistry.onCubeUnloaded(cube);
        animatedBlockRegistry.onCubeUnloaded(cube);
        for (CubeEvents.UnloadListener l : unloadListeners) {
            l.onCubeUnloaded(this, cube);
        }
    }

    /**
     * CV_EV_CHANGED: the cube's content moved after its INSTALLED event (a population finished writing into it,
     * edits, a network decode over it) - in meshing and NO_MESH streams alike, at most once per cube per tick.
     * The physics cache may hold the old cells; then the registered {@link CubeEvents.ChangeListener}s run.
     */
    private void onChanged(CubePos pos) {
        invalidatePhysicsCube(pos.x(), pos.y(), pos.z());
        if (changeListeners.isEmpty()) {
            return;
        }
        Cube cube = cubes.get(pos);
        if (cube == null) {
            return;
        }
        for (CubeEvents.ChangeListener l : changeListeners) {
            l.onCubeChanged(this, cube);
        }
    }

    private void onRemeshed(CubeStream s, int slot) {
        // Display only: content changes (physics invalidation, network re-sends) ride CV_EV_CHANGED.
        if (meshConsumers.isEmpty() || slot < 0) {
            return;
        }
        StreamMesh mesh = s.mesh(slot);
        for (CubeEvents.MeshConsumer c : meshConsumers) {
            c.onCubeMesh(this, mesh);
        }
    }

    /**
     * OWNER THREAD. Saves everything now: {@link #flushCubeBlobs()} then {@link #commitCubes()} (blocking I/O).
     * No-op without a store. Savers that must not block the owner thread split the two (3B-S SaveService).
     */
    public void saveAll() {
        if (stream == null || store == null || cleanedUp) {
            return;
        }
        flushCubeBlobs();
        commitCubes();
    }

    /**
     * OWNER THREAD (server tick thread / the thread running {@link #tickStream()}). Puts the game state of every
     * loaded cube into its blob: furnace/workbench registries, then the {@link CubeEvents.SaveHook}s (entities,
     * saplings), then writes the blob of each cube whose payload changed. Cheap (no I/O). No-op without a store.
     */
    public void flushCubeBlobs() {
        if (stream == null || store == null || cleanedUp) {
            return;
        }
        for (Cube cube : cubes.values()) {
            furnaceRegistry.flushToWorld(cube);
            workbenchRegistry.flushToWorld(cube);
            runSaveHooks(cube);
            writeBlob(cube);
        }
    }

    /**
     * Any thread (blocking I/O). Makes everything written so far durable: the block-state table first (cube
     * records reference its ids), then {@code cv_stream_save_all} = every changed cube + column record and
     * {@code cv_store_flush}. Concurrent calls are serialized. No-op without a store or after {@link #cleanup}.
     */
    public void commitCubes() {
        if (stream == null || store == null) {
            return;
        }
        synchronized (commitLock) {
            if (cleanedUp) {
                return;
            }
            saveBlockStates();
            stream.saveAll();
            // The owner thread may have registered a state id (and written it into a cube the save just
            // encoded) while the store saved: write the table again when it grew.
            saveBlockStates();
        }
    }

    private void runSaveHooks(Cube cube) {
        if (!isAuthoritative()) {
            return;
        }
        for (CubeEvents.SaveHook h : saveHooks) {
            try {
                h.onCubeSave(this, cube);
            } catch (RuntimeException e) {
                LOGGER.error("Save hook failed for cube {}", cube.pos(), e);
            }
        }
    }

    private void saveBlockStates() {
        if (storeDir == null) {
            return;
        }
        synchronized (commitLock) {
            int n = blockStates.size();
            if (n == savedStateCount) {
                return;
            }
            try {
                StringBuilder text = new StringBuilder();
                for (String name : blockStates.names()) {
                    text.append(name).append('\n');
                }
                com.stonebreak.world.save.io.AtomicFiles.writeDurable(storeDir.resolve(BLOCK_STATES_FILE),
                    text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                savedStateCount = n;
            } catch (IOException e) {
                LOGGER.error("Could not save the block-state table to {}", storeDir, e);
            }
        }
    }

    /** True when every band cube of column cube (cx, cz) is installed and meshed (CV_EV_COLUMN_READY). */
    public boolean isColumnReady(long cx, long cz) {
        if (cleanedUp) {
            return false;
        }
        // A detached world has no stream: every column counts as ready (tests, tools).
        return mode == Mode.DETACHED || readyColumns.contains(new CubePos(cx, 0, cz));
    }

    /** The column readiness of the column holding block (x, z). */
    public boolean isColumnReadyAt(long x, long z) {
        return isColumnReady(x >> 5, z >> 5);
    }

    /** Native stream counters (debug overlay); null for a detached world. */
    public StreamStats streamStats() {
        return stream != null && !cleanedUp ? stream.stats() : null;
    }

    // ===== update entry points ==================================================================================

    /** Legacy rendered update: the client tick for render-only worlds, the sim for authoritative ones. */
    public void update(com.stonebreak.rendering.Renderer renderer) {
        if (isRenderOnly()) {
            updateClient(renderer);
        } else {
            updateSimulation(Game.getDeltaTime());
        }
    }

    /**
     * Authoritative simulation step (server tick thread): stream tick (installs, unloads, events), then water,
     * leaf decay, saplings, cactus contact and furnaces.
     */
    public void updateSimulation(float deltaTime) {
        tickStream();
        waterSim.tick(deltaTime);
        leafDecay.tick(deltaTime);
        saplingGrowth.tick(deltaTime);
        cactusContact.tick(deltaTime);
        furnaceRegistry.tick(this, deltaTime);
    }

    /**
     * Render-only client update off the main thread: only the FastLOD ring tick now (streaming, meshing and
     * unloading are native and ticked on the main thread by {@link #updateMainThread()}).
     */
    public void updateClient(com.stonebreak.rendering.Renderer renderer) {
        var lodPlayer = Game.getPlayer();
        if (lodPlayer != null) {
            fastLod.updateRing(lodPlayer.getPosition());
        }
    }

    /**
     * Main (GL) thread, every frame: on a client world, follows the local player with the stream's viewer and
     * ticks the stream (REMESHED meshes must reach GL on this thread).
     */
    public void updateMainThread() {
        if (!isRenderOnly() || stream == null || cleanedUp) {
            return;
        }
        var player = Game.getPlayer();
        if (player != null) {
            org.joml.Vector3dc p = player.getPosition();
            StreamCenter c = centerAt((long) Math.floor(p.x()), (long) Math.floor(p.y()), (long) Math.floor(p.z()));
            StreamCenter last = lastClientCenter;
            if (last == null || !last.equals(c)) {
                lastClientCenter = c;
                setStreamCenters(List.of(c));
            }
        }
        tickStream();
    }

    /** Kept for the game loop; GPU cleanup of cube meshes belongs to the renderer now (3B-R). */
    public void processGpuCleanupQueue() {
    }

    /**
     * Chebyshev radius (16-block chunks) the server streams/forgets around a player (network keep radius,
     * 3B-N): render distance + 2.
     */
    public int clientKeepRadius() {
        return config.getRenderDistance() + 2;
    }

    // ===== cubes ================================================================================================

    /** The loaded cube (cx, cy, cz), or null. */
    public Cube getCube(long cx, long cy, long cz) {
        Cube cube = cubes.get(new CubePos(cx, cy, cz));
        if (cube == null && mode == Mode.DETACHED && !cleanedUp && voxels.hasCube(cx, cy, cz)) {
            cube = cubes.computeIfAbsent(new CubePos(cx, cy, cz), p -> new Cube(this, p));
        }
        return cube;
    }

    /** The loaded cube holding block (x, y, z), or null. */
    public Cube getCubeAt(long x, long y, long z) {
        return getCube(x >> 5, y >> 5, z >> 5);
    }

    /** True when cube (cx, cy, cz) is loaded. */
    public boolean hasCube(long cx, long cy, long cz) {
        return !cleanedUp && voxels.hasCube(cx, cy, cz);
    }

    /** Every loaded cube object (a snapshot view; owner thread for consistency). */
    public Collection<Cube> getLoadedCubes() {
        return Collections.unmodifiableCollection(cubes.values());
    }

    public int getLoadedCubeCount() {
        return cleanedUp ? 0 : (int) voxels.cubeCount();
    }

    /** Debug alias of {@link #getLoadedCubeCount()} (overlay rows; counts CUBES now). */
    public int getLoadedChunkCount() {
        return getLoadedCubeCount();
    }

    /**
     * Ensures cube (cx, cy, cz) exists, creating it filled with air when absent and either the world is
     * DETACHED (tests, tools, the battle arena) or the whole cube is KNOWN AIR ({@link #isCubeKnownAir}) - the
     * stream never loads all-air cubes, so building above the terrain needs the cube made first. On a SERVER
     * world the stream adopts the created cube (INSTALLED next tick, saved like any edit, the raised column top
     * pulls it into clients' bands); on a CLIENT world it is a local prediction the server's record replaces
     * ({@link #setLocalCubeListener} asks the server for it). A cube with unknown cells is never created (that
     * would wipe terrain still loading): null until the stream brings it.
     */
    public Cube ensureCube(long cx, long cy, long cz) {
        if (cleanedUp) {
            return null;
        }
        if (!voxels.hasCube(cx, cy, cz)
                && (cy << 5) + 32 > WorldConfiguration.MIN_Y && (cy << 5) < WorldConfiguration.MAX_Y
                && (mode == Mode.DETACHED || isCubeKnownAir(cx, cy, cz))) {
            if (voxels.createCube(cx, cy, cz, BlockType.AIR.getId())) {
                CubePos pos = new CubePos(cx, cy, cz);
                Cube cube = cubes.computeIfAbsent(pos, p -> new Cube(this, p));
                invalidatePhysicsCube(cx, cy, cz);
                java.util.function.Consumer<CubePos> listener = localCubeListener;
                if (mode == Mode.CLIENT && listener != null) {
                    listener.accept(pos);
                }
                return cube;
            }
        }
        return getCube(cx, cy, cz);
    }

    /**
     * True when cube (cx, cy, cz) is missing and every cell of it is known air: its column is known and the cube
     * lies wholly at or above the highest TOP of the column's 32x32 footprint.
     */
    public boolean isCubeKnownAir(long cx, long cy, long cz) {
        if (cleanedUp || voxels.hasCube(cx, cy, cz)) {
            return false;
        }
        CubeWorld.TopRange r = voxels.topRange(cx, cz);
        return r.known() && (cy << 5) >= r.topMax();
    }

    /**
     * CLIENT worlds: told (owner thread) when {@link #ensureCube} created a predicted air cube, so the network
     * layer can request the server's real record for it (a cube the client holds is never "missing").
     */
    public void setLocalCubeListener(java.util.function.Consumer<CubePos> listener) {
        this.localCubeListener = listener;
    }

    /** True when block (x, y, z)'s cube is loaded (a missing cube is false even in known air). */
    public boolean isLoaded(long x, long y, long z) {
        if (cleanedUp || !WorldConfiguration.inBounds(y)) {
            return false;
        }
        return voxels.hasCube(x >> 5, y >> 5, z >> 5);
    }

    /** True when the cell's content is known: its cube is loaded, it is known air, or it is out of bounds. */
    public boolean isKnown(long x, long y, long z) {
        return !cleanedUp && voxels.get(x, y, z) != CubeWorld.MISSING;
    }

    /**
     * True when a body at block (x, y, z) can be simulated: its cell and the one under it are known (loaded or
     * known air). Replaces the column-era {@code isChunkRenderableAt}: the player is held until the ground
     * under it has streamed in.
     */
    public boolean isAreaReadyAt(long x, long y, long z) {
        return isKnown(x, y, z) && isKnown(x, y - 1, z);
    }

    /**
     * The fraction (0..1) of 16-block columns within {@code radius} (a disc, in 16-block units, the menu
     * showcase's convention) whose cube column is READY ({@link #isColumnReady}).
     */
    public float areaDrawnFraction(int centerChunkX, int centerChunkZ, int radius) {
        int total = 0;
        int drawn = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                total++;
                long x = (long) (centerChunkX + dx) * 16;
                long z = (long) (centerChunkZ + dz) * 16;
                if (isColumnReadyAt(x, z)) {
                    drawn++;
                }
            }
        }
        return total == 0 ? 1f : (float) drawn / total;
    }

    // ===== surface ==============================================================================================

    /** First free y above the topmost sky-opaque block at (x, z) (generator answer where never generated). */
    public long surfaceY(long x, long z) {
        return cleanedUp ? WorldConfiguration.MIN_Y : voxels.surfaceY(x, z);
    }

    /** First free y above the topmost non-air block at (x, z), water included. */
    public long topY(long x, long z) {
        return cleanedUp ? WorldConfiguration.MIN_Y : voxels.topY(x, z);
    }

    // ===== physics (engine cp_*) ================================================================================

    private volatile com.stonebreak.world.physics.WorldPhysics physics;

    /** This world's physics, created on first use by the thread that simulates this world. */
    public com.stonebreak.world.physics.WorldPhysics physics() {
        com.stonebreak.world.physics.WorldPhysics p = physics;
        if (p == null) {
            synchronized (this) {
                p = physics;
                if (p == null) {
                    if (cleanedUp) {
                        // Closed: no physics of its own (WorldPhysics.of answers over the closed-world
                        // accessors - all air - instead of throwing at a straggling caller).
                        return null;
                    }
                    p = com.stonebreak.world.physics.WorldPhysics.create(this);
                    physics = p;
                }
            }
        }
        return p;
    }

    /** A block or block state changed (no-op before physics exists). */
    public void invalidatePhysicsBlock(long x, long y, long z) {
        com.stonebreak.world.physics.WorldPhysics p = physics;
        if (p != null) p.invalidateBlock(x, y, z);
    }

    /** A water level changed. */
    public void invalidatePhysicsWater(long x, long y, long z) {
        com.stonebreak.world.physics.WorldPhysics p = physics;
        if (p != null) p.invalidateWater(x, y, z);
    }

    /** A cell's own shape changed (a snow layer). */
    public void invalidatePhysicsCell(long x, long y, long z) {
        com.stonebreak.world.physics.WorldPhysics p = physics;
        if (p != null) p.invalidateCell(x, y, z);
    }

    /** A cube arrived (INSTALLED), changed (CV_EV_CHANGED: population, decodes), was created or left. */
    public void invalidatePhysicsCube(long cx, long cy, long cz) {
        com.stonebreak.world.physics.WorldPhysics p = physics;
        if (p != null) p.invalidateCube(cx, cy, cz);
    }

    /** Additional static model colliders queried by player physics. Ordinary voxel worlds have none. */
    public List<float[]> getStaticCollisionBoxes(Vector3f position, float range) {
        return List.of();
    }

    // ===== blocks ===============================================================================================

    /** The block at (x, y, z): AIR out of bounds, in known air, and for unknown (unloaded) cells. */
    public BlockType getBlockAt(long x, long y, long z) {
        if (cleanedUp) {
            return BlockType.AIR;
        }
        int id = voxels.get(x, y, z);
        return id <= 0 ? BlockType.AIR : BlockIds.of(id);
    }

    /** The raw block id, AIR's id in known air / out of bounds, {@link CubeWorld#MISSING} when unknown. */
    public int getBlockIdAt(long x, long y, long z) {
        return cleanedUp ? CubeWorld.MISSING : voxels.get(x, y, z);
    }

    public boolean setBlockAt(long x, long y, long z, BlockType blockType) {
        return setBlockAt(x, y, z, blockType, false);
    }

    public boolean setBlockAt(long x, long y, long z, BlockType blockType, boolean isPlayerModification) {
        return setBlockAt(x, y, z, blockType, isPlayerModification, null);
    }

    /**
     * Writes a block (the one block-change funnel: physics, water/leaf/sapling sims, animated blocks, torch
     * support, replication of player edits). On a type change the cell's state, water and snow layers are
     * cleared (a WATER cell keeps its water value, a SNOW cell its count). The placement state is written
     * after the block and rides along in the player's block-change intent.
     *
     * @return false when out of bounds or the cube is not loaded (DETACHED worlds create it)
     */
    public boolean setBlockAt(long x, long y, long z, BlockType blockType, boolean isPlayerModification,
                              String placementState) {
        if (blockType == null || cleanedUp || !WorldConfiguration.inBounds(y)) {
            return false;
        }
        // Writes into known air make the cube (server: any authoritative write; client: the local player's
        // predicted edit). Air into known air changes nothing, so it never creates one.
        if (mode == Mode.DETACHED
                || (blockType != BlockType.AIR && (mode == Mode.SERVER || isPlayerModification))) {
            ensureCube(x >> 5, y >> 5, z >> 5);
        }
        int prevId = voxels.set(x, y, z, blockType.getId());
        if (prevId == CubeWorld.MISSING) {
            return false;
        }
        BlockType previous = BlockIds.of(prevId);
        if (previous == blockType) {
            if (placementState != null && !placementState.isEmpty()) {
                setBlockStateAt(x, y, z, placementState);
            }
            return true;
        }
        clearCellLayers(x, y, z, blockType);
        invalidatePhysicsBlock(x, y, z);

        if (isAuthoritative()) {
            waterSim.onBlockChanged(x, y, z, previous, blockType);
            leafDecay.onBlockChanged(x, y, z, previous, blockType);
            saplingGrowth.onBlockChanged(x, y, z, previous, blockType);
        }
        if (placementState != null && !placementState.isEmpty()) {
            setBlockStateAt(x, y, z, placementState);
        }

        boolean small = fitsInt(x, y, z);
        if (small) {
            animatedBlockRegistry.onBlockChanged(i(x), i(y), i(z), previous, blockType);
        }

        // Torch support sweep (issue #247), authoritative worlds only.
        if (small && isAuthoritative() && !sweepingTorches) {
            sweepingTorches = true;
            try {
                com.stonebreak.blocks.torch.TorchBlock.popUnsupported(this, i(x), i(y), i(z));
            } finally {
                sweepingTorches = false;
            }
        }

        if (isPlayerModification && small) {
            com.stonebreak.network.MultiplayerSession.onLocalBlockChange(
                    i(x), i(y), i(z), blockType, previous, placementState);
        }
        return true;
    }

    /** Drops the per-cell layers a block type change invalidates (and the payload state). */
    private void clearCellLayers(long x, long y, long z, BlockType now) {
        if (voxels.getLayer(VoxelLayers.STATE, x, y, z) > 0) {
            voxels.setLayer(VoxelLayers.STATE, x, y, z, 0);
        }
        Cube cube = cubes.get(CubePos.ofBlock(x, y, z));
        if (cube != null && cube.payloadState(Cube.cellOf(x, y, z)) != null) {
            cube.setPayloadState(Cube.cellOf(x, y, z), null);
        }
        if (now != BlockType.WATER && voxels.getLayer(VoxelLayers.WATER, x, y, z) > 0) {
            voxels.setLayer(VoxelLayers.WATER, x, y, z, 0);
        }
        if (now != BlockType.SNOW && voxels.getLayer(VoxelLayers.SNOW, x, y, z) > 0) {
            voxels.setLayer(VoxelLayers.SNOW, x, y, z, 0);
        }
    }

    /**
     * Writes a block WITHOUT the funnel (no sims, no torch sweep, no replication; layers still cleared) - for
     * bulk placement by game code that runs its own bookkeeping (tree growth, structure stamps). Physics is
     * still invalidated. False when the cube is not loaded.
     */
    public boolean setBlockRaw(long x, long y, long z, BlockType blockType) {
        if (cleanedUp || !WorldConfiguration.inBounds(y)) {
            return false;
        }
        if (mode == Mode.DETACHED || (mode == Mode.SERVER && blockType != BlockType.AIR)) {
            ensureCube(x >> 5, y >> 5, z >> 5); // tree growth / stamps above the terrain
        }
        int prev = voxels.set(x, y, z, blockType.getId());
        if (prev == CubeWorld.MISSING) {
            return false;
        }
        if (prev != blockType.getId()) {
            clearCellLayers(x, y, z, blockType);
            invalidatePhysicsBlock(x, y, z);
        }
        return true;
    }

    /** True when the block at the position is WATER. */
    public boolean isPositionUnderwater(long x, long y, long z) {
        return getBlockAt(x, y, z) == BlockType.WATER;
    }

    // ===== block states (STATE layer + cube payload) ===========================================================

    /**
     * The block state string at a position, or null (no state, unloaded). Payload-carrying states (furnace,
     * crafting table) come back whole from the cube's payload; others from the STATE layer id.
     */
    public String getBlockStateAt(long x, long y, long z) {
        if (cleanedUp || !WorldConfiguration.inBounds(y)) {
            return null;
        }
        Cube cube = cubes.get(CubePos.ofBlock(x, y, z));
        if (cube != null) {
            String payload = cube.payloadState(Cube.cellOf(x, y, z));
            if (payload != null) {
                return payload;
            }
        }
        int id = voxels.getLayer(VoxelLayers.STATE, x, y, z);
        return id > 0 ? blockStates.nameOf(id) : null;
    }

    /**
     * Sets the block state string (null/blank clears). The STATE layer gets the state's mesh-variant id (a
     * change remeshes the cube natively); payload beyond the variant key is kept in the cube. No-op when the
     * cube is not loaded.
     */
    public void setBlockStateAt(long x, long y, long z, String state) {
        if (cleanedUp || !WorldConfiguration.inBounds(y)) {
            return;
        }
        if (mode == Mode.DETACHED) {
            ensureCube(x >> 5, y >> 5, z >> 5);
        }
        if (!voxels.hasCube(x >> 5, y >> 5, z >> 5)) {
            return;
        }
        String raw = state == null || state.isBlank() ? null : state;
        String variant = com.stonebreak.blocks.BlockRenderState.meshVariantKey(raw);
        int id = blockStates.idOf(variant);
        // A payload-only string (crafting grid) has no variant: keep a marker id so the cell is still
        // found by layer scans; its text lives in the payload.
        if (id == BlockStateRegistry.NONE && raw != null) {
            id = blockStates.idOf(PAYLOAD_ONLY);
        }
        int previousId = voxels.setLayer(VoxelLayers.STATE, x, y, z, id);
        Cube cube = getCubeAt(x, y, z);
        if (cube != null) {
            cube.setPayloadState(Cube.cellOf(x, y, z), raw != null && !raw.equals(variant) ? raw : null);
            if (previousId != id) {
                cube.markPayloadDirty();
            }
        }
        invalidatePhysicsBlock(x, y, z);
        if (fitsInt(x, y, z)) {
            animatedBlockRegistry.onStateChanged(i(x), i(y), i(z));
        }
    }

    /** STATE layer marker for a state that is all payload (no mesh variant). */
    public static final String PAYLOAD_ONLY = "#payload";

    // ===== water (WATER layer) ==================================================================================

    /**
     * The water value at a position in the game vocabulary ({@link ChunkWaterLayer}: 0 source, 1..7 flowing,
     * 8 falling, RIVER + octant), or -1 when the cell is not loaded water.
     */
    public int getWaterValueAt(long x, long y, long z) {
        if (getBlockIdAt(x, y, z) != BlockType.WATER.getId()) {
            return -1;
        }
        int raw = voxels.getLayer(VoxelLayers.WATER, x, y, z);
        return raw < 0 ? -1 : VoxelLayers.waterToGame(raw);
    }

    /**
     * Writes a water value (game vocabulary; 0 = source) on a WATER cell; false when not loaded. Invalidates
     * physics (flow forces read the neighbours). The block itself is not changed.
     */
    public boolean setWaterValueAt(long x, long y, long z, int value) {
        if (cleanedUp || !WorldConfiguration.inBounds(y)) {
            return false;
        }
        int prev = voxels.setLayer(VoxelLayers.WATER, x, y, z, VoxelLayers.waterToRaw(value));
        if (prev == CubeWorld.MISSING) {
            return false;
        }
        invalidatePhysicsWater(x, y, z);
        return true;
    }

    /** Water level 0..8 (river = source) or -1 when not loaded water. */
    public int getWaterLevelAt(long x, long y, long z) {
        int value = getWaterValueAt(x, y, z);
        return value < 0 ? -1 : ChunkWaterLayer.level(value);
    }

    /** Flow octant of a river value from {@link #getWaterValueAt}, or -1. */
    public static int riverFlowOf(int waterValue) {
        return waterValue >= 0 && ChunkWaterLayer.isRiver(waterValue) ? ChunkWaterLayer.octant(waterValue) : -1;
    }

    /** True when the block is WATER and a source. */
    public boolean isWaterSourceAt(long x, long y, long z) {
        return getWaterLevelAt(x, y, z) == ChunkWaterLayer.SOURCE;
    }

    /** Water cell state, or null when the position is not water. */
    public com.stonebreak.blocks.waterSystem.WaterBlock getWaterStateAt(long x, long y, long z) {
        int value = getWaterLevelAt(x, y, z);
        if (value < 0) {
            return null;
        }
        if (value == ChunkWaterLayer.FALLING) {
            return com.stonebreak.blocks.waterSystem.WaterBlock.falling(0);
        }
        return new com.stonebreak.blocks.waterSystem.WaterBlock(value, false);
    }

    /**
     * Generated water level at a column (sea, river, lake surface) from the generator, or
     * {@link WorldConfiguration#NO_WATER}; distinct from the live WATER layer.
     */
    public int getGeneratedWaterLevelAt(long x, long z) {
        if (cleanedUp) {
            return WorldConfiguration.NO_WATER;
        }
        return terrain().getWaterLevelAt(i(x), i(z));
    }

    // ===== snow (SNOW layer) ====================================================================================

    public SnowLayerManager getSnowLayerManager() {
        return snowLayerManager;
    }

    public int getSnowLayers(long x, long y, long z) {
        return snowLayerManager.getSnowLayers(x, y, z);
    }

    /** Visual/collision height of the block at a position (snow: its layer height). */
    public float getSnowHeight(long x, long y, long z) {
        BlockType block = getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return snowLayerManager.getSnowHeight(x, y, z);
        }
        return block.getVisualHeight();
    }

    // ===== remesh compat ========================================================================================

    /** No-op: block and meshed-layer writes mark cubes dirty natively and the stream re-meshes them. */
    public void triggerChunkRebuild(long worldX, long worldY, long worldZ) {
    }

    /** No-op: see {@link #triggerChunkRebuild}. */
    public void scheduleChunkRemeshAt(long x, long y, long z) {
    }

    /**
     * OWNER THREAD. Re-applies everything the mesher reads from the game (block table, fluid, face rules and, on a
     * display world, the unit-cube face textures - e.g. the leaf-transparency setting flips leaves between
     * cutout and opaque cubes) and re-meshes every installed cube ({@code CubeStream.remeshAll}: nearest first
     * through the normal budgets; meshes built from the old tables are dropped). The models stay (no setting
     * changes them).
     */
    public void rebuildAllLoadedChunks() {
        if (cleanedUp) {
            return;
        }
        if (isRenderOnly()) {
            CubeRenderAssets.reapply(voxels);
        } else {
            StonebreakBlockTable.apply(voxels);
        }
        CubeStream s = stream;
        if (s != null && !cleanedUp) {
            s.remeshAll();
        }
    }

    /** Settings-menu signature (the position no longer matters). */
    public void rebuildAllLoadedChunks(int playerChunkX, int playerChunkZ) {
        rebuildAllLoadedChunks();
    }

    // ===== systems ==============================================================================================

    public WaterSim getWaterSim() {
        return waterSim;
    }

    public LeafDecaySystem getLeafDecay() {
        return leafDecay;
    }

    public com.stonebreak.world.growth.SaplingGrowthSystem getSaplingGrowth() {
        return saplingGrowth;
    }

    public com.stonebreak.world.save.SaveService getSaveService() {
        return saveService;
    }

    public void setSaveService(com.stonebreak.world.save.SaveService saveService) {
        this.saveService = saveService;
    }

    public void setEntitySpawner(com.stonebreak.mobs.entities.EntitySpawner entitySpawner) {
        this.entitySpawner = entitySpawner;
    }

    public com.stonebreak.mobs.entities.EntitySpawner getEntitySpawner() {
        return entitySpawner;
    }

    public void setEntityManager(com.stonebreak.mobs.entities.EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** This world's entity manager if bound, else null (never substitute {@code Game.getEntityManager()}). */
    public com.stonebreak.mobs.entities.EntityManager getEntityManager() {
        return entityManager;
    }

    public void setPlayerLocator(com.stonebreak.mobs.entities.ai.behavior.PlayerLocator playerLocator) {
        this.playerLocator = playerLocator != null
            ? playerLocator : com.stonebreak.mobs.entities.ai.behavior.PlayerLocator.NONE;
    }

    public com.stonebreak.mobs.entities.ai.behavior.PlayerLocator getPlayerLocator() {
        return playerLocator;
    }

    public TerrainGeneratorType getGeneratorType() {
        return generatorType;
    }

    /**
     * The world's terrain (native generator + column queries). Server worlds build it eagerly (the stream
     * needs it); client and detached worlds on first use. Throws when the worldgen lib is unavailable.
     */
    public NativeTerrain terrain() {
        NativeTerrain t = terrain;
        if (t == null) {
            synchronized (terrainLock) {
                t = terrain;
                if (t == null) {
                    if (cleanedUp) {
                        throw new com.stonebreak.world.generation.TerrainClosedException("World is cleaned up");
                    }
                    t = NativeTerrain.create(seed, generatorType, terrainScale, climateLayout, heightCurve,
                        worldFeatures, standardShape, blockStates);
                    terrain = t;
                }
                if (cleanedUp) {
                    // cleanup() ran while this was being built (it takes the lock to hand the terrain over, so
                    // either it closed this one or it finished before the build): never hand out a dead world's
                    // terrain
                    throw new com.stonebreak.world.generation.TerrainClosedException("World is cleaned up");
                }
            }
        }
        return t;
    }

    /** The terrain if it was built, else null (never builds it). */
    public NativeTerrain terrainIfStarted() {
        return terrain;
    }

    public long getSeed() {
        return seed;
    }

    /** The seed is fixed at construction; kept for save compatibility (logs a mismatch). */
    public void setSeed(long seed) {
        if (this.seed != seed) {
            LOGGER.warn("Attempt to set seed {} on a world with seed {} ignored", seed, this.seed);
        }
    }

    public Vector3f getSpawnPosition() {
        return new Vector3f(spawnPosition);
    }

    public void setSpawnPosition(Vector3f newSpawnPosition) {
        this.spawnPosition.set(newSpawnPosition);
    }

    /** The saved spawn ({@code WorldData.getSpawnPosition()}, double since Phase 4); stored as float here. */
    public void setSpawnPosition(org.joml.Vector3dc newSpawnPosition) {
        this.spawnPosition.set((float) newSpawnPosition.x(), (float) newSpawnPosition.y(), (float) newSpawnPosition.z());
    }

    public void setSpawnPosition(float x, float y, float z) {
        this.spawnPosition.set(x, y, z);
    }

    public com.stonebreak.blocks.furnace.FurnaceStateRegistry getFurnaceRegistry() {
        return furnaceRegistry;
    }

    public com.stonebreak.blocks.workbench.WorkbenchStateRegistry getWorkbenchRegistry() {
        return workbenchRegistry;
    }

    public com.stonebreak.blocks.anim.AnimatedBlockRegistry getAnimatedBlockRegistry() {
        return animatedBlockRegistry;
    }

    public WorldConfiguration getConfig() {
        return config;
    }

    public void ensureFastLodManager(com.stonebreak.rendering.textures.BlockTextureArray textureArray) {
        fastLod.ensure(textureArray);
    }

    public FastLodManager getFastLodManager() {
        return fastLod.get();
    }

    /** Stream: meshes waiting for the hand-out budget (debug). */
    public int getPendingMeshBuildCount() {
        StreamStats s = streamStats();
        return s == null ? 0 : (int) (s.cubesMeshing() + s.meshesPending());
    }

    /** Stream: finished loads waiting for the install budget (debug). */
    public int getPendingGLUploadCount() {
        StreamStats s = streamStats();
        return s == null ? 0 : (int) s.installsPending();
    }

    public com.stonebreak.mobs.entities.ai.nav.PathfindingService pathfindingIfStarted() {
        return pathfinding;
    }

    public com.stonebreak.mobs.entities.ai.nav.PathfindingService pathfinding() {
        com.stonebreak.mobs.entities.ai.nav.PathfindingService service = pathfinding;
        if (service != null) {
            return service;
        }
        synchronized (pathfindingLock) {
            if (pathfinding == null && !cleanedUp) {
                pathfinding = com.stonebreak.mobs.entities.ai.nav.PathfindingService.forWorld(this);
            }
            return pathfinding;
        }
    }

    // ===== lifecycle ============================================================================================

    /** World switch on the main thread: closes the FastLOD cache (its SQLite files would stay locked). */
    public void clearWorldData() {
        fastLod.shutdownInline();
        spawnPosition.set(0, 100, 0);
    }

    /**
     * Releases everything: path searching, physics, the stream (saves and flushes when a store exists),
     * the store, the world, the generator. Call after every thread using the world stopped.
     */
    public void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        synchronized (pathfindingLock) {
            if (pathfinding != null) {
                pathfinding.close();
                pathfinding = null;
            }
        }
        synchronized (this) {
            if (physics != null) {
                physics.close();
                physics = null;
            }
        }
        fastLod.shutdownDeferred();
        try {
            if (stream != null) {
                if (store != null) {
                    // No save hooks here: their owners (entity manager) may already be torn down, and an
                    // empty snapshot would overwrite the saved entities. SaveService.close() runs them first.
                    for (Cube cube : new ArrayList<>(cubes.values())) {
                        furnaceRegistry.flushToWorld(cube);
                        workbenchRegistry.flushToWorld(cube);
                        writeBlob(cube);
                    }
                }
                // Table before the records that reference it; stream close = save everything + store flush.
                synchronized (commitLock) {
                    saveBlockStates();
                    stream.close();
                }
            } else {
                saveBlockStates();
            }
            if (store != null) {
                store.close();
            }
        } catch (RuntimeException e) {
            LOGGER.error("World stream/store shutdown failed", e);
        }
        if (isRenderOnly()) {
            CubeRenderAssets.detach(voxels);
        }
        cubes.clear();
        readyColumns.clear();
        voxels.close();
        // Last: the native generator. Its own gate refuses new queries and waits for the ones in flight (FastLOD
        // workers abandoned by an executor that did not stop in time, the weather relief thread, debug and spawn
        // queries) before freeing it, so no thread can run on a destroyed generator. Taken under the build lock so a
        // terrain() racing this cleanup is either closed here or refuses (cleanedUp is already set).
        NativeTerrain t;
        synchronized (terrainLock) {
            t = terrain;
            terrain = null;
        }
        if (t != null) {
            t.close();
        }
    }

    public boolean isCleanedUp() {
        return cleanedUp;
    }

    /**
     * True once {@link #cleanup()} started: the native world is (being) closed and every query answers its
     * closed default (AIR / {@link CubeWorld#MISSING} / MIN_Y / not loaded / not ready / no-op writes)
     * instead of reaching the freed {@link CubeWorld}. A per-tick system holding a world reference across a
     * world switch must skip a closed one.
     */
    public boolean isClosed() {
        return cleanedUp;
    }
}
