package com.openmason.engine.ui.layoutspike;

import com.openmason.engine.cenda.CendaKernels;

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

/**
 * FFM binding to {@code cf_layout} (Yoga in the Cenda library): one downcall
 * per layout, one upcall per measured leaf.
 */
final class YogaFlex {

    private static final int EXPECTED_ABI = 2;
    private static final MethodHandle LAYOUT;
    private static final MemorySegment MEASURE_STUB;
    private static final boolean AVAILABLE;

    private static final ThreadLocal<FlexMeasure> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<float[]> MEASURE_OUT = ThreadLocal.withInitial(() -> new float[2]);
    private static final ThreadLocal<MemorySegment[]> SCRATCH = ThreadLocal.withInitial(() -> new MemorySegment[2]);

    static {
        MethodHandle layout = null;
        MemorySegment stub = MemorySegment.NULL;
        boolean available = false;
        Path lib = CendaKernels.libraryPath().orElse(null);
        if (lib != null) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                MemorySegment abiSym = lookup.find("cf_abi_version").orElse(null);
                if (abiSym != null && (int) linker.downcallHandle(abiSym,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact() == EXPECTED_ABI) {
                    layout = linker.downcallHandle(lookup.find("cf_layout").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    stub = linker.upcallStub(MethodHandles.lookup().findStatic(YogaFlex.class, "onMeasure",
                            MethodType.methodType(void.class, int.class, float.class, int.class, float.class,
                                int.class, MemorySegment.class)),
                        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        Arena.global());
                    available = true;
                }
            } catch (Throwable t) {
                available = false;
            }
        }
        LAYOUT = layout;
        MEASURE_STUB = stub;
        AVAILABLE = available;
    }

    private YogaFlex() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Returns x, y, w, h per record (root-relative). */
    static float[] layout(FlexTree tree, float width, float height, float pointScale, FlexMeasure measure) {
        float[] out = new float[tree.count() * 4];
        layoutInto(tree, width, height, pointScale, measure, out);
        return out;
    }

    static void layoutInto(FlexTree tree, float width, float height, float pointScale, FlexMeasure measure,
                           float[] out) {
        int n = tree.count();
        MemorySegment records = scratch(0, (long) n * FlexTree.STRIDE * Float.BYTES);
        MemorySegment rects = scratch(1, (long) n * 4 * Float.BYTES);
        MemorySegment.copy(tree.records(), 0, records, ValueLayout.JAVA_FLOAT, 0, n * FlexTree.STRIDE);
        CURRENT.set(measure);
        try {
            MemorySegment callback = measure == null ? MemorySegment.NULL : MEASURE_STUB;
            int status = (int) LAYOUT.invokeExact(records, n, width, height, pointScale, callback, rects);
            if (status != 0) {
                throw new IllegalArgumentException("cf_layout rejected the tree (" + status + ")");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            CURRENT.remove();
        }
        MemorySegment.copy(rects, ValueLayout.JAVA_FLOAT, 0, out, 0, n * 4);
    }

    private static MemorySegment scratch(int slot, long bytes) {
        MemorySegment[] slots = SCRATCH.get();
        if (slots[slot] == null || slots[slot].byteSize() < bytes) {
            slots[slot] = Arena.ofAuto().allocate(Math.max(bytes, 4096), 8);
        }
        return slots[slot];
    }

    @SuppressWarnings("unused") // upcall target
    private static void onMeasure(int id, float w, int wMode, float h, int hMode, MemorySegment outWh) {
        try {
            float[] out = MEASURE_OUT.get();
            out[0] = 0;
            out[1] = 0;
            CURRENT.get().measure(id, w, wMode, h, hMode, out);
            MemorySegment wh = outWh.reinterpret(8);
            wh.set(ValueLayout.JAVA_FLOAT, 0, out[0]);
            wh.set(ValueLayout.JAVA_FLOAT, 4, out[1]);
        } catch (Throwable ignored) {
            // an exception escaping an upcall kills the JVM; the leaf measures as 0x0
        }
    }
}
