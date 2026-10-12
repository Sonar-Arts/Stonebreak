package com.openmason.engine.cenda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The watchdog deadline bounds Lua, not the host work a call asks for: time inside Java host
 * functions is subtracted, Lua before or after them (or in a callback the host makes) still
 * counts. Found live: the pause screen's Resync action took over 50 ms and disabled its script.
 */
class LuaHostTimeDeadlineTest {

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aSlowHostFunctionDoesNotTripTheDeadline() {
        assumeTrue(CendaLua.isAvailable(), "Cenda library with the Lua host not built");
        try (LuaWatchdog dog = new LuaWatchdog(250); LuaState lua = CendaLua.newState(0)) {
            dog.watch(lua, 50);
            try {
                lua.register(0, "slow", call -> {
                    sleep(120);
                    call.result(0, 1);
                    return 1;
                });
                assertEquals(LuaState.OK, lua.run("local n = 0 for i = 1, 3 do n = n + slow() end assert(n == 3)"),
                    lua.lastError());
                assertTrue(lua.hostNanos(System.nanoTime()) >= 300_000_000L, "host time is accounted");
            } finally {
                dog.unwatch(lua);
            }
        }
    }

    @Test
    void aBusyLoopAfterASlowHostCallStillTrips() {
        assumeTrue(CendaLua.isAvailable(), "Cenda library with the Lua host not built");
        try (LuaWatchdog dog = new LuaWatchdog(250); LuaState lua = CendaLua.newState(0)) {
            dog.watch(lua, 50);
            try {
                lua.register(0, "slow", call -> {
                    sleep(120);
                    return 0;
                });
                long t0 = System.nanoTime();
                assertEquals(LuaState.ERR_DEADLINE, lua.run("slow() while true do end"), lua.lastError());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < 120 + 1000, "stopped after " + ms + " ms");
            } finally {
                dog.unwatch(lua);
            }
        }
    }

    @Test
    void luaTheHostCallsBackIntoStillCounts() {
        assumeTrue(CendaLua.isAvailable(), "Cenda library with the Lua host not built");
        try (LuaWatchdog dog = new LuaWatchdog(250); LuaState lua = CendaLua.newState(0)) {
            dog.watch(lua, 50);
            try {
                lua.register(0, "dispatch", call -> {
                    sleep(80); // host work: free
                    lua.call(lua.refFunction(0, "handler"), 0, 0); // script callback: counted
                    return 0;
                });
                assertEquals(LuaState.OK, lua.run("function handler() while true do end end"));
                long t0 = System.nanoTime();
                assertEquals(LuaState.ERR_DEADLINE, lua.run("dispatch()"), lua.lastError());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < 80 + 1000, "stopped after " + ms + " ms");
                assertEquals(LuaState.OK, lua.run("local x = 0 for i = 1, 1000 do x = x + i end"),
                    "the state stays usable");
            } finally {
                dog.unwatch(lua);
            }
        }
    }
}
