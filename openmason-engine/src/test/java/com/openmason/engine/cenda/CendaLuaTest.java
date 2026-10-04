package com.openmason.engine.cenda;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract of the Cenda Lua host through FFM (#283): loud load failures,
 * sandbox, memory cap, instruction budget, host upcalls, bulk buffers and
 * coroutine cancellation. Skips only when the library is not built — the
 * failure-diagnostic tests run either way.
 */
@Tag("regression")
class CendaLuaTest {

    private static void assumeLua() {
        assumeTrue(CendaLua.isAvailable(), "Cenda library with the Lua host not built");
    }

    // ───────────────────────── load diagnostics ─────────────────────────

    @Test
    void missingLibraryFailsLoudlyWithThePath(@TempDir Path dir) {
        Path missing = dir.resolve("libcenda_kernels.so");
        CendaLuaUnavailableException e = assertThrows(CendaLuaUnavailableException.class,
            () -> CendaLua.verify(missing, CendaLua.EXPECTED_ABI));
        assertTrue(e.getMessage().contains(missing.toString()), e.getMessage());
    }

    @Test
    void nonLibraryFileFailsLoudly(@TempDir Path dir) throws IOException {
        Path junk = Files.writeString(dir.resolve("libcenda_kernels.so"), "not an ELF");
        assertThrows(CendaLuaUnavailableException.class, () -> CendaLua.verify(junk, CendaLua.EXPECTED_ABI));
    }

    @Test
    void wrongAbiNamesBothVersions() {
        Path lib = CendaKernels.locateLibrary();
        assumeTrue(lib != null, "Cenda library not built");
        CendaLuaUnavailableException e = assertThrows(CendaLuaUnavailableException.class,
            () -> CendaLua.verify(lib, 999));
        assertTrue(e.getMessage().contains("ABI " + CendaLua.EXPECTED_ABI)
            && e.getMessage().contains("expects 999"), e.getMessage());
    }

    @Test
    void libraryWithoutLuaHostIsRejected() {
        Path libm = Path.of("/usr/lib/libm.so.6");
        assumeTrue(Files.isRegularFile(libm), "no libm at the Linux path");
        CendaLuaUnavailableException e = assertThrows(CendaLuaUnavailableException.class,
            () -> CendaLua.verify(libm, CendaLua.EXPECTED_ABI));
        assertTrue(e.getMessage().contains("cl_abi_version"), e.getMessage());
    }

    @Test
    void loadsLua55() {
        assumeLua();
        assertTrue(CendaLua.luaRelease().startsWith("Lua 5.5"), CendaLua.luaRelease());
    }

    // ─────────────────────────────── sandbox ───────────────────────────────

    @Test
    void sandboxExposesOnlyTheCuratedLibraries() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            assertEquals(LuaState.OK, lua.run("""
                function probe()
                  local banned = {io, os, debug, package, require, dofile, loadfile, collectgarbage, string.dump}
                  for i = 1, 9 do if banned[i] ~= nil then return i end end
                  if select('#', load('\\27Lua')) ~= 2 then return 100 end
                  if getmetatable('') ~= 'string' then return 101 end
                  return math.floor(math.sqrt(16)) + #string.rep('a', 3) + utf8.len('é') + #table.pack(1, 2)
                end"""), lua.lastError());
            assertEquals(10.0, lua.call1(lua.refFunction(0, "probe")));
        }
    }

    @Test
    void errorsCarryTracebacksAndKeepTheStateUsable() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            assertEquals(LuaState.ERR_SYNTAX, lua.run("x = = 1", "broken.lua", 0));
            assertTrue(lua.lastError().contains("broken.lua"), lua.lastError());
            assertEquals(LuaState.ERR_RUN, lua.run("local function f() error('boom') end f()", "doc.lua", 0));
            assertTrue(lua.lastError().contains("boom") && lua.lastError().contains("traceback"), lua.lastError());
            assertEquals(LuaState.OK, lua.run("ok = 1"));
        }
    }

    @Test
    void environmentsIsolateGlobalsAndLibraries() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            int a = lua.newEnv();
            int b = lua.newEnv();
            assertEquals(LuaState.OK, lua.run("counter = 1; string.extra = 7; function get() return counter end", "a", a));
            assertEquals(LuaState.OK, lua.run(
                "function get() return (counter == nil and string.extra == nil) and 1 or 0 end", "b", b));
            assertEquals(1.0, lua.call1(lua.refFunction(a, "get")));
            assertEquals(1.0, lua.call1(lua.refFunction(b, "get")));
            assertTrue(lua.refFunction(0, "get") <= 0, "env functions must not leak into the shared globals");
        }
    }

    // ───────────────────────────── limits ─────────────────────────────

    @Test
    void memoryCapRaisesAMemoryErrorNotACrash() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(512 * 1024)) {
            int status = lua.run("local t = {} for i = 1, 1e7 do t[i] = string.rep('x', 64) .. i end");
            assertEquals(LuaState.ERR_MEM, status, lua.lastError());
            assertTrue(lua.memPeak() <= 512 * 1024);
            lua.gcCollect();
            assertEquals(LuaState.OK, lua.run("after = 1"), lua.lastError());
        }
    }

    @Test
    void instructionBudgetStopsRunawayScriptsEvenUnderPcall() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            lua.setBudget(200_000);
            assertEquals(LuaState.ERR_BUDGET, lua.run("while true do end"));
            assertEquals(LuaState.ERR_BUDGET,
                lua.run("while true do pcall(function() while true do end end) end"));
            assertTrue(lua.lastInstructions() < 200_000 + 2_000, "overrun " + lua.lastInstructions());
            assertEquals(LuaState.OK, lua.run("local x = 0 for i = 1, 1000 do x = x + i end"),
                "budget resets for the next call");
        }
    }

    @Test
    void watchdogDeadlineStopsHungCallbacksWithoutAHook() {
        assumeLua();
        try (LuaWatchdog dog = new LuaWatchdog(250); LuaState lua = CendaLua.newState(0)) {
            dog.watch(lua, 20);
            try {
                for (String hang : new String[]{
                    "while true do end",
                    "local function f() return f() end f()",
                    "while true do pcall(function() while true do end end) end",
                    "local co = coroutine.wrap(function() while true do end end) co()"}) {
                    long t0 = System.nanoTime();
                    assertEquals(LuaState.ERR_DEADLINE, lua.run(hang), hang + ": " + lua.lastError());
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    assertTrue(ms < 500, hang + " took " + ms + " ms");
                }
                assertEquals(LuaState.OK, lua.run("local x = 0 for i = 1, 1000 do x = x + i end"),
                    "fast calls are never interrupted");
            } finally {
                dog.unwatch(lua);
            }
        }
    }

    // ───────────────────────────── host bridges ─────────────────────────────

    @Test
    void hostFunctionsRoundTripAndExceptionsBecomeLuaErrors() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            lua.register(0, "add", call -> {
                double sum = 0;
                for (int i = 0; i < call.argCount(); i++) {
                    sum += call.arg(i);
                }
                call.result(0, sum);
                return 1;
            });
            lua.register(0, "explode", call -> {
                throw new IllegalStateException("host bug");
            });
            assertEquals(LuaState.OK, lua.run("function f() return add(1, 2, 3.5) end"));
            assertEquals(6.5, lua.call1(lua.refFunction(0, "f")));
            assertEquals(LuaState.ERR_RUN, lua.run("explode()"));
            assertTrue(lua.lastError().contains("host function failed"), lua.lastError());
        }
    }

    @Test
    void nestedUpcallsKeepTheirOwnArguments() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            lua.register(0, "outer", call -> {
                double before = call.arg(0);
                lua.setArg(0, 5);
                lua.call(lua.refFunction(0, "inner"), 1, 1);
                call.result(0, before * 100 + lua.result(0) + call.arg(0));
                return 1;
            });
            lua.register(0, "twice", call -> {
                call.result(0, call.arg(0) * 2);
                return 1;
            });
            assertEquals(LuaState.OK, lua.run("function inner(x) return twice(x) end function f() return outer(7) end"));
            assertEquals(7 * 100 + 10 + 7, lua.call1(lua.refFunction(0, "f")));
        }
    }

    @Test
    void floatBufferCarriesBulkDataWithoutCrossings() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            LuaFloatBuffer out = lua.bindBuffer(0, "draw", 64);
            assertEquals(LuaState.OK, lua.run("for i = 1, 10 do draw.emit(i, i * 2) end"));
            assertEquals(20, out.cursor());
            assertEquals(20f, out.get(19));
            assertEquals(LuaState.ERR_RUN, lua.run("for i = 1, 100 do draw.emit(i) end"));
            out.reset();
            assertEquals(0, out.cursor());
        }
    }

    // ───────────────────────────── coroutines ─────────────────────────────

    /**
     * The ui.await pattern from #292: a handler yields on an async host action;
     * closing the document cancels it (running its {@code <close>} cleanup) and
     * a result arriving afterwards is dropped by generation id.
     */
    @Test
    void cancelledCoroutineRunsCleanupAndNeverResumes() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            assertEquals(LuaState.OK, lua.run("""
                cleaned, applied = 0, 0
                function handler()
                  local guard <close> = setmetatable({}, {__close = function() cleaned = cleaned + 1 end})
                  local result = coroutine.yield(42)   -- 42 = pending action id
                  applied = applied + result
                end
                function stats() return cleaned * 10 + applied end"""));
            int fn = lua.refFunction(0, "handler");
            int generation = 1;
            int thread = lua.newThread(fn);
            assertEquals(LuaState.YIELD, lua.resume(thread, 0, 1));
            assertEquals(42.0, lua.result(0));
            int pendingGeneration = generation;

            generation++; // document closed / reloaded
            assertEquals(LuaState.OK, lua.closeThread(thread));
            assertEquals(LuaState.THREAD_DEAD, lua.threadStatus(thread));

            // The action completes late: stale generation, and the thread is dead anyway.
            if (pendingGeneration == generation) {
                lua.setArg(0, 5);
                lua.resume(thread, 1, 0);
            }
            lua.setArg(0, 5);
            assertEquals(LuaState.ERR_ARG, lua.resume(thread, 1, 0), "a cancelled thread must refuse resumption");
            assertEquals(10.0, lua.call1(lua.refFunction(0, "stats")), "cleanup ran once, result never applied");
            lua.unref(thread);
        }
    }

    @Test
    void aStateIsConfinedToItsThread() throws Exception {
        assumeLua();
        try (LuaState lua = CendaLua.newState(0)) {
            assertEquals(LuaState.OK, lua.run("function f(x) return x end"));
            int fn = lua.refFunction(0, "f");
            ExecutionException e = assertThrows(ExecutionException.class,
                () -> CompletableFuture.runAsync(() -> lua.call(fn, 0, 1)).get());
            assertInstanceOf(WrongThreadException.class, e.getCause());
        }
    }
}
