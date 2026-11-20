package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.openmason.engine.cenda.NativeLibraryLocator;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FFM binding of {@code libstonebreak_terrain}'s standalone pipeline ({@code sbtg_terrain_*},
 * {@code stonebreak-game/native/include/stonebreak/terrain.h}): the DaedalusTGM tile pipeline in-process on Open
 * Mason's engine runtime (Cenda {@code ci_*}), no Python. One instance = one engine runtime; its lanes
 * ({@link #setLanes}) are tile builders on their own CUDA streams that {@link NativeTerrainService} drives from one worker
 * thread each (calls on one lane are serialised natively).
 */
final class NativeTerrain implements AutoCloseable {

    /** {@code SBTG_ABI_VERSION} this binding speaks. */
    static final int EXPECTED_ABI = 2;

    private static final NativeLibraryLocator.Spec LIBRARY = new NativeLibraryLocator.Spec(
            "stonebreak_terrain", "stonebreak.terrain.path", "STONEBREAK_TERRAIN_PATH",
            List.of(
                    "stonebreak-game/native/build/release/src/terrain/",
                    "stonebreak-game/native/build/agent-terrain/src/terrain/",
                    "native/build/release/src/terrain/",
                    "../stonebreak-game/native/build/release/src/terrain/"),
            resource -> NativeTerrain.class.getResourceAsStream("/" + resource));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final String UNAVAILABLE_REASON;
    private static final Path LIBRARY_PATH;
    private static MethodHandle ABI, LAST_ERROR, OPEN, CLOSE, INFO, WARMUP, SET_WORLD, TILE, TILE_PLANES,
            SURFACE_COUNT, SURFACE_NAME, SURFACE, TILE_FREE, STATS;
    /** Lanes (additive to ABI 2; null with an older library = one lane). */
    private static MethodHandle SET_LANES, LANES, TILE_LANE;

    /** sbtg_terrain_options: device, flags, cache_dir, nvrtc_path, reserved[6]. */
    private static final MemoryLayout OPTIONS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("device"), ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.ADDRESS.withName("cache_dir"), ValueLayout.ADDRESS.withName("nvrtc_path"),
            MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_LONG).withName("reserved"));

    static {
        String reason = null;
        Path path = null;
        try {
            path = NativeLibraryLocator.locate(LIBRARY);
            if (path == null) {
                reason = "libstonebreak_terrain not found";
            } else {
                SymbolLookup l = SymbolLookup.libraryLookup(path, Arena.global());
                ABI = down(l, "sbtg_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
                int abi = (int) ABI.invokeExact();
                if (abi != EXPECTED_ABI) {
                    reason = "sbtg ABI " + abi + " (expected " + EXPECTED_ABI + ") at " + path;
                } else {
                    bind(l);
                }
            }
        } catch (Throwable t) {
            reason = "failed to load libstonebreak_terrain: " + t;
        }
        UNAVAILABLE_REASON = reason;
        LIBRARY_PATH = path;
    }

    private static MethodHandle down(SymbolLookup l, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(l.find(name)
                .orElseThrow(() -> new IllegalStateException("missing native symbol " + name)), fd);
    }

    private static void bind(SymbolLookup l) {
        var A = ValueLayout.ADDRESS;
        var I = ValueLayout.JAVA_INT;
        var J = ValueLayout.JAVA_LONG;
        LAST_ERROR = down(l, "sbtg_last_error", FunctionDescriptor.of(A));
        OPEN = down(l, "sbtg_terrain_open", FunctionDescriptor.of(A, A, A, A));
        CLOSE = down(l, "sbtg_terrain_close", FunctionDescriptor.ofVoid(A));
        INFO = down(l, "sbtg_terrain_info", FunctionDescriptor.of(A, A));
        WARMUP = down(l, "sbtg_terrain_warmup", FunctionDescriptor.of(I, A));
        SET_WORLD = down(l, "sbtg_terrain_set_world", FunctionDescriptor.of(I, A, I, A));
        TILE = down(l, "sbtg_terrain_tile", FunctionDescriptor.of(I, A, I, J, I, I, I, A));
        TILE_PLANES = down(l, "sbtg_tile_planes", FunctionDescriptor.of(A, A, A, A, A));
        SURFACE_COUNT = down(l, "sbtg_tile_surface_count", FunctionDescriptor.of(I, A));
        SURFACE_NAME = down(l, "sbtg_tile_surface_name", FunctionDescriptor.of(A, A, I));
        SURFACE = down(l, "sbtg_tile_surface", FunctionDescriptor.of(A, A, I));
        TILE_FREE = down(l, "sbtg_tile_free", FunctionDescriptor.ofVoid(A));
        STATS = down(l, "sbtg_terrain_stats", FunctionDescriptor.of(I, A, A, I));
        if (l.find("sbtg_terrain_set_lanes").isPresent()) {
            SET_LANES = down(l, "sbtg_terrain_set_lanes", FunctionDescriptor.of(I, A, I));
            LANES = down(l, "sbtg_terrain_lanes", FunctionDescriptor.of(I, A));
            TILE_LANE = down(l, "sbtg_terrain_tile_lane", FunctionDescriptor.of(I, A, I, I, J, I, I, I, A));
        }
    }

    /**
     * Lanes: {@code n} tile builders on their own streams that run concurrently (one thread each) and share the models
     * and window caches; tiles are bit-identical whatever lane builds them. Before {@link #warmup()}; a library without
     * lanes stays at one. Returns the lane count now in effect.
     */
    int setLanes(int n) {
        if (SET_LANES == null) {
            return 1;
        }
        try {
            if (n > 1 && (int) LANES.invokeExact(handle) == 1) {
                check((int) SET_LANES.invokeExact(handle, n), "sbtg_terrain_set_lanes");
            }
            return (int) LANES.invokeExact(handle);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    int lanes() {
        if (LANES == null) {
            return 1;
        }
        try {
            return (int) LANES.invokeExact(handle);
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Null when the library loaded with the expected ABI, else why not. */
    static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    static Path libraryPath() {
        return LIBRARY_PATH;
    }

    /** A finished tile: {@code planes[p][row * cols + col]} and the surface channels by wire name (uint8). */
    record Tile(int rows, int cols, short[][] planes, Map<String, byte[]> surface) {
    }

    private final MemorySegment handle;
    private final String info;

    private NativeTerrain(MemorySegment handle) throws Throwable {
        this.handle = handle;
        this.info = ((MemorySegment) INFO.invokeExact(handle)).reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Opens the pipeline: {@code cendaLib} = the engine library (libcenda_kernels with ci ABI 2), {@code nativeDir} =
     * the model's release export (manifest.json + .cim networks).
     */
    static NativeTerrain open(Path cendaLib, Path nativeDir, int device, Path kernelCache, Path nvrtc) {
        if (UNAVAILABLE_REASON != null) {
            throw new IllegalStateException(UNAVAILABLE_REASON);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opts = arena.allocate(OPTIONS);
            opts.set(ValueLayout.JAVA_INT, 0, device);
            opts.set(ValueLayout.ADDRESS, 8, kernelCache == null ? MemorySegment.NULL
                    : arena.allocateFrom(kernelCache.toString()));
            opts.set(ValueLayout.ADDRESS, 16, nvrtc == null ? MemorySegment.NULL : arena.allocateFrom(nvrtc.toString()));
            MemorySegment h = (MemorySegment) OPEN.invokeExact(arena.allocateFrom(cendaLib.toString()),
                    arena.allocateFrom(nativeDir.toString()), opts);
            if (h.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("sbtg_terrain_open: " + lastError());
            }
            return new NativeTerrain(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("sbtg_terrain_open failed", t);
        }
    }

    private static String lastError() {
        try {
            return ((MemorySegment) LAST_ERROR.invokeExact()).reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "(no error text: " + t + ")";
        }
    }

    /** {"model_id", "abi", "device", "manifest", "methods"}. */
    String info() {
        return info;
    }

    void warmup() {
        try {
            check((int) WARMUP.invokeExact(handle), "sbtg_terrain_warmup");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    void setWorld(int index, String worldJson) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) SET_WORLD.invokeExact(handle, index, arena.allocateFrom(worldJson)), "sbtg_terrain_set_world");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    Tile tile(int world, long seed, int tileX, int tileZ, int lod) {
        return tile(0, world, seed, tileX, tileZ, lod);
    }

    /** A tile on {@code lane}: calls on different lanes run concurrently, on one lane they are serialised. */
    Tile tile(int lane, int world, long seed, int tileX, int tileZ, int lod) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            if (lane == 0 || TILE_LANE == null) {
                check((int) TILE.invokeExact(handle, world, seed, tileX, tileZ, lod, out), "sbtg_terrain_tile");
            } else {
                check((int) TILE_LANE.invokeExact(handle, lane, world, seed, tileX, tileZ, lod, out),
                        "sbtg_terrain_tile_lane");
            }
            MemorySegment tile = out.get(ValueLayout.ADDRESS, 0);
            try {
                MemorySegment dims = arena.allocate(ValueLayout.JAVA_INT, 3);
                MemorySegment data = (MemorySegment) TILE_PLANES.invokeExact(tile, dims,
                        dims.asSlice(4), dims.asSlice(8));
                int planes = dims.getAtIndex(ValueLayout.JAVA_INT, 0);
                int rows = dims.getAtIndex(ValueLayout.JAVA_INT, 1);
                int cols = dims.getAtIndex(ValueLayout.JAVA_INT, 2);
                int cells = rows * cols;
                MemorySegment all = data.reinterpret((long) planes * cells * 2);
                short[][] p = new short[planes][cells];
                for (int k = 0; k < planes; k++) {
                    MemorySegment.copy(all, ValueLayout.JAVA_SHORT.withOrder(ByteOrder.nativeOrder()),
                            (long) k * cells * 2, p[k], 0, cells);
                }
                Map<String, byte[]> surface = new LinkedHashMap<>();
                int n = (int) SURFACE_COUNT.invokeExact(tile);
                for (int i = 0; i < n; i++) {
                    String name = ((MemorySegment) SURFACE_NAME.invokeExact(tile, i)).reinterpret(Long.MAX_VALUE)
                            .getString(0);
                    MemorySegment plane = ((MemorySegment) SURFACE.invokeExact(tile, i)).reinterpret(cells);
                    byte[] b = new byte[cells];
                    MemorySegment.copy(plane, ValueLayout.JAVA_BYTE, 0, b, 0, cells);
                    surface.put(name, b);
                }
                return new Tile(rows, cols, p, surface);
            } finally {
                TILE_FREE.invokeExact(tile);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    String stats() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) STATS.invokeExact(handle, MemorySegment.NULL, 0);
            MemorySegment buf = arena.allocate(n + 1L);
            int ignored = (int) STATS.invokeExact(handle, buf, n + 1);
            return buf.getString(0);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static void check(int r, String what) {
        if (r != 0) {
            throw new IllegalStateException(what + " (" + r + "): " + lastError());
        }
    }

    @Override
    public void close() {
        try {
            CLOSE.invokeExact(handle);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
