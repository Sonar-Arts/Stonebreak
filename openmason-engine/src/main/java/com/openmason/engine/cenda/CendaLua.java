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
 * FFM binding to the sandboxed Lua 5.5 host compiled into the Cenda library
 * ({@code cenda/native/kernels/src/lua_host.cpp}, ABI in {@code cenda/lua_host.h}).
 *
 * <p>Unlike {@link CendaKernels} this is <b>not</b> optional: UI scripting has
 * no Java fallback. When the library is missing or its Lua-host ABI differs,
 * {@link #isAvailable()} is false and every entry point throws
 * {@link CendaLuaUnavailableException} with the diagnostic.
 *
 * <p>The method handles are {@code static final} on purpose: the JIT can then
 * inline the downcall, which is the difference between ~10 ns and ~50 ns per
 * crossing. Discovery uses the same search as the kernels
 * ({@code -Dcenda.kernels.path}, {@code CENDA_KERNELS_PATH}, build dirs).
 *
 * <p>ABI 2 (#292) adds typed values ({@link LuaValueWriter}, {@link LuaValueReader},
 * {@link LuaValueFunction}); the {@code ui} API is built on them in {@code engine.ui.script}.
 */
public final class CendaLua {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaLua.class);

    /** Must equal {@code CL_ABI_VERSION} in lua_host.h. */
    public static final int EXPECTED_ABI = 2;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout ADDR = ValueLayout.ADDRESS;

    private static final String RELEASE;
    private static final CendaLuaUnavailableException FAILURE;

    static final MethodHandle STATE_NEW;
    static final MethodHandle STATE_CLOSE;
    static final MethodHandle MEM_USED;
    static final MethodHandle MEM_PEAK;
    static final MethodHandle MEM_ALLOCATED;
    static final MethodHandle SET_MEM_LIMIT;
    static final MethodHandle SET_BUDGET;
    static final MethodHandle LAST_INSTRUCTIONS;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle ENV_NEW;
    static final MethodHandle UNREF;
    static final MethodHandle RUN;
    static final MethodHandle REF_FUNCTION;
    static final MethodHandle CALL;
    static final MethodHandle REGISTER_HOST;
    static final MethodHandle BIND_BUFFER;
    static final MethodHandle BUFFER_CURSOR;
    static final MethodHandle BUFFER_RESET;
    static final MethodHandle THREAD_NEW;
    static final MethodHandle RESUME;
    static final MethodHandle THREAD_CLOSE;
    static final MethodHandle THREAD_STATUS;
    static final MethodHandle GC_COLLECT;
    static final MethodHandle WATCH_TOKEN;
    static final MethodHandle INTERRUPT;
    static final MethodHandle SET_HOST_BUFFER;
    static final MethodHandle REGISTER_HOST_V;
    static final MethodHandle CALL_V;
    /** The single upcall stub every host function goes through. */
    static final MemorySegment HOST_STUB;
    /** The single upcall stub every value-typed host function goes through. */
    static final MemorySegment HOST_V_STUB;

    static {
        String release = null;
        CendaLuaUnavailableException failure = null;
        SymbolLookup lookup = null;
        MemorySegment stub = MemorySegment.NULL;
        MemorySegment stubV = MemorySegment.NULL;
        try {
            if (ADDR.byteSize() != Long.BYTES) {
                throw new CendaLuaUnavailableException("the Cenda Lua host needs a 64-bit JVM");
            }
            Path lib = CendaKernels.locateLibrary();
            if (lib == null) {
                throw new CendaLuaUnavailableException(
                    "Cenda library not found (searched -Dcenda.kernels.path, CENDA_KERNELS_PATH and "
                        + "openmason-engine/cenda/build/{release,debug}/native/kernels relative to "
                        + Path.of("").toAbsolutePath() + "). Build it: openmason-engine/cenda/build-kernels.sh");
            }
            lookup = verify(lib, EXPECTED_ABI);
            release = string((MemorySegment) LINKER.downcallHandle(
                find(lookup, lib, "cl_lua_release"), FunctionDescriptor.of(ADDR)).invokeExact());
            stub = LINKER.upcallStub(
                MethodHandles.lookup().findStatic(LuaState.class, "dispatchHost",
                    MethodType.methodType(int.class, long.class, MemorySegment.class, int.class,
                        MemorySegment.class, int.class)),
                FunctionDescriptor.of(I32, I64, ADDR, I32, ADDR, I32),
                Arena.global());
            stubV = LINKER.upcallStub(
                MethodHandles.lookup().findStatic(LuaState.class, "dispatchHostV",
                    MethodType.methodType(int.class, long.class, MemorySegment.class, int.class, int.class)),
                FunctionDescriptor.of(I32, I64, ADDR, I32, I32),
                Arena.global());
            LOGGER.info("Cenda Lua host loaded ({}, ABI {}) from {}", release, EXPECTED_ABI, lib);
        } catch (CendaLuaUnavailableException e) {
            failure = e;
        } catch (Throwable t) {
            failure = new CendaLuaUnavailableException("Cenda Lua host failed to initialize: " + t, t);
        }
        if (failure != null) {
            LOGGER.error("UI scripting unavailable: {}", failure.getMessage());
            lookup = null;
        }
        RELEASE = release;
        FAILURE = failure;
        HOST_STUB = stub;
        HOST_V_STUB = stubV;
        STATE_NEW = handle(lookup, "cl_state_new", FunctionDescriptor.of(ADDR, I64));
        STATE_CLOSE = handle(lookup, "cl_state_close", FunctionDescriptor.ofVoid(ADDR));
        MEM_USED = handle(lookup, "cl_mem_used", FunctionDescriptor.of(I64, ADDR), Linker.Option.critical(false));
        MEM_PEAK = handle(lookup, "cl_mem_peak", FunctionDescriptor.of(I64, ADDR), Linker.Option.critical(false));
        MEM_ALLOCATED = handle(lookup, "cl_mem_allocated", FunctionDescriptor.of(I64, ADDR),
            Linker.Option.critical(false));
        SET_MEM_LIMIT = handle(lookup, "cl_set_mem_limit", FunctionDescriptor.ofVoid(ADDR, I64));
        SET_BUDGET = handle(lookup, "cl_set_budget", FunctionDescriptor.ofVoid(ADDR, I64));
        LAST_INSTRUCTIONS = handle(lookup, "cl_last_instructions", FunctionDescriptor.of(I64, ADDR),
            Linker.Option.critical(false));
        LAST_ERROR = handle(lookup, "cl_last_error", FunctionDescriptor.of(ADDR, ADDR));
        ENV_NEW = handle(lookup, "cl_env_new", FunctionDescriptor.of(I32, ADDR));
        UNREF = handle(lookup, "cl_unref", FunctionDescriptor.ofVoid(ADDR, I32));
        RUN = handle(lookup, "cl_run", FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR, I32));
        REF_FUNCTION = handle(lookup, "cl_ref_function", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        CALL = handle(lookup, "cl_call", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR, I32));
        REGISTER_HOST = handle(lookup, "cl_register_host",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, I64));
        BIND_BUFFER = handle(lookup, "cl_bind_buffer", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, I32));
        BUFFER_CURSOR = handle(lookup, "cl_buffer_cursor", FunctionDescriptor.of(I32, ADDR, I32),
            Linker.Option.critical(false));
        BUFFER_RESET = handle(lookup, "cl_buffer_reset", FunctionDescriptor.ofVoid(ADDR, I32),
            Linker.Option.critical(false));
        THREAD_NEW = handle(lookup, "cl_thread_new", FunctionDescriptor.of(I32, ADDR, I32));
        RESUME = handle(lookup, "cl_resume",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR, I32, ADDR));
        THREAD_CLOSE = handle(lookup, "cl_thread_close", FunctionDescriptor.of(I32, ADDR, I32));
        THREAD_STATUS = handle(lookup, "cl_thread_status", FunctionDescriptor.of(I32, ADDR, I32));
        GC_COLLECT = handle(lookup, "cl_gc_collect", FunctionDescriptor.of(I64, ADDR));
        WATCH_TOKEN = handle(lookup, "cl_watch_token", FunctionDescriptor.of(I64, ADDR),
            Linker.Option.critical(false));
        INTERRUPT = handle(lookup, "cl_interrupt", FunctionDescriptor.ofVoid(ADDR, I64),
            Linker.Option.critical(false));
        SET_HOST_BUFFER = handle(lookup, "cl_set_host_buffer", FunctionDescriptor.ofVoid(ADDR, ADDR, I32),
            Linker.Option.critical(false));
        REGISTER_HOST_V = handle(lookup, "cl_register_host_v",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, I64));
        CALL_V = handle(lookup, "cl_call_v",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, I32, I32, ADDR, ADDR, ADDR));
    }

    private CendaLua() {
    }

    /** True when the Lua host loaded and passed its ABI handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Throws the load diagnostic when the Lua host is unavailable. */
    public static void require() {
        if (FAILURE != null) {
            throw new CendaLuaUnavailableException(FAILURE.getMessage(), FAILURE);
        }
    }

    /** "Lua 5.5.1". */
    public static String luaRelease() {
        require();
        return RELEASE;
    }

    /** A new sandboxed state; {@code memLimitBytes == 0} means uncapped. */
    public static LuaState newState(long memLimitBytes) {
        require();
        return new LuaState(memLimitBytes);
    }

    /**
     * Opens {@code lib} and checks its Lua-host ABI. Separate from the static
     * load so tests can prove the failure diagnostics against any file.
     */
    static SymbolLookup verify(Path lib, int expectedAbi) {
        if (!Files.isRegularFile(lib)) {
            throw new CendaLuaUnavailableException("Cenda library not found at " + lib.toAbsolutePath());
        }
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(lib, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new CendaLuaUnavailableException("Cannot load " + lib + " as a native library: "
                + e.getMessage(), e);
        }
        int abi;
        try {
            abi = (int) LINKER.downcallHandle(find(lookup, lib, "cl_abi_version"),
                FunctionDescriptor.of(I32)).invokeExact();
        } catch (CendaLuaUnavailableException e) {
            throw e;
        } catch (Throwable t) {
            throw new CendaLuaUnavailableException("cl_abi_version() failed in " + lib, t);
        }
        if (abi != expectedAbi) {
            throw new CendaLuaUnavailableException("Cenda Lua host at " + lib + " has ABI " + abi
                + " but this build expects " + expectedAbi
                + ". The library is stale or from another branch: rebuild with openmason-engine/cenda/build-kernels.sh");
        }
        return lookup;
    }

    static String string(MemorySegment cString) {
        return cString.equals(MemorySegment.NULL) ? "" : cString.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static MemorySegment find(SymbolLookup lookup, Path lib, String name) {
        return lookup.find(name).orElseThrow(() -> new CendaLuaUnavailableException(
            "Cenda library at " + lib + " has no Lua host (missing symbol " + name
                + "); it predates #283 — rebuild with openmason-engine/cenda/build-kernels.sh"));
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
}
