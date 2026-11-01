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
import java.nio.file.Path;

/**
 * FFM binding to the Cenda native kernels library ({@code libcenda_kernels.so},
 * built from {@code openmason-engine/cenda/native/kernels}): noise, the chunk mesher
 * and zstd. Game-specific kernels belong in the game's own library (Stonebreak's worldgen
 * moved out in #386), which can reuse the discovery rules through {@link NativeLibraryLocator}.
 *
 * Fully optional: when the library is absent or fails its ABI handshake,
 * {@link #isAvailable()} is false and callers must use their pure-Java path.
 * Nodes are immutable after creation and safe to sample from any number of
 * threads concurrently.
 *
 * Library discovery order: {@code -Dcenda.kernels.path=<file>}, then the
 * {@code CENDA_KERNELS_PATH} environment variable, then known build locations
 * relative to the working directory, then the copy packaged in the jar
 * ({@code natives/<platform>/<lib>}, extracted to a per-user cache by
 * {@link NativeLibraryExtractor}) — how a shipped game finds it.
 */
public final class CendaKernels {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaKernels.class);
    private static final int EXPECTED_ABI = 13; // 13 (#386): worldgen kernels moved to the game's own library

    /** Where the engine library is looked for — see the class comment for the order. Declared
     *  before the static loader below, which reads it. */
    static final NativeLibraryLocator.Spec LIBRARY = new NativeLibraryLocator.Spec(
        "cenda_kernels", "cenda.kernels.path", "CENDA_KERNELS_PATH",
        java.util.List.of(
            "openmason-engine/cenda/build/release/native/kernels/",
            "openmason-engine/cenda/build/debug/native/kernels/",
            "openmason-engine/cenda/build/native/kernels/",
            "../openmason-engine/cenda/build/release/native/kernels/",
            "../openmason-engine/cenda/build/debug/native/kernels/",
            "../openmason-engine/cenda/build/native/kernels/",
            "cenda/build/release/native/kernels/",
            "cenda/build/native/kernels/"),
        NativeLibraryExtractor::openResource);

    private static final boolean AVAILABLE;
    private static final String SIMD_LEVEL;
    private static final MethodHandle CREATE_TREE;
    private static final MethodHandle CREATE_FBM;
    private static final MethodHandle DESTROY;
    private static final MethodHandle GEN_2D;
    private static final MethodHandle GEN_3D;
    private static final MethodHandle MESH_CHUNK;
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
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
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

    /**
     * Per-thread grow-on-demand native scratch segments for per-chunk kernel
     * calls (mesher, zstd). Each slot is one argument role
     * of one call; contents are only valid for the duration of that downcall.
     * Replaces the confined-Arena-per-call pattern, which allocated and freed
     * ~130 KB of off-heap memory on every chunk mesh/generate/compress.
     */
    private static final int SCRATCH_SLOT_COUNT = 12;
    private static final ThreadLocal<MemorySegment[]> SCRATCH =
        ThreadLocal.withInitial(() -> new MemorySegment[SCRATCH_SLOT_COUNT]);

    private static MemorySegment scratch(int slot, long bytes) {
        MemorySegment[] slots = SCRATCH.get();
        MemorySegment segment = slots[slot];
        if (segment == null || segment.byteSize() < bytes) {
            segment = Arena.ofAuto().allocate(Math.max(bytes, 4096L), 8L);
            slots[slot] = segment;
        }
        return segment;
    }

    private static MemorySegment scratchFrom(int slot, byte[] data, int offset, int length) {
        MemorySegment segment = scratch(slot, length);
        MemorySegment.copy(data, offset, segment, ValueLayout.JAVA_BYTE, 0L, length);
        return segment;
    }

    private static MemorySegment scratchFrom(int slot, short[] data) {
        MemorySegment segment = scratch(slot, (long) data.length * Short.BYTES);
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_SHORT, 0L, data.length);
        return segment;
    }

    private static MemorySegment scratchFrom(int slot, int[] data) {
        MemorySegment segment = scratch(slot, (long) data.length * Integer.BYTES);
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_INT, 0L, data.length);
        return segment;
    }

    private static MemorySegment scratchFrom(int slot, long[] data) {
        MemorySegment segment = scratch(slot, (long) data.length * Long.BYTES);
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_LONG, 0L, data.length);
        return segment;
    }

    private static MemorySegment scratchFrom(int slot, float[] data) {
        MemorySegment segment = scratch(slot, (long) data.length * Float.BYTES);
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_FLOAT, 0L, data.length);
        return segment;
    }

    private static MemorySegment scratchFromNullable(int slot, short[] data) {
        return data == null ? MemorySegment.NULL : scratchFrom(slot, data);
    }

    private CendaKernels() {
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + name));
    }

    static Path locateLibrary() {
        return NativeLibraryLocator.locate(LIBRARY);
    }

    /**
     * Where the Cenda library would be loaded from (same search as the static
     * load), for diagnostics and the other bindings sharing the library.
     */
    public static java.util.Optional<Path> libraryPath() {
        return java.util.Optional.ofNullable(locateLibrary());
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

    /**
     * Widest chunk {@link #meshChunk} takes ({@code CK_MESH_MAX_CHUNK_SIZE}: a native row mask
     * holds one bit per z). Wider worlds mesh on the Java path.
     */
    public static final int MESH_MAX_CHUNK_SIZE = 16;

    /**
     * Culls + lights one {@code chunkSize × worldHeight × chunkSize} chunk's standard-cube faces
     * natively. Layouts and semantics per cenda/kernels.h (ck_mesh_chunk): {@code blocks} is
     * {@code chunkSize² × worldHeight} ids, the border planes {@code worldHeight × chunkSize},
     * the corner columns {@code worldHeight}, {@code heights} {@code (chunkSize + 2)²}. Returns
     * the number of quad records written to {@code outQuads} (9 floats each), or a negative
     * count if the output array is too small (retry with {@code -result * 9} floats), or
     * Integer.MIN_VALUE when the library is unavailable or the dimensions are out of range
     * ({@code chunkSize} past {@link #MESH_MAX_CHUNK_SIZE}).
     */
    public static int meshChunk(short[] blocks, int chunkSize, int worldHeight, byte[] classTable, int airId,
                                short[] planeXn, short[] planeXp,
                                short[] planeZn, short[] planeZp,
                                short[] cornerNn, short[] cornerPn,
                                short[] cornerNp, short[] cornerPp,
                                short[] heights18, int maxY, boolean smooth,
                                float[] outQuads) {
        if (!AVAILABLE || chunkSize < 1 || chunkSize > MESH_MAX_CHUNK_SIZE || worldHeight < 1) {
            return Integer.MIN_VALUE;
        }
        int capQuads = outQuads.length / 9;
        try {
            MemorySegment blocksSeg = scratchFrom(0, blocks);
            MemorySegment clsSeg = scratchFrom(1, classTable, 0, classTable.length);
            MemorySegment heightsSeg = scratchFrom(2, heights18);
            MemorySegment outSeg = scratch(3, (long) capQuads * 9 * Float.BYTES);
            int result = (int) MESH_CHUNK.invokeExact(
                blocksSeg, chunkSize, worldHeight, clsSeg, classTable.length, airId,
                scratchFromNullable(4, planeXn), scratchFromNullable(5, planeXp),
                scratchFromNullable(6, planeZn), scratchFromNullable(7, planeZp),
                scratchFromNullable(8, cornerNn), scratchFromNullable(9, cornerPn),
                scratchFromNullable(10, cornerNp), scratchFromNullable(11, cornerPp),
                heightsSeg, maxY, smooth ? 1 : 0, outSeg, capQuads);
            if (result > 0) {
                MemorySegment.copy(outSeg, ValueLayout.JAVA_FLOAT, 0L, outQuads, 0, result * 9);
            }
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_mesh_chunk failed", t);
        }
    }

    // ═══════════════════════ zstd codec ═══════════════════════

    /**
     * Worst-case compressed size for {@code srcLen} input bytes — size
     * caller-provided destination buffers with this before calling
     * {@link #zstdCompress(byte[], int, byte[], int)}. Mirrors
     * ZSTD_compressBound without a native call.
     */
    public static int zstdCompressBound(int srcLen) {
        return srcLen + (srcLen >> 8) + 512;
    }

    /**
     * Compresses {@code src[0..srcLen)} into the caller's reusable {@code dst}
     * buffer (size it with {@link #zstdCompressBound}). Returns the number of
     * compressed bytes written, or -1 when unavailable, on error, or when
     * {@code dst} is too small. Allocation-free: per-thread native scratch.
     */
    public static int zstdCompress(byte[] src, int srcLen, byte[] dst, int level) {
        if (!AVAILABLE || src == null || dst == null || srcLen <= 0 || srcLen > src.length) {
            return -1;
        }
        try {
            long bound = (long) ZSTD_BOUND.invokeExact((long) srcLen);
            if (bound <= 0) {
                return -1;
            }
            MemorySegment srcSeg = scratchFrom(0, src, 0, srcLen);
            MemorySegment dstSeg = scratch(1, bound);
            long written = (long) ZSTD_COMPRESS.invokeExact(dstSeg, bound, srcSeg, (long) srcLen, level);
            if (written <= 0 || written > dst.length) {
                return -1;
            }
            MemorySegment.copy(dstSeg, ValueLayout.JAVA_BYTE, 0L, dst, 0, (int) written);
            return (int) written;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_zstd_compress failed", t);
        }
    }

    /**
     * Decompresses {@code src[srcOff..srcOff+srcLen)} — whose original size is
     * known exactly — into the caller's reusable {@code dst} buffer. Returns
     * false when unavailable, on error, or on size mismatch. Allocation-free:
     * per-thread native scratch.
     */
    public static boolean zstdDecompress(byte[] src, int srcOff, int srcLen,
                                         byte[] dst, int expectedSize) {
        if (!AVAILABLE || src == null || dst == null || expectedSize <= 0
                || dst.length < expectedSize || srcOff < 0 || srcLen <= 0
                || srcOff + srcLen > src.length) {
            return false;
        }
        try {
            MemorySegment srcSeg = scratchFrom(0, src, srcOff, srcLen);
            MemorySegment dstSeg = scratch(1, expectedSize);
            long restored = (long) ZSTD_DECOMPRESS.invokeExact(
                dstSeg, (long) expectedSize, srcSeg, (long) srcLen);
            if (restored != expectedSize) {
                return false;
            }
            MemorySegment.copy(dstSeg, ValueLayout.JAVA_BYTE, 0L, dst, 0, expectedSize);
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException("ck_zstd_decompress failed", t);
        }
    }

    /**
     * Compresses with zstd. Returns null when unavailable, on error, or for
     * empty input (empty payloads are not a supported compression case).
     */
    public static byte[] zstdCompress(byte[] src, int level) {
        if (src == null) {
            return null;
        }
        byte[] dst = new byte[zstdCompressBound(src.length)];
        int written = zstdCompress(src, src.length, dst, level);
        if (written < 0) {
            return null;
        }
        return java.util.Arrays.copyOf(dst, written);
    }

    /**
     * Decompresses zstd data whose original size is known exactly.
     * Returns null when unavailable, on error, or on size mismatch.
     */
    public static byte[] zstdDecompress(byte[] src, int expectedSize) {
        if (src == null || expectedSize <= 0) {
            return null;
        }
        byte[] out = new byte[expectedSize];
        if (!zstdDecompress(src, 0, src.length, out, expectedSize)) {
            return null;
        }
        return out;
    }
}
