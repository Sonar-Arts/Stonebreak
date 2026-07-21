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

    static {
        boolean available = false;
        String simd = "unavailable";
        MethodHandle createTree = null;
        MethodHandle createFbm = null;
        MethodHandle destroy = null;
        MethodHandle gen2d = null;
        MethodHandle gen3d = null;
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(ValueLayout.JAVA_FLOAT, (long) xCount * yCount);
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(ValueLayout.JAVA_FLOAT, total);
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
}
