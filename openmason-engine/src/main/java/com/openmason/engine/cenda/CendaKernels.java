package com.openmason.engine.cenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FFM binding to the Cenda native kernels library ({@code libcenda_kernels.so},
 * built from {@code openmason-engine/cenda/native/kernels}).
 *
 * Fully optional: when the library is absent or fails its ABI handshake,
 * {@link #isAvailable()} is false and callers must use their pure-Java path.
 * Nodes are immutable after creation and safe to sample from any number of
 * threads concurrently.
 *
 * Library discovery order: {@code -Dcenda.kernels.path=<file>}, then the
 * {@code CENDA_KERNELS_PATH} environment variable, then known build locations
 * relative to the working directory.
 */
public final class CendaKernels {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaKernels.class);
    private static final int EXPECTED_ABI = 1;

    private static final boolean AVAILABLE;
    private static final String SIMD_LEVEL;
    private static final MethodHandle CREATE_TREE;
    private static final MethodHandle CREATE_FBM;
    private static final MethodHandle DESTROY;
    private static final MethodHandle GEN_2D;
    private static final MethodHandle GEN_3D;
    private static final MethodHandle MESH_CHUNK;
    private static final MethodHandle TERRAIN_CREATE;
    private static final MethodHandle TERRAIN_DESTROY;
    private static final MethodHandle CARVE_WORMS;
    private static final MethodHandle ZSTD_BOUND;
    private static final MethodHandle ZSTD_COMPRESS;
    private static final MethodHandle ZSTD_DECOMPRESS;

    /** Class-table bit flags mirrored from cenda/kernels.h. */
    public static final int CLASS_CUBE = 1;
    public static final int CLASS_TRANSPARENT = 2;
    public static final int CLASS_OPAQUE_LIGHT = 4;

    static {
        boolean available = false;
        String simd = "unavailable";
        MethodHandle createTree = null;
        MethodHandle createFbm = null;
        MethodHandle destroy = null;
        MethodHandle gen2d = null;
        MethodHandle gen3d = null;
        MethodHandle meshChunk = null;
        MethodHandle terrainCreate = null;
        MethodHandle terrainDestroy = null;
        MethodHandle carveWorms = null;
        MethodHandle zstdBound = null;
        MethodHandle zstdCompress = null;
        MethodHandle zstdDecompress = null;
        try {
            Path libPath = locateLibrary();
            if (libPath != null) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.global());

                MethodHandle abiVersion = linker.downcallHandle(
                    find(lookup, "ck_abi_version"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                MethodHandle simdLevel = linker.downcallHandle(
                    find(lookup, "ck_simd_level"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
                createTree = linker.downcallHandle(
                    find(lookup, "ck_noise_from_encoded_tree"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                createFbm = linker.downcallHandle(
                    find(lookup, "ck_noise_simplex_fbm"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
                destroy = linker.downcallHandle(
                    find(lookup, "ck_noise_destroy"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                gen2d = linker.downcallHandle(
                    find(lookup, "ck_gen_grid_2d"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT));
                gen3d = linker.downcallHandle(
                    find(lookup, "ck_gen_grid_3d"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT));
                meshChunk = linker.downcallHandle(
                    find(lookup, "ck_mesh_chunk"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                terrainCreate = linker.downcallHandle(
                    find(lookup, "ck_terrain_create"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT));
                terrainDestroy = linker.downcallHandle(
                    find(lookup, "ck_terrain_destroy"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                carveWorms = linker.downcallHandle(
                    find(lookup, "ck_carve_worms"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
                zstdBound = linker.downcallHandle(
                    find(lookup, "ck_zstd_bound"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
                zstdCompress = linker.downcallHandle(
                    find(lookup, "ck_zstd_compress"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
                zstdDecompress = linker.downcallHandle(
                    find(lookup, "ck_zstd_decompress"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

                int abi = (int) abiVersion.invokeExact();
                if (abi != EXPECTED_ABI) {
                    LOGGER.warn("Cenda kernels at {} have ABI {} (expected {}); ignoring library",
                        libPath, abi, EXPECTED_ABI);
                } else {
                    MemorySegment simdPtr = (MemorySegment) simdLevel.invokeExact();
                    simd = simdPtr.reinterpret(256).getString(0);
                    available = true;
                    LOGGER.info("Cenda native kernels loaded (SIMD: {}) from {}", simd, libPath);
                }
            } else {
                LOGGER.info("Cenda native kernels not found; pure-Java fallbacks will be used");
            }
        } catch (Throwable t) {
            LOGGER.warn("Cenda native kernels failed to load; pure-Java fallbacks will be used", t);
            available = false;
        }
        AVAILABLE = available;
        SIMD_LEVEL = simd;
        CREATE_TREE = createTree;
        CREATE_FBM = createFbm;
        DESTROY = destroy;
        GEN_2D = gen2d;
        GEN_3D = gen3d;
        MESH_CHUNK = meshChunk;
        TERRAIN_CREATE = terrainCreate;
        TERRAIN_DESTROY = terrainDestroy;
        CARVE_WORMS = carveWorms;
        ZSTD_BOUND = zstdBound;
        ZSTD_COMPRESS = zstdCompress;
        ZSTD_DECOMPRESS = zstdDecompress;
    }

    /**
     * Per-thread reusable native buffer for grid fills. A fresh confined Arena
     * per call costs ~150ns — measurable on small fills. Auto arenas are
     * GC-managed, so pooled worker threads never leak segments.
     */
    private static final ThreadLocal<MemorySegment> FILL_BUFFER = new ThreadLocal<>();
    private static final long MIN_BUFFER_FLOATS = 4096; // one 16x16 chunk fill x16

    private static MemorySegment fillBuffer(long floats) {
        MemorySegment segment = FILL_BUFFER.get();
        if (segment == null || segment.byteSize() < floats * Float.BYTES) {
            segment = Arena.ofAuto().allocate(ValueLayout.JAVA_FLOAT, Math.max(floats, MIN_BUFFER_FLOATS));
            FILL_BUFFER.set(segment);
        }
        return segment;
    }

    private CendaKernels() {
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + name));
    }

    private static Path locateLibrary() {
        String prop = System.getProperty("cenda.kernels.path");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            return Files.isRegularFile(p) ? p : null;
        }
        String env = System.getenv("CENDA_KERNELS_PATH");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            return Files.isRegularFile(p) ? p : null;
        }
        String libName = System.mapLibraryName("cenda_kernels");
        String[] candidates = {
            "openmason-engine/cenda/build/release/native/kernels/" + libName,
            "openmason-engine/cenda/build/debug/native/kernels/" + libName,
            "../openmason-engine/cenda/build/release/native/kernels/" + libName,
            "../openmason-engine/cenda/build/debug/native/kernels/" + libName,
            "cenda/build/release/native/kernels/" + libName,
        };
        for (String candidate : candidates) {
            Path p = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** True when the native library loaded and passed its ABI handshake. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** SIMD feature set the native library dispatches to, for diagnostics. */
    public static String simdLevel() {
        return SIMD_LEVEL;
    }

    /**
     * Creates a Simplex-fBm generator with the frequency applied inside the
     * node (DomainScale), so sampling positions are raw world coordinates.
     * Returns 0 on failure or when the library is unavailable.
     */
    public static long createSimplexFbm(int octaves, float lacunarity, float gain, float frequency) {
        if (!AVAILABLE) {
            return 0L;
        }
        try {
            MemorySegment node = (MemorySegment) CREATE_FBM.invokeExact(octaves, lacunarity, gain, frequency);
            return node.address();
        } catch (Throwable t) {
            throw new IllegalStateException("ck_noise_simplex_fbm failed", t);
        }
    }

    /**
     * Creates a generator from a FastNoise2 encoded node tree string.
     * Returns 0 on failure or when the library is unavailable.
     */
    public static long createFromEncodedTree(String encodedTree) {
        if (!AVAILABLE || encodedTree == null) {
            return 0L;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tree = arena.allocateFrom(encodedTree);
            MemorySegment node = (MemorySegment) CREATE_TREE.invokeExact(tree);
            return node.address();
        } catch (Throwable t) {
            throw new IllegalStateException("ck_noise_from_encoded_tree failed", t);
        }
    }

    /** Destroys a node created by one of the create methods. Safe on 0. */
    public static void destroy(long node) {
        if (!AVAILABLE || node == 0L) {
            return;
        }
        try {
            DESTROY.invokeExact(MemorySegment.ofAddress(node));
        } catch (Throwable t) {
            throw new IllegalStateException("ck_noise_destroy failed", t);
        }
    }

    /**
     * Fills {@code out} ({@code xCount*yCount} floats, X fastest) with noise at
     * positions {@code (xOffset + i*xStep, yOffset + j*yStep)}.
     */
    public static boolean fillGrid2D(long node, float[] out,
                                     float xOffset, float yOffset,
                                     int xCount, int yCount,
                                     float xStep, float yStep,
                                     int seed) {
        if (!AVAILABLE || node == 0L || out == null || out.length < xCount * yCount) {
            return false;
        }
        try {
            MemorySegment buffer = fillBuffer((long) xCount * yCount);
            int rc = (int) GEN_2D.invokeExact(MemorySegment.ofAddress(node), buffer,
                xOffset, yOffset, xCount, yCount, xStep, yStep, seed);
            if (rc != 0) {
                return false;
            }
            MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT, 0L, out, 0, xCount * yCount);
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_gen_grid_2d failed", t);
        }
    }

    /**
     * Fills {@code out} ({@code xCount*yCount*zCount} floats, X fastest, then Y,
     * then Z) with noise on a uniform 3D grid.
     */
    public static boolean fillGrid3D(long node, float[] out,
                                     float xOffset, float yOffset, float zOffset,
                                     int xCount, int yCount, int zCount,
                                     float xStep, float yStep, float zStep,
                                     int seed) {
        int total = xCount * yCount * zCount;
        if (!AVAILABLE || node == 0L || out == null || out.length < total) {
            return false;
        }
        try {
            MemorySegment buffer = fillBuffer(total);
            int rc = (int) GEN_3D.invokeExact(MemorySegment.ofAddress(node), buffer,
                xOffset, yOffset, zOffset, xCount, yCount, zCount, xStep, yStep, zStep, seed);
            if (rc != 0) {
                return false;
            }
            MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT, 0L, out, 0, total);
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_gen_grid_3d failed", t);
        }
    }

    // ═══════════════════════ Chunk mesher ═══════════════════════

    private static MemorySegment copyOrNull(Arena arena, short[] data) {
        return data == null ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_SHORT, data);
    }

    /**
     * Culls + lights one chunk's standard-cube faces natively. Layouts and
     * semantics per cenda/kernels.h (ck_mesh_chunk). Returns the number of
     * quad records written to {@code outQuads} (9 floats each), or a negative
     * count if the output array is too small (retry with {@code -result * 9}
     * floats), or Integer.MIN_VALUE when the library is unavailable.
     */
    public static int meshChunk(short[] blocks, byte[] classTable, int airId,
                                short[] planeXn, short[] planeXp,
                                short[] planeZn, short[] planeZp,
                                short[] cornerNn, short[] cornerPn,
                                short[] cornerNp, short[] cornerPp,
                                short[] heights18, int maxY, boolean smooth,
                                float[] outQuads) {
        if (!AVAILABLE) {
            return Integer.MIN_VALUE;
        }
        int capQuads = outQuads.length / 9;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment blocksSeg = arena.allocateFrom(ValueLayout.JAVA_SHORT, blocks);
            MemorySegment clsSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, classTable);
            MemorySegment heightsSeg = arena.allocateFrom(ValueLayout.JAVA_SHORT, heights18);
            MemorySegment outSeg = arena.allocate(ValueLayout.JAVA_FLOAT, (long) capQuads * 9);
            int result = (int) MESH_CHUNK.invokeExact(
                blocksSeg, clsSeg, classTable.length, airId,
                copyOrNull(arena, planeXn), copyOrNull(arena, planeXp),
                copyOrNull(arena, planeZn), copyOrNull(arena, planeZp),
                copyOrNull(arena, cornerNn), copyOrNull(arena, cornerPn),
                copyOrNull(arena, cornerNp), copyOrNull(arena, cornerPp),
                heightsSeg, maxY, smooth ? 1 : 0, outSeg, capQuads);
            if (result > 0) {
                MemorySegment.copy(outSeg, ValueLayout.JAVA_FLOAT, 0L, outQuads, 0, result * 9);
            }
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_mesh_chunk failed", t);
        }
    }

    // ═══════════════════════ Worm carver ═══════════════════════

    /**
     * Creates a native terrain context (channel nodes + splines + worm state).
     * Returns 0 when unavailable or on invalid input. Destroy with
     * {@link #terrainDestroy}.
     */
    public static long terrainCreate(long seed,
                                     int[] chSeeds, int[] chOctaves,
                                     float[] chGain, float[] chLacunarity, float[] chFreq,
                                     int[] chXoff, int[] chZoff,
                                     double[] splineXs, double[] splineYs, int[] splineSizes,
                                     float detailAmplitude) {
        if (!AVAILABLE) {
            return 0L;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctx = (MemorySegment) TERRAIN_CREATE.invokeExact(
                seed,
                arena.allocateFrom(ValueLayout.JAVA_INT, chSeeds),
                arena.allocateFrom(ValueLayout.JAVA_INT, chOctaves),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, chGain),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, chLacunarity),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, chFreq),
                arena.allocateFrom(ValueLayout.JAVA_INT, chXoff),
                arena.allocateFrom(ValueLayout.JAVA_INT, chZoff),
                arena.allocateFrom(ValueLayout.JAVA_DOUBLE, splineXs),
                arena.allocateFrom(ValueLayout.JAVA_DOUBLE, splineYs),
                arena.allocateFrom(ValueLayout.JAVA_INT, splineSizes),
                detailAmplitude);
            return ctx.address();
        } catch (Throwable t) {
            throw new IllegalStateException("ck_terrain_create failed", t);
        }
    }

    /** Destroys a terrain context. Safe on 0. */
    public static void terrainDestroy(long ctx) {
        if (!AVAILABLE || ctx == 0L) {
            return;
        }
        try {
            TERRAIN_DESTROY.invokeExact(MemorySegment.ofAddress(ctx));
        } catch (Throwable t) {
            throw new IllegalStateException("ck_terrain_destroy failed", t);
        }
    }

    /**
     * Runs the native worm carver for one chunk. {@code outMask} (1024 longs)
     * receives the carve mask in java.util.BitSet.valueOf layout, bit index
     * {@code (x<<12)|(y<<4)|z}. anchorChunks/anchors may be null when empty.
     * Returns the number of carved cells, or negative on error/unavailable.
     */
    public static long carveWorms(long ctx, int chunkX, int chunkZ, int[] heights256,
                                  int[] anchorChunks, float[] anchors, long[] outMask) {
        if (!AVAILABLE || ctx == 0L) {
            return -1L;
        }
        int nAnchors = anchorChunks == null ? 0 : anchorChunks.length / 2;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment maskSeg = arena.allocate(ValueLayout.JAVA_LONG, 1024);
            MemorySegment anchorChunksSeg = nAnchors == 0 ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, anchorChunks);
            MemorySegment anchorsSeg = nAnchors == 0 ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_FLOAT, anchors);
            long carved = (long) CARVE_WORMS.invokeExact(
                MemorySegment.ofAddress(ctx), chunkX, chunkZ,
                arena.allocateFrom(ValueLayout.JAVA_INT, heights256),
                nAnchors, anchorChunksSeg, anchorsSeg, maskSeg);
            if (carved >= 0) {
                MemorySegment.copy(maskSeg, ValueLayout.JAVA_LONG, 0L, outMask, 0, 1024);
            }
            return carved;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_carve_worms failed", t);
        }
    }

    // ═══════════════════════ zstd codec ═══════════════════════

    /** Compresses with zstd. Returns null when unavailable or on error. */
    public static byte[] zstdCompress(byte[] src, int level) {
        if (!AVAILABLE || src == null) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            long bound = (long) ZSTD_BOUND.invokeExact((long) src.length);
            if (bound <= 0) {
                return null;
            }
            MemorySegment dst = arena.allocate(bound);
            MemorySegment srcSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, src);
            long written = (long) ZSTD_COMPRESS.invokeExact(dst, bound, srcSeg, (long) src.length, level);
            if (written <= 0) {
                return null;
            }
            byte[] out = new byte[(int) written];
            MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0L, out, 0, (int) written);
            return out;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_zstd_compress failed", t);
        }
    }

    /**
     * Decompresses zstd data whose original size is known exactly.
     * Returns null when unavailable, on error, or on size mismatch.
     */
    public static byte[] zstdDecompress(byte[] src, int expectedSize) {
        if (!AVAILABLE || src == null || expectedSize <= 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(expectedSize);
            MemorySegment srcSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, src);
            long restored = (long) ZSTD_DECOMPRESS.invokeExact(dst, (long) expectedSize, srcSeg, (long) src.length);
            if (restored != expectedSize) {
                return null;
            }
            byte[] out = new byte[expectedSize];
            MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0L, out, 0, expectedSize);
            return out;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_zstd_decompress failed", t);
        }
    }
}
