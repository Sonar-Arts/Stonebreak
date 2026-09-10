package com.stonebreak.world.generation;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.heightmap.RavineCarver;
import com.stonebreak.world.generation.heightmap.SinkholeCarver;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.generation.water.BasinCache;
import com.stonebreak.world.generation.water.NativeWaterTiles;

import java.util.BitSet;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.terrain.MobGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Random;

/**
 * Orchestrates per-chunk terrain and feature generation by delegating to focused subsystems.
 */
public class TerrainGenerationSystem {
    public static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    public static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final long seed;
    /** {@code outFloor} sentinel for a cell with no cave mouth; see {@link #sampleCellOpenings}. */
    public static final int NO_OPENING = Integer.MIN_VALUE;

    private final TerrainTileSource tileSource;
    private final HeightMapGenerator heightMapGenerator;
    private final BiomeManager biomeManager;
    private final OreGenerator oreGenerator;
    private final VegetationGenerator vegetationGenerator;
    private final SurfaceDecorationGenerator decorationGenerator;
    private final DeterministicRandom deterministicRandom;
    private final Density3D density3D;
    private final PerlinWormCarver wormCarver;
    private final CavernCarver cavernCarver;
    private final MegaCavernCarver megaCavernCarver;
    private final RavineCarver ravineCarver;
    private final SinkholeCarver sinkholeCarver;
    private final SurfaceProfileCache surfaceProfiles;

    private final Random animalRandom = new Random();
    private final Object animalRandomLock = new Object();

    public TerrainGenerationSystem(long seed) {
        this(withServicesRunning(seed), productionTileSource(seed));
    }

    /**
     * The production tile chain: the HTTP-backed bridge cache, wrapped — when the
     * native water backend is selected ({@code -Dstonebreak.water.backend=native},
     * the default) — in {@link NativeWaterTiles}, which stamps lakes per tile
     * from the depression fill {@link BasinCache} solves per region. With the
     * wrapper active
     * the bridge runs with its hydrological solve disabled (the process manager
     * sets {@code TERRAIN_BRIDGE_HYDROLOGY=0} from the same property), so raw
     * tiles carry sea-level-only water and cost sub-second GPU time instead of
     * the L0/L1 macro-region solves.
     */
    private static TerrainTileSource productionTileSource(long seed) {
        DiffusionBridgeConfig config = DiffusionBridgeConfig.fromSystemProperties();
        DiffusionTileCache rawTiles = new DiffusionTileCache(config, seed);
        if (!NativeWaterTiles.nativeBackendSelected()) {
            return rawTiles;
        }
        return new NativeWaterTiles(rawTiles, BasinCache.production(config, seed), seed,
            config.tileSizeBlocks(), config.maxCachedTiles());
    }

    /**
     * Blocks until the local terrain-diffusion services are up and pinned to {@code seed}
     * (starting/restarting them if needed — see {@link TerrainServiceProcessManager}), then
     * returns the seed unchanged. A pass-through so it can sit in the constructor-delegation
     * chain above without a separate init block.
     */
    private static long withServicesRunning(long seed) {
        TerrainServiceProcessManager.getInstance().ensureRunningForSeed(seed);
        return seed;
    }

    /**
     * Test-only seam: injects a fake {@link TerrainTileSource} instead of the
     * real HTTP-backed bridge client, so terrain-shape logic (cave carving,
     * mesh consistency, etc.) can be exercised offline. Production code must
     * always go through {@link #TerrainGenerationSystem(long)} — no fallback
     * path, see plan.md Phase 2.
     */
    TerrainGenerationSystem(long seed, TerrainTileSource tileSource) {
        this.seed = seed;
        this.tileSource = tileSource;
        this.deterministicRandom = new DeterministicRandom(seed);
        this.heightMapGenerator = new HeightMapGenerator(tileSource);
        this.biomeManager = new BiomeManager(tileSource);
        this.oreGenerator = new OreGenerator(deterministicRandom, heightMapGenerator, seed);
        this.vegetationGenerator = new VegetationGenerator(deterministicRandom);
        this.decorationGenerator = new SurfaceDecorationGenerator(deterministicRandom, heightMapGenerator, seed);
        this.density3D = new Density3D(seed, heightMapGenerator);
        this.wormCarver = new PerlinWormCarver(seed, heightMapGenerator);
        this.cavernCarver = new CavernCarver(seed, heightMapGenerator);
        this.megaCavernCarver = new MegaCavernCarver(seed, heightMapGenerator);
        this.ravineCarver = new RavineCarver(seed, heightMapGenerator);
        this.sinkholeCarver = new SinkholeCarver(seed, heightMapGenerator);
        this.wormCarver.setCavernCarver(cavernCarver);
        this.wormCarver.setMegaCavernCarver(megaCavernCarver);
        // Lets a sinkhole cut to exactly the depth that opens into a real tunnel.
        this.sinkholeCarver.setWormCarver(wormCarver);
        this.surfaceProfiles = new SurfaceProfileCache(this::buildSurfaceProfile);
    }

    /**
     * Releases whatever the tile chain holds open — with the native water
     * backend that is {@link NativeWaterTiles}, and through it the
     * {@link BasinCache}'s escalation and prefetch threads.
     *
     * <p>Called from {@code World.cleanup()}. Nothing here is required for a
     * clean JVM exit (the threads are daemons), but a session that loads
     * several worlds leaked a pair of them and a region cache every time.
     */
    public void shutdown() {
        if (tileSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                System.err.println("[TerrainGenerationSystem] tile source close failed: " + e);
            }
        }
    }

    public long getSeed() {
        return seed;
    }

    public BiomeType getBiomeAt(int x, int z) {
        return biomeManager.getBiome(x, z);
    }

    /** Continentalness-only base height (debug). */
    public int getBaseHeightAt(int x, int z) {
        return heightMapGenerator.baseHeight(x, z);
    }

    /** Shape with PV/erosion, no surface detail (debug). */
    public int getShapedHeightAt(int x, int z) {
        return heightMapGenerator.shapedHeight(x, z);
    }

    /** Final terrain height as used by chunk generation. */
    public int getFinalTerrainHeightAt(int x, int z) {
        return heightMapGenerator.generateHeight(x, z);
    }

    /** Water level as used by chunk generation, or {@link TerrainTile#NO_WATER}. */
    public int getWaterLevelAt(int x, int z) {
        return heightMapGenerator.waterLevel(x, z);
    }

    /**
     * One past the highest solid block of a column — the surface a heightfield renderer has
     * to draw. Equals {@link #getFinalTerrainHeightAt} wherever nothing cut the top off the
     * column, and sits below it inside a ravine, a sinkhole, a breached cavern or the
     * {@code Density3D} overhang band. Backed by a per-chunk cache; see
     * {@link #buildSurfaceProfile}.
     */
    public int carvedSurfaceHeight(int worldX, int worldZ) {
        SurfaceProfileCache.Profile profile = surfaceProfiles.get(
                Math.floorDiv(worldX, CHUNK_SIZE), Math.floorDiv(worldZ, CHUNK_SIZE));
        return profile.surfaceY()[
                Math.floorMod(worldX, CHUNK_SIZE) * CHUNK_SIZE + Math.floorMod(worldZ, CHUNK_SIZE)];
    }

    /**
     * Per-cell cave-mouth geometry for a FastLOD node, aggregated over each cell's whole
     * {@code cellSize x cellSize} footprint rather than point-probed like the heights.
     *
     * <p>This is the channel that makes caves other than ravines visible past the finest LOD
     * band. Heights are one representative probe per cell, so an opening only survives
     * coarsening if the probe happens to land in it: measured hit rates are 79% at L1, 54% at
     * L2, 33% at L3 and 15% at L4. Ravines are long and wide enough to be hit anyway, which is
     * why they were the only thing left in the distance; a worm mouth or a sinkhole a few
     * blocks across was not.
     *
     * <p>Aggregating the <em>heights</em> instead is the obvious fix and it is wrong — taking
     * the minimum over each cell reports a mean carve of 32 blocks at L4 against a true mean
     * of 2.8, which would gouge trenches across open terrain. So the surface keeps its point
     * sample and the opening rides alongside it as its own channel, for the mesher to draw as
     * a recessed notch inside the cell.
     *
     * <p>Costs only cached reads: every column consulted here is one
     * {@link #carvedSurfaceHeight} already built for this chunk's profile.
     *
     * @param cellHeights  the per-cell surface heights already sampled, {@code [ix*cells+iz]}
     * @param outFloor     per cell: lowest carved floor in the footprint, or
     *                     {@link #NO_OPENING} when nothing in the cell is carved below
     *                     {@code cellHeights}
     * @param outCoverage  per cell: carved share of the footprint, 0..255
     */
    public void sampleCellOpenings(int worldX0, int worldZ0, int cellsPerAxis, int cellSize,
                                   int[] cellHeights, int[] outFloor, byte[] outCoverage) {
        for (int ix = 0; ix < cellsPerAxis; ix++) {
            for (int iz = 0; iz < cellsPerAxis; iz++) {
                int cell = ix * cellsPerAxis + iz;
                int shown = cellHeights[cell];
                int floor = Integer.MAX_VALUE;
                int carvedCount = 0;
                for (int dx = 0; dx < cellSize; dx++) {
                    for (int dz = 0; dz < cellSize; dz++) {
                        int wx = worldX0 + ix * cellSize + dx;
                        int wz = worldZ0 + iz * cellSize + dz;
                        SurfaceProfileCache.Profile p = surfaceProfiles.get(
                                Math.floorDiv(wx, CHUNK_SIZE), Math.floorDiv(wz, CHUNK_SIZE));
                        int local = Math.floorMod(wx, CHUNK_SIZE) * CHUNK_SIZE
                                  + Math.floorMod(wz, CHUNK_SIZE);
                        if (!p.carved()[local]) {
                            continue;   // downhill ground is not a cave mouth
                        }
                        carvedCount++;
                        floor = Math.min(floor, p.surfaceY()[local]);
                    }
                }
                // Only an opening that reaches BELOW the surface being drawn is worth a
                // notch. At L0 the cell is one column, so its own carve is already in the
                // height and this is always false — the finest level emits nothing.
                if (carvedCount == 0 || floor >= shown) {
                    outFloor[cell] = NO_OPENING;
                    outCoverage[cell] = 0;
                } else {
                    outFloor[cell] = floor;
                    int cells = cellSize * cellSize;
                    outCoverage[cell] = (byte) Math.min(255, Math.max(1, carvedCount * 255 / cells));
                }
            }
        }
    }

    /**
     * Returns the surface block a column places at its terrain top
     * ({@code y == height - 1}), derived from the same biome rules as
     * {@link #determineBlockType}. Submerged columns report their real seabed
     * block — {@code determineBlockType} places {@code surfaceBlock(biome)}
     * there just like on land — so a renderer can draw the ocean floor;
     * submergence itself is decided per-column from
     * {@code waterLevel > height} (see {@link HeightMapGenerator#waterLevel}),
     * not from a global {@code SEA_LEVEL} — the ocean is one case of that,
     * not a separate rule.
     */
    public BlockType getSurfaceBlockAt(int worldX, int worldZ) {
        return surfaceBlock(biomeManager.getBiome(worldX, worldZ));
    }

    /** Deterministic RNG for shared probing logic (tree placement, etc.). */
    public DeterministicRandom getDeterministicRandom() {
        return deterministicRandom;
    }

    /**
     * Probes whether a tree would be placed at this column, without mutating any
     * chunk.
     *
     * <p>Note this reports off the raw column height, so it says nothing about whether the
     * ground under the tree survived carving. FastLOD needs that and gets it from
     * {@link #sampleColumns}, which suppresses trees on a carved top the same way the real
     * {@code VegetationGenerator} does by reading the block.
     */
    public com.stonebreak.world.generation.features.VegetationGenerator.TreeSample getTreeAt(int worldX, int worldZ) {
        int height = heightMapGenerator.generateHeight(worldX, worldZ);
        if (heightMapGenerator.waterLevel(worldX, worldZ) > height) return null;
        BlockType surface = surfaceBlock(biomeManager.getBiome(worldX, worldZ));
        BiomeType biome = biomeManager.getBiome(worldX, worldZ);
        return com.stonebreak.world.generation.features.VegetationGenerator.probeTree(
                worldX, worldZ, biome, surface, deterministicRandom);
    }

    /**
     * Batched column probe for coarse samplers (FastLOD): fills heights and,
     * optionally, surface blocks / tree samples for a {@code count x count}
     * grid at block positions {@code (worldX0 + ix*stride, worldZ0 + iz*stride)},
     * indexed {@code [ix*count + iz]}.
     *
     * <p>Heights are the <em>carved</em> surface — one past the highest solid block, see
     * {@link #buildSurfaceProfile} — not the raw tile height {@link #getFinalTerrainHeightAt}
     * reports. A heightfield renderer that used the raw height drew ravines and sinkholes as
     * flat ground, sealed the cut with a dark foundation wall at the node border, and popped a
     * trench into place the moment the real chunk loaded. Surface blocks and trees follow from
     * the same carved top: a cut column exposes stone rather than a sheet of grass, and grows
     * no tree, exactly as {@code VegetationGenerator} finds when it reads the real block.
     *
     * <p>On the diffusion generator the batching win lives one layer down: the
     * heights come from bridge tiles that {@code DiffusionTileCache} already
     * serves whole, so a straight per-column loop touches each tile once and
     * needs no separate grid-fill path. The carve profiles behind it are cached per chunk,
     * which is what keeps a grid that spills into its neighbours from rebuilding their masks.
     *
     * @param outWaterLevels nullable; length count*count when present. Also filled
     *                       internally (whether or not the caller wants it) when
     *                       {@code outTrees} is present, since tree suppression needs it.
     * @param outSurface nullable; length count*count when present
     * @param outTrees   nullable; length count*count when present
     */
    public void sampleColumns(int worldX0, int worldZ0, int count, int stride,
                              int[] outHeights,
                              int[] outWaterLevels,
                              BlockType[] outSurface,
                              com.stonebreak.world.generation.features.VegetationGenerator.TreeSample[] outTrees) {
        boolean needBiomes = outSurface != null || outTrees != null;
        boolean needWater = outWaterLevels != null || outTrees != null;
        for (int ix = 0; ix < count; ix++) {
            for (int iz = 0; iz < count; iz++) {
                int idx = ix * count + iz;
                int wx = worldX0 + ix * stride;
                int wz = worldZ0 + iz * stride;
                int rawHeight = heightMapGenerator.generateHeight(wx, wz);
                int height = carvedSurfaceHeight(wx, wz);
                outHeights[idx] = height;
                int waterLevel = needWater ? heightMapGenerator.waterLevel(wx, wz) : TerrainTile.NO_WATER;
                if (outWaterLevels != null) {
                    outWaterLevels[idx] = waterLevel;
                }
                if (!needBiomes) {
                    continue;
                }
                // Biome is resolved for submerged columns too — their surface
                // is the real seabed block (see getSurfaceBlockAt).
                BiomeType biome = biomeManager.getBiome(wx, wz);
                BlockType surface = exposedBlock(rawHeight, height, biome);
                if (outSurface != null) {
                    outSurface[idx] = surface;
                }
                if (outTrees != null) {
                    // The real generator reads the block at rawHeight - 1 and every placement
                    // branch needs it to be the biome's surface block. Carved away, it is air
                    // and nothing is planted — so a carved top is exactly a treeless column.
                    outTrees[idx] = (waterLevel > rawHeight || height != rawHeight) ? null
                        : com.stonebreak.world.generation.features.VegetationGenerator.probeTree(
                            wx, wz, biome, surface, deterministicRandom);
                }
            }
        }
    }

    /**
     * Result of terrain-only generation: the chunk plus the column profile
     * (heights + biomes) so deferred feature population can reuse it instead
     * of resampling the noise stack.
     */
    public record TerrainResult(Chunk chunk, ColumnProfile profile) {}

    /**
     * Generates terrain blocks for a chunk. Features are populated separately
     * once neighbor chunks exist (prevents recursive generation across chunk borders).
     */
    public TerrainResult generateTerrainOnly(int chunkX, int chunkZ) {
        long startNanos = System.nanoTime();
        try {
            return generateTerrainOnlyTimed(chunkX, chunkZ);
        } finally {
            // The F3 terrain counter had been dead since the diffusion rewrite dropped
            // this call — every cave tuning decision below is measured against it.
            TerrainGenStats.record(System.nanoTime() - startNanos,
                TerrainNoise.backend() == TerrainNoise.Backend.NATIVE
                    ? TerrainGenStats.Mode.MIXED
                    : TerrainGenStats.Mode.JAVA);
        }
    }

    private TerrainResult generateTerrainOnlyTimed(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Base Terrain Shape");

        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] waterLevels = new int[CHUNK_SIZE * CHUNK_SIZE];
        // Where a river passes under standing ground it tunnels instead of levelling
        // it, so the column's height is the hill and the river is in here. Local
        // rather than on ColumnProfile: only this loop and the cave guard need them,
        // and the feature pass correctly plants on the hilltop.
        int[] riverFloors = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] riverRoofs = new int[CHUNK_SIZE * CHUNK_SIZE];
        BiomeType[] biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];

        // Shape first (noise-driven), then skin with biomes. Biomes do not influence shape.
        heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights, waterLevels,
                riverFloors, riverRoofs);
        updateLoadingProgress("Determining Biomes");
        biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);

        updateLoadingProgress("Applying Biome Materials");
        CarveMasks masks = buildCarveMasks(chunkX, chunkZ, heights, waterLevels, riverFloors);
        BitSet caveMask = masks.caveMask();
        BitSet formationMask = masks.formationMask();

        // Write terrain into paletted storage directly instead of 65k
        // chunk.setBlock calls (each of which churns dirty flags, per-block
        // state removal, and incremental heightmap updates). AIR cells are
        // skipped entirely — sections above the terrain stay in their
        // near-free uniform tier. The caller recomputes the heightmap once.
        CcoBlockStorage storage = CcoFactory.createEmptyStorage(BlockType.AIR);
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        // Three SIMD volume fills instead of a per-block simplex sample per solid cell. With
        // a surface near y=400 the per-point path costs ~100k Java noise samples per chunk;
        // this path was written for exactly that and had simply never been called. Null on
        // the Java backend, where determineBlockType falls back to per-point isSolid.
        Density3D.Field densityField =
                density3D.prepareChunk(chunkX, chunkZ, heights, waterLevels, riverFloors, riverRoofs);
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                int height = heights[idx];
                int waterLevel = waterLevels[idx];
                int riverFloor = riverFloors[idx];
                int riverRoof = riverRoofs[idx];
                BiomeType biome = biomes[idx];
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    int bit = LocalBlockKey.pack(x, y, z);
                    BlockType block;
                    if (riverRoof > riverFloor && y >= riverFloor && y <= riverRoof) {
                        // A river running under standing ground. This has to beat every
                        // mask below it: a cavern or worm that wins one of these cells
                        // opens the passage, and worldgen water is a source block, so the
                        // river would then drain forever.
                        //
                        // The branch owns the SHELL as well as the void. `riverFloor` is
                        // the bed the kernel cut and `riverRoof` the lid it left, and
                        // neither is safe by being underground: unlike a surface riverbed
                        // — which IS the column's surface height, so nothing carves it —
                        // a tunnel bed sits tens of blocks down, in the middle of cave
                        // country. Density3D is the one carver WaterGuard does not cover
                        // (it reads noise and depth, no water plane), and it was hollowing
                        // the bed out from underneath.
                        block = (y == riverFloor || y == riverRoof)
                                ? BlockType.STONE
                                : (y < waterLevel ? BlockType.WATER : BlockType.AIR);
                    } else if (y > 0 && y < height && formationMask.get(bit)) {
                        block = BlockType.STONE;
                    } else if (y > 0 && y < height && caveMask.get(bit)) {
                        continue; // carved to air — already the uniform fill
                    } else {
                        block = determineBlockType(worldX, y, worldZ, height, waterLevel, biome,
                                densityField, x, z);
                    }
                    if (block != BlockType.AIR) {
                        storage.set(x, y, z, block);
                    }
                }
            }
        }

        Chunk chunk = new Chunk(chunkX, chunkZ, storage);
        // One mesh+data dirty mark replaces the per-setBlock marks. The caller
        // clears data-dirty for waterless chunks, exactly as before.
        chunk.getCcoDirtyTracker().markBlockChanged();
        chunk.setFeaturesPopulated(false);
        return new TerrainResult(chunk, new ColumnProfile(heights, biomes, waterLevels));
    }

    /**
     * The masks that decide solidity for one chunk, in the order the block loop
     * applies them: a river tunnel beats everything, then {@code formationMask}
     * beats {@code caveMask}, and {@code caveMask} beats the {@code Density3D}
     * test in {@link #determineBlockType}.
     */
    private record CarveMasks(BitSet caveMask, BitSet formationMask) {}

    /**
     * Builds both masks for a chunk. Shared by real generation and by
     * {@link #buildSurfaceProfile} so the surface FastLOD draws cannot drift from the
     * surface the block loop writes — a drift that showed up as ravines rendering flat at
     * distance and then popping into a trench at the chunk seam.
     */
    private CarveMasks buildCarveMasks(int chunkX, int chunkZ, int[] heights, int[] waterLevels,
                                       int[] riverFloors) {
        BitSet caveMask = wormCarver.carveMaskForChunk(chunkX, chunkZ, heights, waterLevels, riverFloors);
        CavernCarver.Result cavernResult =
                cavernCarver.buildForChunk(chunkX, chunkZ, heights, waterLevels, riverFloors);
        MegaCavernCarver.Result megaCavernResult =
                megaCavernCarver.buildForChunk(chunkX, chunkZ, heights, waterLevels, riverFloors);
        caveMask.or(cavernResult.carveMask);
        caveMask.or(megaCavernResult.carveMask);
        // Entrances. Unlike the carvers above, these two are anchored to the surface and cut
        // downward, so they open the network to the sky by construction rather than by luck.
        caveMask.or(ravineCarver.carveMaskForChunk(chunkX, chunkZ, heights, waterLevels, riverFloors));
        caveMask.or(sinkholeCarver.carveMaskForChunk(chunkX, chunkZ, heights, waterLevels, riverFloors));
        BitSet formationMask = cavernResult.formationMask;
        formationMask.or(megaCavernResult.formationMask);
        return new CarveMasks(caveMask, formationMask);
    }

    /**
     * The visible top of every column of a chunk — one past the highest solid block —
     * indexed {@code [x * CHUNK_SIZE + z]}.
     *
     * <p>This is what a heightfield view of the world (FastLOD) has to draw. The raw tile
     * height is only where the column <em>starts</em>; a ravine or sinkhole mouth, a cavern
     * that broke through, or the {@code Density3D} overhang band can all take the top off it,
     * and a formation can put stone back. Descending here with the same predicate the block
     * loop uses is what keeps the two agreeing.
     *
     * <p>The descent goes through {@link Density3D#prepareChunk} — the same prepared field
     * {@link #generateTerrainOnly} reads — not the per-point {@link Density3D#isSolid}.
     * That is not an optimisation, it is the parity requirement. On the native backend the
     * two are different noise implementations, not two spellings of one: {@code isSolid}
     * samples the Java simplex generator while the prepared field is a FastNoise2 SIMD
     * volume fill. Descending on the per-point path made FastLOD draw a surface derived from
     * noise the chunk loop never consults, and the two disagreed on 0.79% of columns by as
     * much as 16 blocks — LOD terrain standing above the chunk that replaces it, which pops
     * away (and opens a hole in what it was occluding) the moment the chunk loads.
     * {@code prepareChunk} returns null on the Java backend, where per-point {@code isSolid}
     * IS what the block loop uses, so the fallback below is the parity-preserving path there.
     */
    private SurfaceProfileCache.Profile buildSurfaceProfile(int chunkX, int chunkZ) {
        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] waterLevels = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] riverFloors = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] riverRoofs = new int[CHUNK_SIZE * CHUNK_SIZE];
        BiomeType[] biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];
        heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights, waterLevels,
                riverFloors, riverRoofs);
        biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);

        // The same masks the block loop builds, from the same planes — the parity
        // contract below is only worth anything if the inputs match too.
        CarveMasks masks = buildCarveMasks(chunkX, chunkZ, heights, waterLevels, riverFloors);
        BitSet caveMask = masks.caveMask();
        BitSet formationMask = masks.formationMask();
        Density3D.Field densityField =
                density3D.prepareChunk(chunkX, chunkZ, heights, waterLevels, riverFloors, riverRoofs);

        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        int[] profile = new int[CHUNK_SIZE * CHUNK_SIZE];
        boolean[] carved = new boolean[CHUNK_SIZE * CHUNK_SIZE];
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                int height = heights[idx];
                BiomeType biome = biomes[idx];
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int y = height - 1;
                // y == 0 is always BEDROCK, so this terminates with profile >= 1 — the same
                // floor HeightMapGenerator.clampToWorld imposes on the raw height.
                while (y > 0) {
                    int bit = LocalBlockKey.pack(x, y, z);
                    if (formationMask.get(bit)) {
                        break;
                    }
                    boolean solid = (densityField != null)
                            ? densityField.isSolid(x, y, z, height, biome)
                            : density3D.isSolid(worldX, y, worldZ, height, biome);
                    if (caveMask.get(bit) || !solid) {
                        y--;
                        continue;
                    }
                    break;
                }
                profile[idx] = y + 1;
                carved[idx] = profile[idx] < height;
            }
        }
        return new SurfaceProfileCache.Profile(profile, carved);
    }

    /**
     * The block exposed at the top of a column that has been carved down from
     * {@code rawHeight} to {@code carvedHeight}, matching the material branches of
     * {@link #determineBlockType}. Uncarved columns get the biome's surface block as
     * before; a ravine wall gets stone rather than a sheet of grass laid over the cut.
     */
    private static BlockType exposedBlock(int rawHeight, int carvedHeight, BiomeType biome) {
        int y = carvedHeight - 1;
        if (y < rawHeight - 4) {
            return BlockType.STONE;
        }
        if (y < rawHeight - 1) {
            return subsurfaceBlock(biome);
        }
        return surfaceBlock(biome);
    }

    /**
     * Populates features (ores, vegetation, decorations, mobs) on an already-terrained chunk.
     * Caller must ensure neighbor chunks at (+1,0), (0,+1), (+1,+1) exist.
     *
     * @param profile Column profile from terrain generation, or null to recompute
     *                (e.g., for chunks loaded from disk that still need features)
     */
    public void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager,
                                          ColumnProfile profile) {
        if (chunk == null || chunk.areFeaturesPopulated()) {
            return;
        }

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        if (!verifyNeighborsExist(world, chunkX, chunkZ)) {
            System.err.println("WARNING: populateChunkWithFeatures called before neighbors ready for chunk (" +
                chunkX + ", " + chunkZ + "). Skipping feature population.");
            return;
        }

        updateLoadingProgress("Adding Surface Decorations & Details");

        int[] heights;
        BiomeType[] biomes;
        int[] waterLevels;
        if (profile != null) {
            // Reuse the profile computed during terrain generation — skips a
            // full noise resampling pass per chunk.
            heights = profile.heights();
            biomes = profile.biomes();
            waterLevels = profile.waterLevels();
        } else {
            // Chunk loaded from disk with no profile carried over — recompute
            // both planes from the same resolved tile, same as terrain generation.
            heights = new int[CHUNK_SIZE * CHUNK_SIZE];
            biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];
            waterLevels = new int[CHUNK_SIZE * CHUNK_SIZE];
            heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights, waterLevels);
            biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);
        }
        BiomeType dominantBiome = biomes[(CHUNK_SIZE / 2) * CHUNK_SIZE + (CHUNK_SIZE / 2)];

        ChunkGenerationContext ctx = new ChunkGenerationContext(
            world, chunk, snowLayerManager, heights, biomes, waterLevels, dominantBiome);

        oreGenerator.generate(ctx);
        vegetationGenerator.generate(ctx);
        decorationGenerator.generate(ctx);

        // Passive-mob population is owned entirely by EntitySpawner, which is a
        // continuous, visibility-capped cycle (its "single source of truth").
        // The old per-chunk generation spawn below was a second, UNCAPPED path:
        // it rolled a fresh herd for every generated plains chunk with no global
        // cap or density check (and wrote to a different EntityManager than the
        // cap sweep counts against), flooding the world with animals as the
        // player explored. Disabled so EntitySpawner is the sole spawner.
        // MobGenerator.processChunkMobSpawning(world, chunk, dominantBiome, animalRandom, animalRandomLock);

        chunk.setFeaturesPopulated(true);
    }

    /**
     * @param waterLevel first y that is not water in this column, or
     *                   {@link com.stonebreak.world.generation.diffusion.TerrainTile#NO_WATER}
     */
    private BlockType determineBlockType(int worldX, int y, int worldZ, int height,
                                         int waterLevel, BiomeType biome,
                                         Density3D.Field densityField, int localX, int localZ) {
        if (y == 0) {
            return BlockType.BEDROCK;
        }
        boolean carved = densityField != null
                ? !densityField.isSolid(localX, y, localZ, height, biome)
                : !density3D.isSolid(worldX, y, worldZ, height, biome);
        if (y < height && carved) {
            return BlockType.AIR;
        }
        if (y < height - 4) {
            if (biome == BiomeType.RED_SAND_DESERT && y < height - 10 &&
                deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "magma", 0.6f)) {
                return BlockType.MAGMA;
            }
            return BlockType.STONE;
        }
        if (y < height - 1) {
            return subsurfaceBlock(biome);
        }
        if (y < height) {
            return surfaceBlock(biome);
        }
        // Was `y < SEA_LEVEL`. The ocean is now one case of a per-column water surface
        // rather than a global constant, so a river or a lake several hundred blocks up
        // places water by exactly the same rule the sea always did.
        //
        // The old `RED_SAND_DESERT && height > SEA_LEVEL` branch that sat here is gone
        // rather than ported: it was unreachable. Getting this far needs `y >= height`,
        // since every earlier branch covers `y < height`; combined with `height > SEA_LEVEL`
        // that gives `y >= height > SEA_LEVEL`, which contradicts `y < SEA_LEVEL`.
        if (y < waterLevel) {
            return BlockType.WATER;
        }
        return BlockType.AIR;
    }

    private static BlockType subsurfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case RED_SAND_DESERT, BADLANDS -> BlockType.RED_SANDSTONE;
            case DESERT, BEACH -> BlockType.SANDSTONE;
            case PLAINS, SNOWY_PLAINS, TAIGA, MEADOW, TUNDRA -> BlockType.DIRT;
            case STONY_PEAKS -> BlockType.STONE;
            case ICE_FIELDS -> BlockType.ICE;
        };
    }

    private static BlockType surfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case DESERT, BEACH -> BlockType.SAND;
            case RED_SAND_DESERT, BADLANDS -> BlockType.RED_SAND;
            case PLAINS, MEADOW -> BlockType.GRASS;
            case SNOWY_PLAINS, TAIGA, TUNDRA -> BlockType.SNOWY_DIRT;
            case STONY_PEAKS -> BlockType.STONE;
            case ICE_FIELDS -> BlockType.ICE;
        };
    }

    private boolean verifyNeighborsExist(World world, int chunkX, int chunkZ) {
        return world.hasChunkAt(chunkX + 1, chunkZ) &&
               world.hasChunkAt(chunkX, chunkZ + 1) &&
               world.hasChunkAt(chunkX + 1, chunkZ + 1);
    }

    private void updateLoadingProgress(String stageName) {
        Game game = Game.getInstance();
        if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
            game.getLoadingScreen().updateProgress(stageName);
        }
    }
}
