package com.openmason.engine.cenda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * One sandboxed Lua 5.5 state. Single-threaded: create, call and close it on
 * one thread (its confined arena enforces this for argument buffers).
 *
 * <p>Calls are allocation-free in steady state: arguments go in through
 * {@link #setArg}, results come out through {@link #result}, and host upcalls
 * reuse one {@link LuaHostCall} per nesting depth.
 *
 * <p>Status codes mirror {@code CL_*} in lua_host.h.
 */
public final class LuaState implements AutoCloseable {

    public static final int OK = 0;
    public static final int YIELD = 1;
    public static final int ERR_RUN = 2;
    public static final int ERR_SYNTAX = 3;
    public static final int ERR_MEM = 4;
    public static final int ERR_ERR = 5;
    public static final int ERR_BUDGET = 16;
    public static final int ERR_ARG = 17;
    public static final int ERR_HOST = 18;
    public static final int ERR_DEADLINE = 19;

    public static final int THREAD_SUSPENDED = 0;
    public static final int THREAD_DEAD = 3;

    private static final int MAX_VALUES = LuaHostCall.MAX_VALUES;
    private static final int MAX_HOST_DEPTH = 32;

    private static final Object HOST_LOCK = new Object();
    private static volatile HostEntry[] hosts = new HostEntry[64];
    private static int hostCount = 1; // id 0 is never handed out

    private record HostEntry(LuaState owner, LuaHostFunction fn, LuaValueFunction vfn) {
    }

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment handle;
    private final MemorySegment args;
    private final MemorySegment results;
    private final MemorySegment resultCount;
    private final LuaHostCall[] hostCalls = new LuaHostCall[MAX_HOST_DEPTH];
    private final LuaValueReader[] hostArgs = new LuaValueReader[MAX_HOST_DEPTH];
    private final LuaValueWriter[] hostResults = new LuaValueWriter[MAX_HOST_DEPTH];
    private MemorySegment hostBuffer = MemorySegment.NULL;
    private final LuaValueWriter callArgs;
    private final LuaValueReader callResults = new LuaValueReader();
    private final MemorySegment outPtr;
    private final MemorySegment outLen;
    private final MemorySegment outCount;
    private int hostDepth;
    private int lastResultCount;
    private boolean closed;

    LuaState(long memLimitBytes) {
        try {
            handle = (MemorySegment) CendaLua.STATE_NEW.invokeExact(memLimitBytes);
        } catch (Throwable t) {
            throw new IllegalStateException("cl_state_new failed", t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            arena.close();
            throw new IllegalStateException("cl_state_new returned NULL (memory limit " + memLimitBytes
                + " bytes is too small for the base libraries?)");
        }
        args = arena.allocate(ValueLayout.JAVA_DOUBLE, MAX_VALUES);
        results = arena.allocate(ValueLayout.JAVA_DOUBLE, MAX_VALUES);
        resultCount = arena.allocate(ValueLayout.JAVA_INT);
        callArgs = new LuaValueWriter(arena, 1024);
        outPtr = arena.allocate(ValueLayout.JAVA_LONG); // a pointer; read as a long so reads allocate nothing
        outLen = arena.allocate(ValueLayout.JAVA_INT);
        outCount = arena.allocate(ValueLayout.JAVA_INT);
    }

    // ─────────────────────────── chunks and calls ───────────────────────────

    /** Compile and run a text chunk in {@code envRef} (0 = this state's globals). */
    public int run(String source, String chunkName, int envRef) {
        try (Arena call = Arena.ofConfined()) {
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            MemorySegment src = call.allocate(bytes.length + 1L);
            MemorySegment.copy(bytes, 0, src, ValueLayout.JAVA_BYTE, 0, bytes.length);
            return (int) CendaLua.RUN.invokeExact(handle, src, (long) bytes.length,
                call.allocateFrom("=" + chunkName), envRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int run(String source) {
        return run(source, "chunk", 0);
    }

    /** Registry ref to function {@code name} in {@code envRef}; {@code <= 0} if absent. */
    public int refFunction(int envRef, String name) {
        try (Arena call = Arena.ofConfined()) {
            return (int) CendaLua.REF_FUNCTION.invokeExact(handle, envRef, call.allocateFrom(name));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setArg(int i, double value) {
        args.setAtIndex(ValueLayout.JAVA_DOUBLE, i, value);
    }

    public double result(int i) {
        return results.getAtIndex(ValueLayout.JAVA_DOUBLE, i);
    }

    /** Protected call with the first {@code nargs} {@link #setArg} values. */
    public int call(int fnRef, int nargs, int nresults) {
        try {
            return (int) CendaLua.CALL.invokeExact(handle, fnRef, args, nargs, results, nresults);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Convenience single-result call (allocates its varargs array). */
    public double call1(int fnRef, double... values) {
        for (int i = 0; i < values.length; i++) {
            setArg(i, values[i]);
        }
        int status = call(fnRef, values.length, 1);
        if (status != OK) {
            throw new IllegalStateException("Lua call failed (" + status + "): " + lastError());
        }
        return result(0);
    }

    // ───────────────────────────── typed values ─────────────────────────────

    /**
     * The reusable argument writer of {@link #callValues}: {@code reset()} it, write the
     * arguments, then call. Shared by every value call on this state.
     */
    public LuaValueWriter args() {
        return callArgs.reset();
    }

    /**
     * Protected call of {@code fnRef} with the values written to {@link #args()} since its reset.
     * On {@link #OK} the results are in {@link #results()} until the next call on this state.
     * Allocation-free when the arguments and results are numbers or booleans.
     */
    public int callValues(int fnRef, int maxResults) {
        LuaValueWriter a = callArgs;
        try {
            int status = (int) CendaLua.CALL_V.invokeExact(handle, fnRef, a.segment(), a.length(), a.count(),
                maxResults, outPtr, outLen, outCount);
            if (status == OK) {
                callResults.bind(outPtr.get(ValueLayout.JAVA_LONG, 0), outLen.get(ValueLayout.JAVA_INT, 0),
                    outCount.get(ValueLayout.JAVA_INT, 0));
            } else {
                callResults.bind(0, 0, 0);
            }
            return status;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Results of the last successful {@link #callValues}. */
    public LuaValueReader results() {
        return callResults;
    }

    /** Exposes a value-typed {@code fn} to Lua as {@code name} in {@code envRef}. */
    public int registerValues(int envRef, String name, LuaValueFunction fn) {
        long id = addHost(new HostEntry(this, null, fn));
        try (Arena call = Arena.ofConfined()) {
            return (int) CendaLua.REGISTER_HOST_V.invokeExact(handle, envRef, call.allocateFrom(name),
                CendaLua.HOST_V_STUB, id);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public String lastError() {
        try {
            return CendaLua.string((MemorySegment) CendaLua.LAST_ERROR.invokeExact(handle));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ───────────────────────────── environments ─────────────────────────────

    /** Isolated environment (own globals and library tables) on this state. */
    public int newEnv() {
        try {
            return (int) CendaLua.ENV_NEW.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void unref(int ref) {
        try {
            CendaLua.UNREF.invokeExact(handle, ref);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ───────────────────────────── host bridges ─────────────────────────────

    /** Exposes {@code fn} to Lua as {@code name} in {@code envRef}. */
    public int register(int envRef, String name, LuaHostFunction fn) {
        long id = addHost(new HostEntry(this, fn, null));
        try (Arena call = Arena.ofConfined()) {
            return (int) CendaLua.REGISTER_HOST.invokeExact(handle, envRef, call.allocateFrom(name),
                CendaLua.HOST_STUB, id);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Binds a native float buffer of {@code capacity} floats as Lua table
     * {@code name} ({@code emit}, {@code put}, {@code get}, {@code len}).
     */
    public LuaFloatBuffer bindBuffer(int envRef, String name, int capacity) {
        MemorySegment data = arena.allocate(ValueLayout.JAVA_FLOAT, capacity);
        try (Arena call = Arena.ofConfined()) {
            int id = (int) CendaLua.BIND_BUFFER.invokeExact(handle, envRef, call.allocateFrom(name), data, capacity);
            if (id < 0) {
                throw new IllegalStateException("cl_bind_buffer failed (" + -id + "): " + lastError());
            }
            return new LuaFloatBuffer(this, id, data);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int bufferCursor(int buffer) {
        try {
            return (int) CendaLua.BUFFER_CURSOR.invokeExact(handle, buffer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    void bufferReset(int buffer) {
        try {
            CendaLua.BUFFER_RESET.invokeExact(handle, buffer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ────────────────────────────── coroutines ──────────────────────────────

    public int newThread(int fnRef) {
        try {
            return (int) CendaLua.THREAD_NEW.invokeExact(handle, fnRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Resume with the first {@code nargs} args; see {@link #lastResultCount()}. */
    public int resume(int threadRef, int nargs, int maxResults) {
        try {
            int status = (int) CendaLua.RESUME.invokeExact(handle, threadRef, args, nargs, results,
                Math.min(maxResults, MAX_VALUES), resultCount);
            lastResultCount = resultCount.get(ValueLayout.JAVA_INT, 0);
            return status;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int lastResultCount() {
        return lastResultCount;
    }

    /** Cancels the coroutine, running its pending {@code <close>} handlers. */
    public int closeThread(int threadRef) {
        try {
            return (int) CendaLua.THREAD_CLOSE.invokeExact(handle, threadRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int threadStatus(int threadRef) {
        try {
            return (int) CendaLua.THREAD_STATUS.invokeExact(handle, threadRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ──────────────────────────── limits and memory ────────────────────────────

    public long memUsed() {
        try {
            return (long) CendaLua.MEM_USED.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public long memPeak() {
        try {
            return (long) CendaLua.MEM_PEAK.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Cumulative bytes this state has requested; a delta is Lua-heap garbage. */
    public long memAllocated() {
        try {
            return (long) CendaLua.MEM_ALLOCATED.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setMemLimit(long bytes) {
        try {
            CendaLua.SET_MEM_LIMIT.invokeExact(handle, bytes);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Instruction budget per top-level call; 0 disables the count hook. */
    public void setBudget(long instructions) {
        try {
            CendaLua.SET_BUDGET.invokeExact(handle, instructions);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public long lastInstructions() {
        try {
            return (long) CendaLua.LAST_INSTRUCTIONS.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Token of the top-level call in progress, 0 when idle. Safe from any
     * thread (the watchdog's side of the deadline protocol).
     */
    public long watchToken() {
        try {
            return (long) CendaLua.WATCH_TOKEN.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Stops the call identified by {@code token} with {@link #ERR_DEADLINE}.
     * Safe from any thread; a token for a finished call is ignored.
     */
    public void interrupt(long token) {
        try {
            CendaLua.INTERRUPT.invokeExact(handle, token);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Full GC cycle; returns bytes in use afterwards. */
    public long gcCollect() {
        try {
            return (long) CendaLua.GC_COLLECT.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            CendaLua.STATE_CLOSE.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            removeHosts(this);
            arena.close();
        }
    }

    // ───────────────────────────── upcall plumbing ─────────────────────────────

    private static long addHost(HostEntry entry) {
        synchronized (HOST_LOCK) {
            HostEntry[] table = hosts;
            if (hostCount == table.length) {
                table = java.util.Arrays.copyOf(table, table.length * 2);
            }
            table[hostCount] = entry;
            hosts = table;
            return hostCount++;
        }
    }

    private static void removeHosts(LuaState owner) {
        synchronized (HOST_LOCK) {
            HostEntry[] table = hosts;
            for (int i = 1; i < hostCount; i++) {
                if (table[i] != null && table[i].owner == owner) {
                    table[i] = null;
                }
            }
        }
    }

    /** Target of {@link CendaLua#HOST_STUB}. Must never throw. */
    static int dispatchHost(long id, MemorySegment rawArgs, int nargs, MemorySegment rawResults, int max) {
        try {
            HostEntry entry = hosts[(int) id];
            if (entry == null) {
                return -2;
            }
            LuaState state = entry.owner;
            int depth = state.hostDepth;
            if (depth >= MAX_HOST_DEPTH) {
                return -3;
            }
            LuaHostCall call = state.hostCalls[depth];
            if (call == null) {
                call = new LuaHostCall();
                state.hostCalls[depth] = call;
            }
            call.bind(rawArgs, nargs, rawResults, max);
            state.hostDepth = depth + 1;
            try {
                return entry.fn.invoke(call);
            } finally {
                state.hostDepth = depth;
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Target of {@link CendaLua#HOST_V_STUB}. Must never throw. */
    static int dispatchHostV(long id, MemorySegment rawArgs, int len, int nargs) {
        LuaState state = null;
        int depth = 0;
        try {
            HostEntry entry = hosts[(int) id];
            if (entry == null || entry.vfn == null) {
                return -2;
            }
            state = entry.owner;
            depth = state.hostDepth;
            if (depth >= MAX_HOST_DEPTH) {
                return state.fail(depth - 1, "host calls nested deeper than " + MAX_HOST_DEPTH);
            }
            LuaValueReader in = state.hostArgs[depth];
            LuaValueWriter out = state.hostResults[depth];
            if (in == null) {
                in = new LuaValueReader();
                out = new LuaValueWriter(state.arena, 1024);
                state.hostArgs[depth] = in;
                state.hostResults[depth] = out;
            }
            in.bind(rawArgs.address(), len, nargs);
            out.reset();
            state.hostDepth = depth + 1;
            int written;
            try {
                written = entry.vfn.invoke(in, out);
            } finally {
                state.hostDepth = depth;
            }
            state.publish(out);
            return written;
        } catch (Throwable t) {
            if (state == null) {
                return -2;
            }
            try {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                return state.fail(depth, msg);
            } catch (Throwable ignored) {
                return -2;
            }
        }
    }

    /** Writes {@code message} as the host error of the call at {@code depth}. */
    private int fail(int depth, String message) throws Throwable {
        LuaValueWriter out = hostResults[Math.max(0, depth)];
        if (out == null) {
            out = new LuaValueWriter(arena, 1024);
            hostResults[Math.max(0, depth)] = out;
        }
        out.cString(message);
        publish(out);
        return -1;
    }

    /** Points the native side at {@code out}'s memory (a downcall only when it moved). */
    private void publish(LuaValueWriter out) throws Throwable {
        MemorySegment seg = out.segment();
        if (!seg.equals(hostBuffer)) {
            CendaLua.SET_HOST_BUFFER.invokeExact(handle, seg, (int) Math.min(seg.byteSize(), Integer.MAX_VALUE));
            hostBuffer = seg;
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
