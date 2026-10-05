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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FFM binding to the retained Yoga tree compiled into the Cenda library
 * ({@code cenda/native/kernels/src/flex_host.cpp}, ABI in {@code cenda/flex.h}).
 *
 * <p>Like {@link CendaLua} and unlike {@link CendaKernels} this is <b>not</b> optional:
 * migrated UI has no Java flexbox (decided in #283, Yoga agreed with a Java engine on only
 * 71.6% of random trees). When the library is missing or its flex ABI differs,
 * {@link #isAvailable()} is false and {@link #newTree} throws
 * {@link CendaFlexUnavailableException} with the diagnostic.
 *
 * <p>Handles are {@code static final} so the JIT can inline the downcalls. Measured leaves
 * call back through one global upcall stub that dispatches to the {@link FlexMeasure} of the
 * layout running on the current thread.
 */
public final class CendaFlex {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaFlex.class);

    /** Must equal {@code CF_ABI_VERSION} in flex.h. */
    public static final int EXPECTED_ABI = 2;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout ADDR = ValueLayout.ADDRESS;

    private static final CendaFlexUnavailableException FAILURE;

    static final MethodHandle TREE_NEW;
    static final MethodHandle TREE_FREE;
    static final MethodHandle TREE_NODE_COUNT;
    static final MethodHandle NODE_NEW;
    static final MethodHandle NODE_FREE;
    static final MethodHandle NODE_INSERT;
    static final MethodHandle NODE_DETACH;
    static final MethodHandle NODES_SET_STYLE;
    static final MethodHandle NODE_MARK_DIRTY;
    static final MethodHandle TREE_LAYOUT;
    static final MethodHandle NODES_READ;
    /** The single measure upcall every tree uses. */
    static final MemorySegment MEASURE_STUB;
    /** The single baseline upcall every tree uses. */
    static final MemorySegment BASELINE_STUB;

    private static final ThreadLocal<FlexMeasure> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<float[]> MEASURE_OUT = ThreadLocal.withInitial(() -> new float[2]);

    static {
        CendaFlexUnavailableException failure = null;
        SymbolLookup lookup = null;
        MemorySegment stub = MemorySegment.NULL;
        MemorySegment baselineStub = MemorySegment.NULL;
        try {
            Path lib = CendaKernels.locateLibrary();
            if (lib == null) {
                throw new CendaFlexUnavailableException(
                    "Cenda library not found (searched -Dcenda.kernels.path, CENDA_KERNELS_PATH and "
                        + "openmason-engine/cenda/build/{release,debug}/native/kernels relative to "
                        + Path.of("").toAbsolutePath() + "). Build it: openmason-engine/cenda/build-kernels.sh");
            }
            lookup = verify(lib, EXPECTED_ABI);
            stub = LINKER.upcallStub(
                MethodHandles.lookup().findStatic(CendaFlex.class, "onMeasure",
                    MethodType.methodType(void.class, int.class, float.class, int.class, float.class, int.class,
                        MemorySegment.class)),
                FunctionDescriptor.ofVoid(I32, F32, I32, F32, I32, ADDR),
                Arena.global());
            baselineStub = LINKER.upcallStub(
                MethodHandles.lookup().findStatic(CendaFlex.class, "onBaseline",
                    MethodType.methodType(float.class, int.class, float.class, float.class)),
                FunctionDescriptor.of(F32, I32, F32, F32),
                Arena.global());
            LOGGER.info("Cenda flex (Yoga, ABI {}) loaded from {}", EXPECTED_ABI, lib);
        } catch (CendaFlexUnavailableException e) {
            failure = e;
        } catch (Throwable t) {
            failure = new CendaFlexUnavailableException("Cenda flex failed to initialize: " + t, t);
        }
        if (failure != null) {
            LOGGER.error("UI layout unavailable: {}", failure.getMessage());
            lookup = null;
        }
        FAILURE = failure;
        MEASURE_STUB = stub;
        BASELINE_STUB = baselineStub;
        TREE_NEW = handle(lookup, "cf_tree_new", FunctionDescriptor.of(ADDR, F32, ADDR, ADDR));
        TREE_FREE = handle(lookup, "cf_tree_free", FunctionDescriptor.ofVoid(ADDR));
        TREE_NODE_COUNT = handle(lookup, "cf_tree_node_count", FunctionDescriptor.of(I32, ADDR),
            Linker.Option.critical(false));
        NODE_NEW = handle(lookup, "cf_node_new", FunctionDescriptor.of(I32, ADDR));
        NODE_FREE = handle(lookup, "cf_node_free", FunctionDescriptor.of(I32, ADDR, I32));
        NODE_INSERT = handle(lookup, "cf_node_insert", FunctionDescriptor.of(I32, ADDR, I32, I32, I32));
        NODE_DETACH = handle(lookup, "cf_node_detach", FunctionDescriptor.of(I32, ADDR, I32));
        NODES_SET_STYLE = handle(lookup, "cf_nodes_set_style", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32));
        NODE_MARK_DIRTY = handle(lookup, "cf_node_mark_dirty", FunctionDescriptor.of(I32, ADDR, I32));
        TREE_LAYOUT = handle(lookup, "cf_tree_layout", FunctionDescriptor.of(I32, ADDR, I32, F32, F32));
        NODES_READ = handle(lookup, "cf_nodes_read", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR));
    }

    private CendaFlex() {
    }

    /** True when the flex host loaded and passed its ABI handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Throws the load diagnostic when the flex host is unavailable. */
    public static void require() {
        if (FAILURE != null) {
            throw new CendaFlexUnavailableException(FAILURE.getMessage(), FAILURE);
        }
    }

    /**
     * A new, empty retained tree.
     *
     * @param pointScale Yoga's pixel grid (1 rounds to whole points, 0 disables rounding)
     */
    public static FlexLayoutTree newTree(float pointScale) {
        require();
        return new FlexLayoutTree(pointScale);
    }

    /** Runs {@code layout} with {@code measure} serving this thread's measure upcalls. */
    static int withMeasure(FlexMeasure measure, java.util.function.IntSupplier layout) {
        FlexMeasure previous = CURRENT.get();
        CURRENT.set(measure);
        try {
            return layout.getAsInt();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Opens {@code lib} and checks its flex ABI. Separate from the static load so tests can
     * prove the failure diagnostics against any file.
     */
    static SymbolLookup verify(Path lib, int expectedAbi) {
        if (!Files.isRegularFile(lib)) {
            throw new CendaFlexUnavailableException("Cenda library not found at " + lib.toAbsolutePath());
        }
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(lib, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new CendaFlexUnavailableException("Cannot load " + lib + " as a native library: "
                + e.getMessage(), e);
        }
        MemorySegment abiSymbol = lookup.find("cf_abi_version").orElseThrow(() -> new CendaFlexUnavailableException(
            "Cenda library at " + lib + " has no flex host (missing symbol cf_abi_version); rebuild with "
                + "openmason-engine/cenda/build-kernels.sh"));
        int abi;
        try {
            abi = (int) LINKER.downcallHandle(abiSymbol, FunctionDescriptor.of(I32)).invokeExact();
        } catch (Throwable t) {
            throw new CendaFlexUnavailableException("cf_abi_version() failed in " + lib, t);
        }
        if (abi != expectedAbi) {
            throw new CendaFlexUnavailableException("Cenda flex host at " + lib + " has ABI " + abi
                + " but this build expects " + expectedAbi
                + ". The library is stale or from another branch: rebuild with openmason-engine/cenda/build-kernels.sh");
        }
        return lookup;
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd,
                                       Linker.Option... options) {
        if (lookup == null) {
            return null;
        }
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + name));
        return LINKER.downcallHandle(symbol, fd, options);
    }

    @SuppressWarnings("unused") // upcall target
    private static float onBaseline(int id, float w, float h) {
        try {
            FlexMeasure measure = CURRENT.get();
            return measure == null ? h : measure.baseline(id, w, h);
        } catch (Throwable t) {
            LOGGER.error("UI baseline failed for leaf {}", id, t);
            return h;
        }
    }

    @SuppressWarnings("unused") // upcall target
    private static void onMeasure(int id, float w, int wMode, float h, int hMode, MemorySegment outWh) {
        try {
            float[] out = MEASURE_OUT.get();
            out[0] = 0;
            out[1] = 0;
            FlexMeasure measure = CURRENT.get();
            if (measure != null) {
                measure.measure(id, w, wMode, h, hMode, out);
            }
            MemorySegment wh = outWh.reinterpret(8);
            wh.set(F32, 0, out[0]);
            wh.set(F32, 4, out[1]);
        } catch (Throwable t) {
            // An exception escaping an upcall kills the JVM; the leaf measures as 0x0 instead.
            LOGGER.error("UI text measure failed for leaf {}", id, t);
        }
    }
}
