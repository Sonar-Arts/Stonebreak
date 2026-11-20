package com.stonebreak.world.generation;

import com.openmason.engine.cenda.NativeLibraryLocator;
import com.stonebreak.blocks.BlockType;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Thin FFM binding to the native cube generator ({@code libstonebreak_worldgen}, SBW ABI 5 (#446; 4 = profile carve codes; 5 = surface channels)): the
 * whole Stonebreak world generator behind Open Mason's {@code cv_generator}. Layouts and semantics per
 * {@code stonebreak-game/native/include/stonebreak/worldgen.h}.
 *
 * <p>{@link #generator()} is the {@code const cv_generator*} the engine's voxel streamer takes
 * ({@code cv_stream_desc.generator}); the engine calls it on its own workers, nothing crosses back into
 * Java except the column-profile upcall of {@link Kind#PROFILE} worlds (diffusion / TGM terrain). The
 * FastLOD samplers mirror {@code TerrainGenerator.sampleColumns / sampleRawColumns /
 * sampleCellOpenings / probeCellTrees}.
 *
 * <p>Not wired into the game yet (#447 adopts it). Block ids come from the runtime registry
 * ({@link #registryBlockIds()}), never from constants.
 */
public final class NativeCubeGenerator implements AutoCloseable {

    /** SBW ABI this binding speaks (5: the column profile's surface channels). */
    public static final int EXPECTED_ABI = 5;
    /** Water level of a dry column. */
    public static final int NO_WATER = Integer.MIN_VALUE;
    public static final int FLAG_PEAK_DAMP = 0x1, FLAG_DEEP_LAYERS = 0x2, FLAG_FEATURES = 0x4;
    /** PROFILE kind: ravines and sinkholes follow the profiles' carve codes (SBW ABI 4), not the hash. */
    public static final int FLAG_PROFILE_CARVE = 0x8;
    /**
     * PROFILE kind: the ocean floor's surface block follows depth, slope and shelter (sand shallows, gravel on swept or
     * steep floors, clay in deep or sheltered water, rock on cliffed coasts) instead of ~97 % sand (2026-11-19; set for
     * height-curve v2 DaedalusTGM-Exp worlds, so saved v1 worlds keep their sea bed).
     */
    public static final int FLAG_SEA_FLOOR = 0x10;
    /**
     * PROFILE kind: population places boulders - pebbles to glacial erratics, boulder fields, scree aprons - with shapes
     * from the procedural source and the rock bank {@link Config#rockBank} (2026-11-19; set per world by
     * {@link WorldFeatureVersion}, so saved worlds never grow boulders beside terrain generated without them).
     */
    public static final int FLAG_BOULDERS = 0x20;
    /**
     * PROFILE kind: dry land's surface block follows the terrain model's surface-detail channels (rock exposure, scree,
     * roughness; 1 m lidar-trained, agent L 2026-11-19): bare rock, talus gravel / cobble, broken ground. A no-op where
     * a tile carries no such channel (models without {@code surface.pt}); set per world by {@link WorldFeatureVersion}
     * (V2+), so saved worlds never change surface materials at their explored edge.
     */
    public static final int FLAG_LAND_SURFACE = 0x40;
    /**
     * PROFILE kind: trees (density, size, clearings) and ground vegetation (bushes, grass, flowers, bare and wet-meadow
     * patches) follow the terrain model's vegetation channels (SBW ABI 5 surface slots 0..7; agent V 2026-11-19). A
     * no-op where a tile carries no such channel (models without {@code veg.pt}); set per world by
     * {@link WorldFeatureVersion} (V3+), so saved worlds never change vegetation at their explored edge.
     */
    public static final int FLAG_VEGETATION = 0x80;
    /**
     * With {@link #FLAG_VEGETATION}: open the land up - big meadow patches by biome on wooded land, a steeper cover-to-density curve - so
     * temperate land is not ~90 % closed forest (2026-11-20); set per world by {@link WorldFeatureVersion} (V4+).
     */
    public static final int FLAG_VEG_OPEN_LAND = 0x100;
    public static final int SAMPLE_CARVED = 1;
    private static final int CONFIG_SIZE = 368;
    private static final int PROFILE_SIZE = 36864;
    /** Offset of {@code sbw_column_profile.surface[16][1024]} (SBW ABI 5). */
    private static final int PROFILE_SURFACE_OFFSET = 20480;
    /** Surface channel slots per column profile ({@code SBW_SURFACE_SLOTS}). */
    public static final int SURFACE_SLOTS = 16;
    private static final int COLUMN_INFO_SIZE = 40;

    /** Generator kinds (worldgen.h SBW_KIND_*). */
    public enum Kind { STANDARD, PROFILE }

    /** Block roles in {@code sbw_block} order; names are the {@link BlockType} names they map to. */
    public enum Block {
        AIR, WATER, STONE, BEDROCK, MAGMA, DIRT, GRASS, SAND, RED_SAND, SANDSTONE, RED_SANDSTONE,
        GRAVEL, CLAY, SNOWY_DIRT, SWAMPY_GRASS, ICE, SNOW, COAL_ORE, IRON_ORE, CRYSTAL, LIMESTONE,
        LIMESTONE_STALAGMITE, WOOD, LEAVES, ELM_WOOD_LOG, ELM_LEAVES, PINE, PINE_LEAVES, CYPRESS_LOG,
        CYPRESS_LEAVES, ROSE, WILDGRASS, DANDELION, CACTUS, RED_SAND_COBBLESTONE,
        /* 2026-11-19, boulders */
        COBBLESTONE, SAND_COBBLESTONE
    }

    /**
     * Terrain scale of a world: metres per block horizontally and the height curve's metres per block.
     * The distilled shape was fitted at 1:4; 1:2 is that shape at twice the size (a refit is optional).
     *
     * <p>Two views of the height curve: {@link #curveRates()} / {@link #curveKnots()} are the v1 curve the native
     * Standard generator uses (never changes: Standard worlds keep their terrain), and {@link #heightCurve} is the
     * full curve of a DaedalusTGM-Exp world of a given {@link HeightCurveVersion}, as TGMPipe reads it. A preset gives
     * its v1 rates (+ optional own knots) and its v2 sea ({@link SeaBandsV2}); a new preset adds a constant here and the
     * mirror in {@code world_config.CURVE_V2}.
     */
    public enum TerrainScalePreset {
        /**
         * 60 m blocks (today's world). v2 sea (round 2, sweep vs 16 real coasts): shallows 4 m per block to ~-24 m,
         * shelf 6 to ~-300 m, deep ocean 32 (v1: 48): coastal seas 5 / 12 / 20 / 38 blocks deep at 0-0.5 / 0.5-2 / 2-5 /
         * 5-15 km, 6.7 % of the coastal sea under 3 blocks, 38 % block-flat (v1: 62 %); trench -9.7 km at y -275.
         */
        ONE_TO_FOUR(60.0, new double[]{48.0, 16.0, 24.0, 38.0}, SeaBandsV2.scaled(1.0, 32.0)),
        /**
         * 30 m blocks (the terrain model's native pixels); rates halved, same exaggeration. v2 sea: half of 1:4's
         * shallows and shelf rates; deep ocean 32 so the deepest trench (-9.7 km) lands at y -312 (24 would put it on
         * the -384 floor; 40 left the far sea block-flat).
         */
        ONE_TO_TWO(30.0, new double[]{24.0, 8.0, 12.0, 19.0}, SeaBandsV2.scaled(0.5, 32.0)),
        /**
         * 15 m blocks (EXPERIMENTAL, 2026-11-19): DaedalusTGM-Exp draws them with its SR-15 node (30 m pixels -&gt; 15 m).
         * Height curve = the user's CAP decision (2026-11-18), not less exaggeration: 1:4's exaggeration (a quarter of
         * its rates: lowland 4, midland ramp to ~6) holds up to ~2 km; above it the rate climbs smoothly (highland
         * blend +-1 km around 2.6 km) to 32 m per block, so the model's soft-capped summits (&lt; 5150 m) end at
         * y ~631 under the build limit (3 km: y ~563, 4 km: y ~595). v2 sea: a quarter of 1:4's shallows and shelf
         * rates (1 / 1.5 m per block), deep ocean 48, so the deepest trench (-9.7 km) lands at y -277 (Challenger
         * -10.9 km at -302). Standard worlds use the v1 rates (ocean 12) with the same knots.
         */
        ONE_TO_ONE(15.0, new double[]{12.0, 4.0, 6.85, 32.0}, SeaBandsV2.scaled(0.25, 48.0),
                new double[]{600.0, 2600.0, 60.0, 150.0, 1000.0});

        private static final double[] KNOTS = {600.0, 2000.0, 60.0, 150.0, 300.0};
        private final double horizontalMetresPerBlock;
        private final double[] curveRates;
        private final SeaBandsV2 seaV2;
        /** The preset's own knots, or null for the shared {@link #KNOTS}. */
        private final double[] knots;

        TerrainScalePreset(double horizontalMetresPerBlock, double[] curveRates, SeaBandsV2 seaV2) {
            this(horizontalMetresPerBlock, curveRates, seaV2, null);
        }

        TerrainScalePreset(double horizontalMetresPerBlock, double[] curveRates, SeaBandsV2 seaV2, double[] knots) {
            this.horizontalMetresPerBlock = horizontalMetresPerBlock;
            this.curveRates = curveRates;
            this.seaV2 = seaV2;
            this.knots = knots;
        }

        public double horizontalMetresPerBlock() {
            return horizontalMetresPerBlock;
        }

        /** v1 rates (ocean, lowland, midland, highland): the Standard generator's curve. */
        public double[] curveRates() {
            return curveRates.clone();
        }

        /** v1 knots (lowland top, highland base, shore / midland / highland blend half-widths). */
        public double[] curveKnots() {
            return knots != null ? knots.clone() : KNOTS.clone();
        }

        /** This preset's v2 sea bands. */
        public SeaBandsV2 seaV2() {
            return seaV2;
        }

        /** The height curve of a DaedalusTGM-Exp world at this scale created with {@code version} (null = current). */
        public HeightCurveSpec heightCurve(HeightCurveVersion version) {
            HeightCurveVersion v = HeightCurveVersion.orCurrent(version);
            SeaBandsV2 sea = seaV2;
            if (v == HeightCurveVersion.V1) {
                return new HeightCurveSpec(v, curveRates.clone(), curveKnots(), 0.0, sea.shelfBaseM(),
                        sea.shelfBlendM(), 0.0, sea.shallowBaseM(), sea.shallowBlendM());
            }
            double[] rates = curveRates.clone();
            rates[0] = sea.deepMetresPerBlock();
            double[] k = curveKnots();
            k[2] = sea.shoreBlendM();
            return new HeightCurveSpec(v, rates, k, sea.shelfMetresPerBlock(), sea.shelfBaseM(), sea.shelfBlendM(),
                    sea.shallowMetresPerBlock(), sea.shallowBaseM(), sea.shallowBlendM());
        }
    }

    /**
     * The sea bands of a height-curve v2 world: from the waterline down, shallows (finest), shelf, then the deep ocean,
     * blended with the same finite-support smoothstep as every band; the shore blend (land side) is narrowed to
     * {@code shoreBlendM} so the shallows hold their rate up to the waterline. Rates in metres per block, depths in
     * metres (negative = below sea level).
     */
    public record SeaBandsV2(double shallowMetresPerBlock, double shallowBaseM, double shallowBlendM,
                             double shelfMetresPerBlock, double shelfBaseM, double shelfBlendM,
                             double deepMetresPerBlock, double shoreBlendM) {
        /** 1:4's sea bands with the shallows and shelf rates times {@code rateScale} (1:2 = 0.5, 1:1 = 0.25) and its own
         *  deep-ocean rate (chosen per preset for trench headroom above the column floor). */
        public static SeaBandsV2 scaled(double rateScale, double deepMetresPerBlock) {
            return new SeaBandsV2(4.0 * rateScale, -24.0, 12.0, 6.0 * rateScale, -300.0, 150.0, deepMetresPerBlock,
                    10.0);
        }
    }

    /**
     * One height curve (metres -&gt; blocks) as TGMPipe's {@code height_curve.HeightCurve} reads it: the v1 rates and
     * knots plus the v2 sea bands ({@code shelfMetresPerBlock} / {@code shallowMetresPerBlock} 0 = none: the v1 curve).
     *
     * @param rates metres per block: ocean, lowland, midland, highland
     * @param knots metres: lowland top, highland base, shore / midland / highland blend half-widths
     */
    public record HeightCurveSpec(HeightCurveVersion version, double[] rates, double[] knots,
                                  double shelfMetresPerBlock, double shelfBaseM, double shelfBlendM,
                                  double shallowMetresPerBlock, double shallowBaseM, double shallowBlendM) {
        public HeightCurveSpec {
            rates = rates.clone();
            knots = knots.clone();
        }

        @Override
        public double[] rates() {
            return rates.clone();
        }

        @Override
        public double[] knots() {
            return knots.clone();
        }
    }

    /**
     * Column profile source of {@link Kind#PROFILE} worlds, for column cube {@code (cx, cz)} (32 x 32
     * columns, arrays {@code [lz * 32 + lx]}). Called on native generator threads, concurrently; may
     * block; must not call back into this generator. Water {@link #NO_WATER} = dry; a river void is
     * {@code riverRoof > riverFloor}; {@code riverFlow} -1 = none; biome = BiomeType ordinal.
     */
    @FunctionalInterface
    public interface ColumnProfiles {
        boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                     byte[] biome, byte[] riverFlow, short[] carve);

        /**
         * As {@link #fill(long, long, int[], int[], int[], int[], byte[], byte[], short[])} plus the surface channels
         * (SBW ABI 5): {@code surface[slot * 1024 + lz * 32 + lx]}, pre-filled with 255 (no data) - a source that
         * leaves a slot alone changes nothing. Slots = {@code SurfaceChannel.slot()} / {@code SBW_SURF_*}.
         */
        default boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                             byte[] biome, byte[] riverFlow, short[] carve, byte[] surface) {
            return fill(cx, cz, height, water, riverFloor, riverRoof, biome, riverFlow, carve);
        }
    }

    /** Generator configuration (worldgen.h sbw_gen_config). Defaults = sbw_config_defaults. */
    public static final class Config {
        public Kind kind = Kind.STANDARD;
        public long seed;
        public long minY = -384;
        public long maxY = 640;
        public int seaLevel = 64;
        public int flags = FLAG_PEAK_DAMP | FLAG_DEEP_LAYERS | FLAG_FEATURES;
        public TerrainScalePreset scale = TerrainScalePreset.ONE_TO_FOUR;
        /** Ids by {@link Block} ordinal (see {@link #registryBlockIds()}). */
        public short[] blockIds = new short[Block.values().length];
        /** cv block flags per game id (CV_BLOCK_*), for the column surface's sky value. */
        public short[] blockFlags = new short[0];
        public int waterLayer = -1;
        public int stateLayer = -1;
        public int snowLayer = -1;
        public short waterSourceValue = 16;
        /** Per river flow octant 0..7; 0 = plain source value. */
        public short[] waterRiverValues = new short[8];
        /** Stalagmite anchor states [((size - 1) * 2 + hanging) * 4 + facing]. */
        public short[] stalagmiteStates = new short[24];
        /** {upright part, hanging part}. */
        public short[] stalagmitePartStates = new short[2];
        public int cacheColumns;
        /**
         * {@link #FLAG_BOULDERS}: id of the SBRB rock shape bank the world's boulders read (game resource
         * {@code worldgen/rocks/rock_bank_v<id>.sbrb}, registered on first use); 0 = procedural shapes only.
         */
        public int rockBank;
        /**
         * {@link Kind#STANDARD}: the world's Standard shape ({@link StandardShapeVersion#number()}, native
         * {@code standard_shape}); 1 = the 2026-11-01 shape. Shape 2 also needs its curve in {@link #curveRates} /
         * {@link #curveKnots} ({@code scale.heightCurve(V2)}): see {@link NativeTerrain#config}.
         */
        public int standardShape = 1;
        /** Height-curve rates / knots to send instead of the scale's v1 ones (null = {@code scale.curveRates()}). */
        public double[] curveRates;
        public double[] curveKnots;
        /** Required for {@link Kind#PROFILE}. */
        public ColumnProfiles profiles;
    }

    /** One column's facts (sbw_column_info). */
    public record ColumnInfo(int height, int carvedHeight, int waterLevel, int biome, int surfaceBlock,
                             int poolDepth, int tree, int top, float temperature, float moisture) {
    }

    /* ------------------------------------------------------------------------------ library */
    private static final NativeLibraryLocator.Spec LIBRARY = new NativeLibraryLocator.Spec(
        "stonebreak_worldgen", "stonebreak.worldgen.path", "STONEBREAK_WORLDGEN_PATH",
        List.of(
            "stonebreak-game/native/build/release/",
            "stonebreak-game/native/build/debug/",
            "native/build/release/",
            "native/build/debug/",
            "../stonebreak-game/native/build/release/",
            "../stonebreak-game/native/build/debug/"),
        resource -> NativeCubeGenerator.class.getResourceAsStream("/" + resource));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;
    private static final Path LIBRARY_PATH;
    private static MethodHandle CONFIG_DEFAULTS, CREATE, DESTROY, LAST_ERROR, GENERATE_CUBE, SAMPLE_COLUMNS,
        SAMPLE_OPENINGS, SAMPLE_OPENINGS_WATER, PROBE_TREES, QUERY_COLUMN, COLUMN_CARVE, COLUMN_SURFACE,
        REGISTER_ROCK_BANK, STANDARD_SHAPE_MAX;

    static {
        boolean ok = false;
        String reason = null;
        Path path = null;
        try {
            path = NativeLibraryLocator.locate(LIBRARY);
            if (path == null) {
                reason = "libstonebreak_worldgen not found";
            } else {
                SymbolLookup lookup = SymbolLookup.libraryLookup(path, Arena.global());
                MethodHandle abi = down(lookup, "sbw_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
                int version = (int) abi.invokeExact();
                if (version != EXPECTED_ABI) {
                    reason = "SBW ABI " + version + " (expected " + EXPECTED_ABI + ") at " + path;
                } else {
                    MethodHandle size = down(lookup, "sbw_config_size", FunctionDescriptor.of(ValueLayout.JAVA_INT));
                    int configSize = (int) size.invokeExact();
                    if (configSize != CONFIG_SIZE) {
                        reason = "sbw_gen_config is " + configSize + " bytes (expected " + CONFIG_SIZE + ")";
                    } else {
                        bind(lookup);
                        ok = true;
                    }
                }
            }
        } catch (Throwable t) {
            reason = "failed to load: " + t;
        }
        AVAILABLE = ok;
        UNAVAILABLE_REASON = reason;
        LIBRARY_PATH = path;
    }

    private static MethodHandle down(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("missing native symbol " + name)), fd);
    }

    private static void bind(SymbolLookup l) {
        ValueLayout A = ValueLayout.ADDRESS, I = ValueLayout.JAVA_INT, J = ValueLayout.JAVA_LONG;
        CONFIG_DEFAULTS = down(l, "sbw_config_defaults", FunctionDescriptor.ofVoid(A));
        CREATE = down(l, "sbw_cv_generator_create", FunctionDescriptor.of(A, A));
        DESTROY = down(l, "sbw_cv_generator_destroy", FunctionDescriptor.ofVoid(A));
        LAST_ERROR = down(l, "sbw_last_error", FunctionDescriptor.of(A));
        GENERATE_CUBE = down(l, "sbw_generate_cube", FunctionDescriptor.of(I, A, J, J, J, A, A));
        COLUMN_SURFACE = down(l, "sbw_column_surface", FunctionDescriptor.of(I, A, J, J, A, A));
        SAMPLE_COLUMNS = down(l, "sbw_sample_columns", FunctionDescriptor.of(I, A, J, J, I, I, I, A, A, A, A));
        SAMPLE_OPENINGS = down(l, "sbw_sample_cell_openings", FunctionDescriptor.of(I, A, J, J, I, I, A, A, A));
        // Added within ABI 4 (2026-11-19): optional, so an older library still loads (its mouths read dry).
        SAMPLE_OPENINGS_WATER = l.find("sbw_sample_cell_openings_water")
            .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(I, A, J, J, I, I, A, A, A, A)))
            .orElse(null);
        // Added within ABI 4/5 (2026-11-19, boulders): optional, so an older library still loads (no rock banks).
        REGISTER_ROCK_BANK = l.find("sbw_register_rock_bank")
            .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(I, I, A, J)))
            .orElse(null);
        // Added within ABI 5 (2026-11-19, Standard shape 2): optional; a library without it knows only shape 1.
        STANDARD_SHAPE_MAX = l.find("sbw_standard_shape_max")
            .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(I)))
            .orElse(null);
        PROBE_TREES = down(l, "sbw_probe_cell_trees", FunctionDescriptor.of(I, A, J, J, I, I, A, A, A, A, I));
        QUERY_COLUMN = down(l, "sbw_query_column", FunctionDescriptor.of(I, A, J, J, A));
        COLUMN_CARVE = down(l, "sbw_column_carve", FunctionDescriptor.of(I, A, J, J, A, A));
    }

    /** The highest Standard shape the loaded library generates (1 for a library older than shape 2). */
    public static int standardShapeMax() {
        if (!AVAILABLE || STANDARD_SHAPE_MAX == null) {
            return 1;
        }
        try {
            return (int) STANDARD_SHAPE_MAX.invokeExact();
        } catch (Throwable t) {
            return 1;
        }
    }

    /** True when the library loaded and passed its ABI handshake. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Why {@link #isAvailable()} is false (null when it is true). */
    public static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    public static Path libraryPath() {
        return LIBRARY_PATH;
    }

    private static final java.util.Set<Integer> REGISTERED_BANKS = new java.util.HashSet<>();

    /**
     * Registers the rock shape bank {@code id} (resource {@code /worldgen/rocks/rock_bank_v<id>.sbrb}) with the native
     * library once per process.
     *
     * @throws IllegalStateException when the resource is missing or the library refuses it (a world must never fall
     *                               back to other shapes: its boulders are part of its identity)
     */
    public static synchronized void ensureRockBank(int id) {
        if (id == 0 || REGISTERED_BANKS.contains(id)) {
            return;
        }
        if (REGISTER_ROCK_BANK == null) {
            throw new IllegalStateException("libstonebreak_worldgen has no sbw_register_rock_bank (rebuild the natives)");
        }
        String resource = "/worldgen/rocks/rock_bank_v" + id + ".sbrb";
        byte[] data;
        try (java.io.InputStream in = NativeCubeGenerator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("rock bank resource missing: " + resource);
            }
            data = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("rock bank unreadable: " + resource, e);
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int r = (int) REGISTER_ROCK_BANK.invokeExact(id, seg, (long) data.length);
            if (r != 0) {
                throw new IllegalStateException("rock bank " + resource + " refused: " + lastError());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        REGISTERED_BANKS.add(id);
    }

    /** {@link Config#blockIds} from the game's runtime block registry (SBO numeric ids). */
    public static short[] registryBlockIds() {
        return blockIds(name -> BlockType.valueOf(name).getId());
    }

    public static short[] blockIds(ToIntFunction<String> idOf) {
        Block[] roles = Block.values();
        short[] ids = new short[roles.length];
        for (Block role : roles) {
            ids[role.ordinal()] = (short) idOf.applyAsInt(role.name());
        }
        return ids;
    }

    /* ---------------------------------------------------------------------------- instance */
    private final Arena arena = Arena.ofShared();
    private final MemorySegment generator;
    private final ColumnProfiles profiles;
    private boolean closed;

    private NativeCubeGenerator(Config config) {
        if (!AVAILABLE) {
            throw new IllegalStateException("native cube generator unavailable: " + UNAVAILABLE_REASON);
        }
        this.profiles = config.profiles;
        MemorySegment cfg = arena.allocate(CONFIG_SIZE, 8);
        try {
            CONFIG_DEFAULTS.invokeExact(cfg);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        cfg.set(ValueLayout.JAVA_INT, 4, config.kind.ordinal());
        cfg.set(ValueLayout.JAVA_LONG, 8, config.seed);
        cfg.set(ValueLayout.JAVA_LONG, 16, config.minY);
        cfg.set(ValueLayout.JAVA_LONG, 24, config.maxY);
        cfg.set(ValueLayout.JAVA_INT, 32, config.seaLevel);
        cfg.set(ValueLayout.JAVA_INT, 36, config.flags);
        cfg.set(ValueLayout.JAVA_DOUBLE, 40, config.scale.horizontalMetresPerBlock());
        double[] rates = config.curveRates != null ? config.curveRates.clone() : config.scale.curveRates();
        double[] knots = config.curveKnots != null ? config.curveKnots.clone() : config.scale.curveKnots();
        for (int i = 0; i < 4; i++) cfg.set(ValueLayout.JAVA_DOUBLE, 48 + 8L * i, rates[i]);
        for (int i = 0; i < 5; i++) cfg.set(ValueLayout.JAVA_DOUBLE, 80 + 8L * i, knots[i]);
        if (config.blockFlags.length > 0) {
            MemorySegment flags = arena.allocateFrom(ValueLayout.JAVA_SHORT, config.blockFlags);
            cfg.set(ValueLayout.ADDRESS, 120, flags);
            cfg.set(ValueLayout.JAVA_INT, 128, config.blockFlags.length);
        }
        cfg.set(ValueLayout.JAVA_INT, 132, config.waterLayer);
        cfg.set(ValueLayout.JAVA_INT, 136, config.stateLayer);
        cfg.set(ValueLayout.JAVA_INT, 140, config.snowLayer);
        if (config.kind == Kind.PROFILE) {
            if (config.profiles == null) {
                throw new IllegalArgumentException("PROFILE kind needs Config.profiles");
            }
            cfg.set(ValueLayout.ADDRESS, 144, profileUpcall());
        }
        cfg.set(ValueLayout.JAVA_INT, 160, config.cacheColumns);
        cfg.set(ValueLayout.JAVA_SHORT, 164, config.waterSourceValue);
        copyShorts(cfg, 168, config.waterRiverValues, 8);
        copyShorts(cfg, 184, config.stalagmiteStates, 24);
        copyShorts(cfg, 232, config.stalagmitePartStates, 2);
        copyShorts(cfg, 240, config.blockIds, Math.min(config.blockIds.length, 64));
        if ((config.flags & FLAG_BOULDERS) != 0 && config.rockBank != 0) {
            ensureRockBank(config.rockBank);
            cfg.set(ValueLayout.JAVA_SHORT, 236, (short) config.rockBank);
        }
        if (config.kind == Kind.STANDARD && config.standardShape > 1) {
            // A library older than the shape would ignore the field and silently build shape 1 terrain into a shape-2
            // world (a seam in its saves): refuse instead.
            if (standardShapeMax() < config.standardShape) {
                arena.close();
                throw new IllegalStateException("libstonebreak_worldgen at " + LIBRARY_PATH + " generates Standard shapes"
                    + " up to " + standardShapeMax() + ", the world needs " + config.standardShape
                    + " (rebuild stonebreak-game/native)");
            }
            cfg.set(ValueLayout.JAVA_SHORT, 238, (short) config.standardShape);
        }
        MemorySegment created;
        try {
            created = (MemorySegment) CREATE.invokeExact(cfg);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        if (created.address() == 0L) {
            arena.close();
            throw new IllegalArgumentException("sbw_cv_generator_create failed: " + lastError());
        }
        this.generator = created;
    }

    /** Creates a generator; the config arrays are copied, the profile source is kept. */
    public static NativeCubeGenerator create(Config config) {
        return new NativeCubeGenerator(config);
    }

    private static void copyShorts(MemorySegment seg, long offset, short[] values, int count) {
        for (int i = 0; i < count && i < values.length; i++) {
            seg.set(ValueLayout.JAVA_SHORT, offset + 2L * i, values[i]);
        }
    }

    private MemorySegment profileUpcall() {
        try {
            MethodHandle target = MethodHandles.lookup().findVirtual(NativeCubeGenerator.class, "fillProfile",
                    MethodType.methodType(int.class, MemorySegment.class, long.class, long.class, MemorySegment.class))
                .bindTo(this);
            return LINKER.upcallStub(target, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The upcall: never lets an exception unwind into native code. */
    @SuppressWarnings("unused")
    private int fillProfile(MemorySegment user, long cx, long cz, MemorySegment out) {
        if (isClosing()) {
            return 0;   // closing: fail fast so the calls in flight (and the prefetch helpers) wind down
        }
        try {
            int[] height = new int[1024], water = new int[1024], floor = new int[1024], roof = new int[1024];
            byte[] biome = new byte[1024], flow = new byte[1024];
            short[] carve = new short[1024];
            byte[] surface = new byte[SURFACE_SLOTS * 1024];
            java.util.Arrays.fill(surface, (byte) 0xFF);
            if (!profiles.fill(cx, cz, height, water, floor, roof, biome, flow, carve, surface)) {
                return 0;
            }
            MemorySegment p = out.reinterpret(PROFILE_SIZE);
            MemorySegment.copy(height, 0, p, ValueLayout.JAVA_INT, 0, 1024);
            MemorySegment.copy(water, 0, p, ValueLayout.JAVA_INT, 4096, 1024);
            MemorySegment.copy(floor, 0, p, ValueLayout.JAVA_INT, 8192, 1024);
            MemorySegment.copy(roof, 0, p, ValueLayout.JAVA_INT, 12288, 1024);
            MemorySegment.copy(biome, 0, p, ValueLayout.JAVA_BYTE, 16384, 1024);
            MemorySegment.copy(flow, 0, p, ValueLayout.JAVA_BYTE, 17408, 1024);
            MemorySegment.copy(carve, 0, p, ValueLayout.JAVA_SHORT, 18432, 1024);
            MemorySegment.copy(surface, 0, p, ValueLayout.JAVA_BYTE, PROFILE_SURFACE_OFFSET, SURFACE_SLOTS * 1024);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The {@code const cv_generator*} for {@code cv_stream_desc.generator}; valid until {@link #close()}. */
    public MemorySegment generator() {
        ensureOpen();
        return generator;
    }

    public static String lastError() {
        try {
            MemorySegment s = (MemorySegment) LAST_ERROR.invokeExact();
            return s.reinterpret(4096).getString(0);
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /**
     * One cube into {@code ids[32768]} (CV_CELL_INDEX order) and optional layers (8 slots, nulls
     * allowed). Returns 1 written, 0 uniform ({@code ids[0]} only), negative on failure. For tools and
     * tests; the engine calls the table directly.
     */
    public int generateCube(long cx, long cy, long cz, short[] ids, short[][] layers) {
        enter();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment idSeg = a.allocate(ValueLayout.JAVA_SHORT, 32768);
            MemorySegment layerTable = MemorySegment.NULL;
            MemorySegment[] layerSegs = new MemorySegment[8];
            if (layers != null) {
                layerTable = a.allocate(ValueLayout.ADDRESS, 8);
                for (int i = 0; i < 8 && i < layers.length; i++) {
                    if (layers[i] != null) {
                        layerSegs[i] = a.allocate(ValueLayout.JAVA_SHORT, 32768);
                        layerTable.setAtIndex(ValueLayout.ADDRESS, i, layerSegs[i]);
                    }
                }
            }
            int r = (int) GENERATE_CUBE.invokeExact(generator, cx, cy, cz, idSeg, layerTable);
            if (r >= 0) {
                MemorySegment.copy(idSeg, ValueLayout.JAVA_SHORT, 0, ids, 0, r == 0 ? 1 : 32768);
                for (int i = 0; i < 8 && layers != null && i < layers.length; i++) {
                    if (layerSegs[i] != null && r == 1) {
                        MemorySegment.copy(layerSegs[i], ValueLayout.JAVA_SHORT, 0, layers[i], 0, 32768);
                    }
                }
            }
            return r;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /** Never-generated surface of column cube (cx, cz): sky[1024], top[1024]; false when unknown. */
    public boolean columnSurface(long cx, long cz, long[] sky, long[] top) {
        enter();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(ValueLayout.JAVA_LONG, 1024), t = a.allocate(ValueLayout.JAVA_LONG, 1024);
            int r = (int) COLUMN_SURFACE.invokeExact(generator, cx, cz, s, t);
            if (r != 1) return false;
            MemorySegment.copy(s, ValueLayout.JAVA_LONG, 0, sky, 0, 1024);
            MemorySegment.copy(t, ValueLayout.JAVA_LONG, 0, top, 0, 1024);
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /**
     * FastLOD column probe over a count x count grid at (x0 + ix*stride, z0 + iz*stride), indexed
     * [ix*count + iz] (TerrainGenerator.sampleColumns / sampleRawColumns). Outputs other than heights
     * may be null; trees are 0 or (kind + 1) | trunkHeight &lt;&lt; 8; surfaces are block ids.
     */
    public void sampleColumns(long x0, long z0, int count, int stride, boolean carved, int[] outHeights,
                              int[] outWater, short[] outSurface, int[] outTrees) {
        enter();
        int n = count * count;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment h = a.allocate(ValueLayout.JAVA_INT, n);
            MemorySegment w = outWater == null ? MemorySegment.NULL : a.allocate(ValueLayout.JAVA_INT, n);
            MemorySegment s = outSurface == null ? MemorySegment.NULL : a.allocate(ValueLayout.JAVA_SHORT, n);
            MemorySegment t = outTrees == null ? MemorySegment.NULL : a.allocate(ValueLayout.JAVA_INT, n);
            int r = (int) SAMPLE_COLUMNS.invokeExact(generator, x0, z0, count, stride, carved ? SAMPLE_CARVED : 0,
                h, w, s, t);
            check(r, "sbw_sample_columns");
            MemorySegment.copy(h, ValueLayout.JAVA_INT, 0, outHeights, 0, n);
            if (outWater != null) MemorySegment.copy(w, ValueLayout.JAVA_INT, 0, outWater, 0, n);
            if (outSurface != null) MemorySegment.copy(s, ValueLayout.JAVA_SHORT, 0, outSurface, 0, n);
            if (outTrees != null) MemorySegment.copy(t, ValueLayout.JAVA_INT, 0, outTrees, 0, n);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /**
     * As below, plus the water standing in each mouth ({@code outWater}, native {@link #NO_WATER} when dry): sea
     * level over a flooded sea shelf. A library without {@code sbw_sample_cell_openings_water} reports them dry.
     */
    public void sampleCellOpenings(long x0, long z0, int cells, int cellSize, int[] cellHeights, int[] outFloor,
                                   byte[] outCoverage, int[] outWater) {
        if (outWater == null || SAMPLE_OPENINGS_WATER == null) {
            sampleCellOpenings(x0, z0, cells, cellSize, cellHeights, outFloor, outCoverage);
            if (outWater != null) java.util.Arrays.fill(outWater, NO_WATER);
            return;
        }
        enter();
        int n = cells * cells;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment h = a.allocateFrom(ValueLayout.JAVA_INT, cellHeights);
            MemorySegment f = a.allocate(ValueLayout.JAVA_INT, n), c = a.allocate(ValueLayout.JAVA_BYTE, n);
            MemorySegment w = a.allocate(ValueLayout.JAVA_INT, n);
            int r = (int) SAMPLE_OPENINGS_WATER.invokeExact(generator, x0, z0, cells, cellSize, h, f, c, w);
            check(r, "sbw_sample_cell_openings_water");
            MemorySegment.copy(f, ValueLayout.JAVA_INT, 0, outFloor, 0, n);
            MemorySegment.copy(c, ValueLayout.JAVA_BYTE, 0, outCoverage, 0, n);
            MemorySegment.copy(w, ValueLayout.JAVA_INT, 0, outWater, 0, n);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /** TerrainGenerator.sampleCellOpenings: floors (Integer.MIN_VALUE = none) and coverage 0..255. */
    public void sampleCellOpenings(long x0, long z0, int cells, int cellSize, int[] cellHeights, int[] outFloor,
                                   byte[] outCoverage) {
        enter();
        int n = cells * cells;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment h = a.allocateFrom(ValueLayout.JAVA_INT, cellHeights);
            MemorySegment f = a.allocate(ValueLayout.JAVA_INT, n), c = a.allocate(ValueLayout.JAVA_BYTE, n);
            int r = (int) SAMPLE_OPENINGS.invokeExact(generator, x0, z0, cells, cellSize, h, f, c);
            check(r, "sbw_sample_cell_openings");
            MemorySegment.copy(f, ValueLayout.JAVA_INT, 0, outFloor, 0, n);
            MemorySegment.copy(c, ValueLayout.JAVA_BYTE, 0, outCoverage, 0, n);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /**
     * TerrainGenerator.probeCellTrees: spots packed (lx &amp; 0xFF) | (lz &amp; 0xFF) &lt;&lt; 8 | kind &lt;&lt; 16 |
     * trunk &lt;&lt; 18, relative to (x0, z0).
     */
    public int[] probeCellTrees(long x0, long z0, int cells, int cellSize, int[] cellHeights, int[] cellWater,
                                short[] cellSurface) {
        enter();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment h = a.allocateFrom(ValueLayout.JAVA_INT, cellHeights);
            MemorySegment w = a.allocateFrom(ValueLayout.JAVA_INT, cellWater);
            MemorySegment s = a.allocateFrom(ValueLayout.JAVA_SHORT, cellSurface);
            int cap = 256;
            for (;;) {
                MemorySegment out = a.allocate(ValueLayout.JAVA_INT, cap);
                int r = (int) PROBE_TREES.invokeExact(generator, x0, z0, cells, cellSize, h, w, s, out, cap);
                if (r <= -16) {
                    cap = -r - 16;
                    continue;
                }
                check(r, "sbw_probe_cell_trees");
                return out.asSlice(0, 4L * r).toArray(ValueLayout.JAVA_INT);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    public ColumnInfo queryColumn(long x, long z) {
        enter();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment o = a.allocate(COLUMN_INFO_SIZE, 8);
            int r = (int) QUERY_COLUMN.invokeExact(generator, x, z, o);
            check(r, "sbw_query_column");
            return new ColumnInfo(o.get(ValueLayout.JAVA_INT, 0), o.get(ValueLayout.JAVA_INT, 4),
                o.get(ValueLayout.JAVA_INT, 8), o.get(ValueLayout.JAVA_INT, 12), o.get(ValueLayout.JAVA_INT, 16),
                o.get(ValueLayout.JAVA_INT, 20), o.get(ValueLayout.JAVA_INT, 24), o.get(ValueLayout.JAVA_INT, 28),
                o.get(ValueLayout.JAVA_FLOAT, 32), o.get(ValueLayout.JAVA_FLOAT, 36));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /** Carved surface of a column cube: heights[1024] and carved flags[1024] ([lz * 32 + lx]). */
    public void columnCarve(long cx, long cz, int[] outCarvedHeight, byte[] outCarved) {
        enter();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment h = a.allocate(ValueLayout.JAVA_INT, 1024), c = a.allocate(ValueLayout.JAVA_BYTE, 1024);
            int r = (int) COLUMN_CARVE.invokeExact(generator, cx, cz, h, c);
            check(r, "sbw_column_carve");
            MemorySegment.copy(h, ValueLayout.JAVA_INT, 0, outCarvedHeight, 0, 1024);
            MemorySegment.copy(c, ValueLayout.JAVA_BYTE, 0, outCarved, 0, 1024);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            exit();
        }
    }

    /** A negative native result as an exception: {@link TerrainClosedException} once the generator is closing. */
    private void check(int r, String what) {
        if (r < 0) {
            String error = lastError();
            if (isClosing()) {
                throw new TerrainClosedException(what + " refused: generator closing (" + error + ")");
            }
            throw new IllegalStateException(what + " failed: " + error);
        }
    }

    private void ensureOpen() {
        if (closed || isClosing()) {
            throw new TerrainClosedException("generator closed");
        }
    }

    /* ---------------------------------------------------------------------------- lifetime gate */
    // Every Java call into the native generator holds the gate (enter/exit); close() refuses new calls, waits for the
    // ones in flight and only then destroys the generator. FastLOD workers, the weather relief thread, debug and spawn
    // queries all run on their own threads and outlive their owners' shutdown calls by design (an executor that does
    // not stop in time is abandoned, the relief thread is never joined): without the gate a call in flight at quit ran
    // on a freed generator (SIGSEGV in Generator::base / buildCarve, 2026-11-19).

    /** Sign bit of {@link #calls}: the generator is closing (new calls are refused). */
    private static final int CLOSING = Integer.MIN_VALUE;
    /** How long close() waits for calls in flight before giving up on freeing the generator (it is leaked then). */
    private static final long CLOSE_DRAIN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(
        Long.getLong("stonebreak.worldgen.closeDrainSeconds", 30L));
    /** Calls in flight (low 31 bits) | {@link #CLOSING}. */
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    /** Admits one call, or throws {@link TerrainClosedException} once {@link #close} began. Pair with exit(). */
    private void enter() {
        for (;;) {
            int s = calls.get();
            if (s < 0) {
                throw new TerrainClosedException("generator closed");
            }
            if (calls.compareAndSet(s, s + 1)) {
                return;
            }
        }
    }

    private void exit() {
        if (calls.decrementAndGet() == CLOSING) {
            synchronized (calls) {
                calls.notifyAll();
            }
        }
    }

    /** True once {@link #beginClose} or {@link #close} ran: every new call is refused. */
    public boolean isClosing() {
        return calls.get() < 0;
    }

    /** Calls in flight right now (tests, diagnostics). */
    int callsInFlight() {
        return calls.get() & ~CLOSING;
    }

    /**
     * Starts closing without waiting: new calls are refused with {@link TerrainClosedException} and the profile
     * upcall fails fast, so calls in flight wind down. The owner then closes the profile source (which unblocks
     * upcalls waiting on it) and calls {@link #close()}. Idempotent.
     */
    public void beginClose() {
        calls.getAndUpdate(s -> s | CLOSING);
    }

    /**
     * Destroys the native generator, then frees the upcall stub - after refusing new calls and waiting for the
     * calls in flight to return. A call that does not return within {@code stonebreak.worldgen.closeDrainSeconds}
     * (default 30) leaves the generator and its upcall stub allocated (leaked, never freed under a running call).
     * The engine streamer that uses {@link #generator()} must be closed before.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        beginClose();
        if (!awaitDrained()) {
            org.slf4j.LoggerFactory.getLogger(NativeCubeGenerator.class).error(
                "Native generator still has {} call(s) in flight after {} s; leaking it instead of freeing it under "
                    + "them", callsInFlight(), java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(CLOSE_DRAIN_NANOS));
            return;
        }
        try {
            DESTROY.invokeExact(generator);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            arena.close();
        }
    }

    /** Waits until no call is in flight; false on timeout. Interrupts are kept for the caller, not obeyed. */
    private boolean awaitDrained() {
        long deadline = System.nanoTime() + CLOSE_DRAIN_NANOS;
        boolean interrupted = false;
        try {
            synchronized (calls) {
                while (calls.get() != CLOSING) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    try {
                        calls.wait(Math.max(1L, Math.min(250L, left / 1_000_000L)));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                return true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
