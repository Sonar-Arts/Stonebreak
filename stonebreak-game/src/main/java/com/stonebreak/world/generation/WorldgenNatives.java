package com.stonebreak.world.generation;

import com.openmason.engine.cenda.NativeLibraryLocator;
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
import java.util.List;
import java.util.Optional;

/**
 * FFM binding to Stonebreak's own native library, {@code libstonebreak_worldgen}, built from
 * {@code stonebreak-game/native} (#386): the native worm carver and the fused chunk generator,
 * which used to live in the engine's Cenda kernels. Layouts and semantics per
 * {@code stonebreak-game/native/include/stonebreak/worldgen.h}.
 *
 * <p>Fully optional, like the engine library: when it is absent or fails its ABI handshake,
 * {@link #isAvailable()} is false and generation stays on the Java carver and the legacy path.
 * Callers additionally gate on the native noise backend ({@code TerrainNoise}), because the
 * kernels' channels must match the noise the engine library hands Java.
 *
 * <p>Library discovery ({@link NativeLibraryLocator}): {@code -Dstonebreak.worldgen.path=<file>},
 * then {@code STONEBREAK_WORLDGEN_PATH}, then {@code stonebreak-game/native/build/<preset>/}
 * relative to the working directory, then the copy the game POM packages into its jar.
 */
public final class WorldgenNatives {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldgenNatives.class);
    private static final int EXPECTED_ABI = 2; // 1 (#386): split out of the engine's CK ABI 12; 2: distilled Standard shape (11 channels + constants + curve table)

    /** Where the library is looked for. */
    static final NativeLibraryLocator.Spec LIBRARY = new NativeLibraryLocator.Spec(
        "stonebreak_worldgen", "stonebreak.worldgen.path", "STONEBREAK_WORLDGEN_PATH",
        List.of(
            "stonebreak-game/native/build/release/",
            "stonebreak-game/native/build/debug/",
            "native/build/release/",
            "native/build/debug/",
            "../stonebreak-game/native/build/release/",
            "../stonebreak-game/native/build/debug/"),
        resource -> WorldgenNatives.class.getResourceAsStream("/" + resource));

    private static final boolean AVAILABLE;
    private static final Path LIBRARY_PATH;
    private static final MethodHandle TERRAIN_CREATE;
    private static final MethodHandle TERRAIN_DESTROY;
    private static final MethodHandle CARVE_WORMS;
    private static final MethodHandle CHUNKGEN_CREATE;
    private static final MethodHandle CHUNKGEN_DESTROY;
    private static final MethodHandle GENERATE_CHUNK;

    static {
        boolean available = false;
        Path libPath = null;
        MethodHandle terrainCreate = null;
        MethodHandle terrainDestroy = null;
        MethodHandle carveWorms = null;
        MethodHandle chunkGenCreate = null;
        MethodHandle chunkGenDestroy = null;
        MethodHandle generateChunk = null;
        try {
            libPath = NativeLibraryLocator.locate(LIBRARY);
            if (libPath != null) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.global());

                MethodHandle abiVersion = linker.downcallHandle(
                    find(lookup, "sbw_abi_version"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                terrainCreate = linker.downcallHandle(
                    find(lookup, "sbw_terrain_create"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT));
                terrainDestroy = linker.downcallHandle(
                    find(lookup, "sbw_terrain_destroy"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                carveWorms = linker.downcallHandle(
                    find(lookup, "sbw_carve_worms"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
                chunkGenCreate = linker.downcallHandle(
                    find(lookup, "sbw_chunkgen_create"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                chunkGenDestroy = linker.downcallHandle(
                    find(lookup, "sbw_chunkgen_destroy"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                generateChunk = linker.downcallHandle(
                    find(lookup, "sbw_generate_chunk"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                int abi = (int) abiVersion.invokeExact();
                if (abi != EXPECTED_ABI) {
                    LOGGER.warn("Stonebreak worldgen natives at {} have ABI {} (expected {}); ignoring library",
                        libPath, abi, EXPECTED_ABI);
                } else {
                    available = true;
                    LOGGER.info("Stonebreak worldgen natives loaded from {}", libPath);
                }
            } else {
                LOGGER.info("Stonebreak worldgen natives not found; the Java carver and legacy chunk path will be used");
            }
        } catch (Throwable t) {
            LOGGER.warn("Stonebreak worldgen natives failed to load; the Java carver and legacy chunk path will be used", t);
            available = false;
        }
        AVAILABLE = available;
        LIBRARY_PATH = libPath;
        TERRAIN_CREATE = terrainCreate;
        TERRAIN_DESTROY = terrainDestroy;
        CARVE_WORMS = carveWorms;
        CHUNKGEN_CREATE = chunkGenCreate;
        CHUNKGEN_DESTROY = chunkGenDestroy;
        GENERATE_CHUNK = generateChunk;
    }

    /**
     * Per-thread grow-on-demand native scratch segments for the per-chunk calls. Each slot is
     * one argument role of one call; contents are only valid for the duration of that downcall.
     */
    private static final int SCRATCH_SLOT_COUNT = 5;
    private static final ThreadLocal<MemorySegment[]> SCRATCH =
        ThreadLocal.withInitial(() -> new MemorySegment[SCRATCH_SLOT_COUNT]);

    private WorldgenNatives() {
    }

    private static MemorySegment scratch(int slot, long bytes) {
        MemorySegment[] slots = SCRATCH.get();
        MemorySegment segment = slots[slot];
        if (segment == null || segment.byteSize() < bytes) {
            segment = Arena.ofAuto().allocate(Math.max(bytes, 4096L), 8L);
            slots[slot] = segment;
        }
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

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + name));
    }

    /** True when the library loaded and passed its ABI handshake. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** The library that was loaded (or rejected), for diagnostics; empty when none was found. */
    public static Optional<Path> libraryPath() {
        return Optional.ofNullable(LIBRARY_PATH);
    }

    // ═══════════════════════ Worm carver ═══════════════════════

    /**
     * Creates a native terrain context (shape channel nodes + worm state). Returns 0 when
     * unavailable or on invalid input. Destroy with {@link #terrainDestroy}.
     *
     * <p>Since ABI 2 the "spline" slots carry the distilled shape
     * ({@link com.stonebreak.world.generation.heightmap.DistilledShape.NativeArgs}): {@code splineXs} =
     * formula constants, {@code splineYs} = height-curve table, {@code splineSizes} = {channels,
     * constants, table length}, {@code detailAmplitude} = detail blocks.
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
            throw new IllegalStateException("sbw_terrain_create failed", t);
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
            throw new IllegalStateException("sbw_terrain_destroy failed", t);
        }
    }

    /**
     * Runs the native worm carver for one chunk. {@code outMask} (1024 longs) receives the carve
     * mask in java.util.BitSet.valueOf layout, bit index {@code (x<<12)|(y<<4)|z}.
     * anchorChunks/anchors may be null when empty. Returns the number of carved cells, or
     * negative on error/unavailable.
     */
    public static long carveWorms(long ctx, int chunkX, int chunkZ, int[] heights256,
                                  int[] anchorChunks, float[] anchors, long[] outMask) {
        if (!AVAILABLE || ctx == 0L) {
            return -1L;
        }
        int nAnchors = anchorChunks == null ? 0 : anchorChunks.length / 2;
        try {
            MemorySegment maskSeg = scratch(0, 1024L * Long.BYTES);
            MemorySegment anchorChunksSeg = nAnchors == 0 ? MemorySegment.NULL
                : scratchFrom(1, anchorChunks);
            MemorySegment anchorsSeg = nAnchors == 0 ? MemorySegment.NULL
                : scratchFrom(2, anchors);
            long carved = (long) CARVE_WORMS.invokeExact(
                MemorySegment.ofAddress(ctx), chunkX, chunkZ,
                scratchFrom(3, heights256),
                nAnchors, anchorChunksSeg, anchorsSeg, maskSeg);
            if (carved >= 0) {
                MemorySegment.copy(maskSeg, ValueLayout.JAVA_LONG, 0L, outMask, 0, 1024);
            }
            return carved;
        } catch (Throwable t) {
            throw new IllegalStateException("sbw_carve_worms failed", t);
        }
    }

    // ═══════════════════ Fused chunk generator ═══════════════════

    /**
     * Creates a fused chunk-generation context (terrain channels/splines + cave-density nodes +
     * block/biome tables). Returns 0 when unavailable or on invalid input. Destroy with
     * {@link #chunkGenDestroy}.
     */
    public static long chunkGenCreate(long seed,
                                      int[] chSeeds, int[] chOctaves,
                                      float[] chGain, float[] chLacunarity, float[] chFreq,
                                      int[] chXoff, int[] chZoff,
                                      double[] splineXs, double[] splineYs, int[] splineSizes,
                                      float detailAmplitude,
                                      int[] densitySeeds, int[] densityOctaves,
                                      float[] densityGain, float[] densityLacunarity,
                                      float[] densityFreq,
                                      double[] cheeseSplineXs, double[] cheeseSplineYs,
                                      int[] cheeseSplineSizes,
                                      int[] blockIds,
                                      short[] biomeSurfaceId, short[] biomeSubsurfaceId,
                                      float[] biomeCaveIntensity, float[] biomeOverhangIntensity,
                                      byte[] biomeFlags,
                                      int magmaFeatureHash, float magmaChance,
                                      byte[] opacityTable) {
        if (!AVAILABLE) {
            return 0L;
        }
        int nBiomes = biomeSurfaceId.length;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctx = (MemorySegment) CHUNKGEN_CREATE.invokeExact(
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
                detailAmplitude,
                arena.allocateFrom(ValueLayout.JAVA_INT, densitySeeds),
                arena.allocateFrom(ValueLayout.JAVA_INT, densityOctaves),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, densityGain),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, densityLacunarity),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, densityFreq),
                arena.allocateFrom(ValueLayout.JAVA_DOUBLE, cheeseSplineXs),
                arena.allocateFrom(ValueLayout.JAVA_DOUBLE, cheeseSplineYs),
                arena.allocateFrom(ValueLayout.JAVA_INT, cheeseSplineSizes),
                arena.allocateFrom(ValueLayout.JAVA_INT, blockIds),
                nBiomes,
                arena.allocateFrom(ValueLayout.JAVA_SHORT, biomeSurfaceId),
                arena.allocateFrom(ValueLayout.JAVA_SHORT, biomeSubsurfaceId),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, biomeCaveIntensity),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, biomeOverhangIntensity),
                arena.allocateFrom(ValueLayout.JAVA_BYTE, biomeFlags),
                magmaFeatureHash, magmaChance,
                arena.allocateFrom(ValueLayout.JAVA_BYTE, opacityTable),
                opacityTable.length);
            return ctx.address();
        } catch (Throwable t) {
            throw new IllegalStateException("sbw_chunkgen_create failed", t);
        }
    }

    /** Destroys a fused chunk-generation context. Safe on 0. */
    public static void chunkGenDestroy(long ctx) {
        if (!AVAILABLE || ctx == 0L) {
            return;
        }
        try {
            CHUNKGEN_DESTROY.invokeExact(MemorySegment.ofAddress(ctx));
        } catch (Throwable t) {
            throw new IllegalStateException("sbw_chunkgen_destroy failed", t);
        }
    }

    /**
     * Generates one chunk's full block volume natively (worm + cavern carving, formations, cave
     * density, biome fill, sky heightmap). {@code heights256} and {@code biomes256} are indexed
     * {@code [x*16+z]} (biomes as BiomeType ordinals); {@code outBlocks} (65536 shorts) receives
     * ids in mesher/CCO layout {@code y*256 + z*16 + x}; {@code outHeightmap} (256 ints,
     * {@code [z*16+x]}, nullable) receives topOpaqueY+1 per column. Returns the non-air block
     * count, or negative on error/unavailable.
     *
     * <p>The kernel emits a FIXED 16x256x16 volume (Stonebreak's Standard frame); the explicit
     * length check fails loudly instead of silently filling part of a differently sized buffer.
     *
     * @param extraCarveMask 1024 longs (bit = {@code (x<<12)|(y<<4)|z}) OR'd into the kernel's
     *                       own carve mask, or null for none
     */
    public static long generateChunk(long ctx, int chunkX, int chunkZ,
                                     int[] heights256, int[] biomes256,
                                     long[] extraCarveMask,
                                     short[] outBlocks, int[] outHeightmap) {
        if (outBlocks.length != 65536) {
            throw new IllegalArgumentException(
                "sbw_generate_chunk emits a fixed 16x256x16 volume; outBlocks must be 65536 shorts, was "
                    + outBlocks.length);
        }
        if (!AVAILABLE || ctx == 0L) {
            return -1L;
        }
        try {
            // invokeExact matches the STATIC types of its arguments, so the null case has to
            // be a MemorySegment local — a ternary here widens to Object and throws
            // WrongMethodTypeException at the call.
            MemorySegment maskSeg = extraCarveMask == null
                ? MemorySegment.NULL
                : scratchFrom(4, extraCarveMask);
            MemorySegment blocksSeg = scratch(0, 65536L * Short.BYTES);
            MemorySegment heightmapSeg = outHeightmap == null ? MemorySegment.NULL
                : scratch(1, 256L * Integer.BYTES);
            long nonAir = (long) GENERATE_CHUNK.invokeExact(
                MemorySegment.ofAddress(ctx), chunkX, chunkZ,
                scratchFrom(2, heights256),
                scratchFrom(3, biomes256),
                maskSeg,
                blocksSeg, heightmapSeg);
            if (nonAir >= 0) {
                MemorySegment.copy(blocksSeg, ValueLayout.JAVA_SHORT, 0L, outBlocks, 0, 65536);
                if (outHeightmap != null) {
                    MemorySegment.copy(heightmapSeg, ValueLayout.JAVA_INT, 0L, outHeightmap, 0, 256);
                }
            }
            return nonAir;
        } catch (Throwable t) {
            throw new IllegalStateException("sbw_generate_chunk failed", t);
        }
    }
}
