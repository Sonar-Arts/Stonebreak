package com.openmason.engine.cenda;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Argument/result view for one Lua→Java upcall. A single instance is reused
 * for every upcall (no per-call allocation); it is only valid inside
 * {@link LuaHostFunction#invoke}.
 */
public final class LuaHostCall {

    /** Upper bound on arguments and results, mirrored from lua_host.cpp. */
    public static final int MAX_VALUES = 16;
    private static final long WINDOW = MAX_VALUES * Double.BYTES;

    private MemorySegment args = MemorySegment.NULL;
    private MemorySegment results = MemorySegment.NULL;
    private int argCount;
    private int maxResults;

    void bind(MemorySegment rawArgs, int nargs, MemorySegment rawResults, int max) {
        // Reinterpreting only when the native address changes keeps the steady
        // state allocation-free: the host trampoline's arrays live on its stack
        // frame, which sits at the same address for same-depth calls.
        if (args.address() != rawArgs.address()) {
            args = rawArgs.reinterpret(WINDOW);
        }
        if (results.address() != rawResults.address()) {
            results = rawResults.reinterpret(WINDOW);
        }
        argCount = nargs;
        maxResults = Math.min(max, MAX_VALUES);
    }

    public int argCount() {
        return argCount;
    }

    /** Argument {@code i} as a number (booleans are 1/0, non-numbers NaN). */
    public double arg(int i) {
        if (i < 0 || i >= argCount) {
            throw new IndexOutOfBoundsException("argument " + i + " of " + argCount);
        }
        return args.getAtIndex(ValueLayout.JAVA_DOUBLE, i);
    }

    public void result(int i, double value) {
        if (i < 0 || i >= maxResults) {
            throw new IndexOutOfBoundsException("result " + i + " of " + maxResults);
        }
        results.setAtIndex(ValueLayout.JAVA_DOUBLE, i, value);
    }
}
