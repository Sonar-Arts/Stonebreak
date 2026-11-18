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
 * Thin FFM binding to the native cube generator ({@code libstonebreak_worldgen}, SBW ABI 3, #446): the
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

    /** SBW ABI this binding speaks. */
    public static final int EXPECTED_ABI = 3;
    /** Water level of a dry column. */
    public static final int NO_WATER = Integer.MIN_VALUE;
    public static final int FLAG_PEAK_DAMP = 0x1, FLAG_DEEP_LAYERS = 0x2, FLAG_FEATURES = 0x4;
    public static final int SAMPLE_CARVED = 1;
    private static final int CONFIG_SIZE = 368;
    private static final int PROFILE_SIZE = 18432;
    private static final int COLUMN_INFO_SIZE = 40;

    /** Generator kinds (worldgen.h SBW_KIND_*). */
    public enum Kind { STANDARD, PROFILE }

    /** Block roles in {@code sbw_block} order; names are the {@link BlockType} names they map to. */
    public enum Block {
        AIR, WATER, STONE, BEDROCK, MAGMA, DIRT, GRASS, SAND, RED_SAND, SANDSTONE, RED_SANDSTONE,
        GRAVEL, CLAY, SNOWY_DIRT, SWAMPY_GRASS, ICE, SNOW, COAL_ORE, IRON_ORE, CRYSTAL, LIMESTONE,
        LIMESTONE_STALAGMITE, WOOD, LEAVES, ELM_WOOD_LOG, ELM_LEAVES, PINE, PINE_LEAVES, CYPRESS_LOG,
        CYPRESS_LEAVES, ROSE, WILDGRASS, DANDELION, CACTUS, RED_SAND_COBBLESTONE
    }

    /**
     * Terrain scale of a world: metres per block horizontally and the height curve's metres per block.
     * The distilled shape was fitted at 1:4; 1:2 is that shape at twice the size (a refit is optional).
     */
    public enum TerrainScalePreset {
        /** 60 m blocks (today's world). */
        ONE_TO_FOUR(60.0, new double[]{48.0, 16.0, 24.0, 38.0}),
        /** 30 m blocks (the terrain model's native pixels); rates halved, same exaggeration. */
        ONE_TO_TWO(30.0, new double[]{24.0, 8.0, 12.0, 19.0});

        private static final double[] KNOTS = {600.0, 2000.0, 60.0, 150.0, 300.0};
        private final double horizontalMetresPerBlock;
        private final double[] curveRates;

        TerrainScalePreset(double horizontalMetresPerBlock, double[] curveRates) {
            this.horizontalMetresPerBlock = horizontalMetresPerBlock;
            this.curveRates = curveRates;
        }

        public double horizontalMetresPerBlock() {
            return horizontalMetresPerBlock;
        }

        public double[] curveRates() {
            return curveRates.clone();
        }

        public double[] curveKnots() {
            return KNOTS.clone();
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
                     byte[] biome, byte[] riverFlow);
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
        SAMPLE_OPENINGS, PROBE_TREES, QUERY_COLUMN, COLUMN_CARVE, COLUMN_SURFACE;

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
        PROBE_TREES = down(l, "sbw_probe_cell_trees", FunctionDescriptor.of(I, A, J, J, I, I, A, A, A, A, I));
        QUERY_COLUMN = down(l, "sbw_query_column", FunctionDescriptor.of(I, A, J, J, A));
        COLUMN_CARVE = down(l, "sbw_column_carve", FunctionDescriptor.of(I, A, J, J, A, A));
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
        double[] rates = config.scale.curveRates();
        double[] knots = config.scale.curveKnots();
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
        try {
            int[] height = new int[1024], water = new int[1024], floor = new int[1024], roof = new int[1024];
            byte[] biome = new byte[1024], flow = new byte[1024];
            if (!profiles.fill(cx, cz, height, water, floor, roof, biome, flow)) {
                return 0;
            }
            MemorySegment p = out.reinterpret(PROFILE_SIZE);
            MemorySegment.copy(height, 0, p, ValueLayout.JAVA_INT, 0, 1024);
            MemorySegment.copy(water, 0, p, ValueLayout.JAVA_INT, 4096, 1024);
            MemorySegment.copy(floor, 0, p, ValueLayout.JAVA_INT, 8192, 1024);
            MemorySegment.copy(roof, 0, p, ValueLayout.JAVA_INT, 12288, 1024);
            MemorySegment.copy(biome, 0, p, ValueLayout.JAVA_BYTE, 16384, 1024);
            MemorySegment.copy(flow, 0, p, ValueLayout.JAVA_BYTE, 17408, 1024);
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
        ensureOpen();
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
        }
    }

    /** Never-generated surface of column cube (cx, cz): sky[1024], top[1024]; false when unknown. */
    public boolean columnSurface(long cx, long cz, long[] sky, long[] top) {
        ensureOpen();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(ValueLayout.JAVA_LONG, 1024), t = a.allocate(ValueLayout.JAVA_LONG, 1024);
            int r = (int) COLUMN_SURFACE.invokeExact(generator, cx, cz, s, t);
            if (r != 1) return false;
            MemorySegment.copy(s, ValueLayout.JAVA_LONG, 0, sky, 0, 1024);
            MemorySegment.copy(t, ValueLayout.JAVA_LONG, 0, top, 0, 1024);
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * FastLOD column probe over a count x count grid at (x0 + ix*stride, z0 + iz*stride), indexed
     * [ix*count + iz] (TerrainGenerator.sampleColumns / sampleRawColumns). Outputs other than heights
     * may be null; trees are 0 or (kind + 1) | trunkHeight &lt;&lt; 8; surfaces are block ids.
     */
    public void sampleColumns(long x0, long z0, int count, int stride, boolean carved, int[] outHeights,
                              int[] outWater, short[] outSurface, int[] outTrees) {
        ensureOpen();
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
        }
    }

    /** TerrainGenerator.sampleCellOpenings: floors (Integer.MIN_VALUE = none) and coverage 0..255. */
    public void sampleCellOpenings(long x0, long z0, int cells, int cellSize, int[] cellHeights, int[] outFloor,
                                   byte[] outCoverage) {
        ensureOpen();
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
        }
    }

    /**
     * TerrainGenerator.probeCellTrees: spots packed (lx &amp; 0xFF) | (lz &amp; 0xFF) &lt;&lt; 8 | kind &lt;&lt; 16 |
     * trunk &lt;&lt; 18, relative to (x0, z0).
     */
    public int[] probeCellTrees(long x0, long z0, int cells, int cellSize, int[] cellHeights, int[] cellWater,
                                short[] cellSurface) {
        ensureOpen();
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
        }
    }

    public ColumnInfo queryColumn(long x, long z) {
        ensureOpen();
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
        }
    }

    /** Carved surface of a column cube: heights[1024] and carved flags[1024] ([lz * 32 + lx]). */
    public void columnCarve(long cx, long cz, int[] outCarvedHeight, byte[] outCarved) {
        ensureOpen();
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
        }
    }

    private static void check(int r, String what) {
        if (r < 0) {
            throw new IllegalStateException(what + " failed: " + lastError());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("generator closed");
        }
    }

    /** Destroys the native generator, then frees the upcall stub. No generator call may be running. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            DESTROY.invokeExact(generator);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            arena.close();
        }
    }
}
