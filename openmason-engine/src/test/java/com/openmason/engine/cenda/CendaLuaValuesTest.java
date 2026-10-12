package com.openmason.engine.cenda;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Typed values across FFM (CL ABI 2, #292): round trips, nesting, host errors, re-entrant calls,
 * buffer growth and allocation-free number calls.
 */
@Tag("regression")
class CendaLuaValuesTest {

    @BeforeEach
    void requireLua() {
        // UI scripting has no fallback: fail, never skip silently, without the native library (#292).
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void uiValuesRoundTripThroughLua() {
        try (LuaState lua = CendaLua.newState(0)) {
            lua.registerValues(0, "echo", (in, out) -> {
                int n = in.count();
                for (int i = 0; i < n; i++) {
                    out.value(in.value());
                }
                return out.count();
            });
            assertEquals(LuaState.OK, lua.run("function probe(v) local r = echo(v) return r, math.type(r.n) end"));
            UiValue.Obj value = new UiValue.Obj(Map.of(
                "n", UiValue.of(3),
                "f", UiValue.of(2.5),
                "s", UiValue.of("héllo 😀"),
                "list", new UiValue.Arr(List.of(UiValue.TRUE, UiValue.NULL, UiValue.of("x"))),
                "nested", new UiValue.Obj(Map.of("deep", new UiValue.Obj(Map.of("k", UiValue.FALSE))))));
            int fn = lua.refFunction(0, "probe");
            lua.args().value(value);
            assertEquals(LuaState.OK, lua.callValues(fn, 4), lua.lastError());
            LuaValueReader r = lua.results();
            assertEquals(2, r.count());
            UiValue back = r.value();
            // A null inside a list is a nil hole in Lua and comes back as null.
            assertEquals(value, back);
            assertEquals("integer", r.string(), "integral host numbers arrive as Lua integers");
        }
    }

    @Test
    void hostExceptionsBecomeCatchableLuaErrors() {
        try (LuaState lua = CendaLua.newState(0)) {
            lua.registerValues(0, "boom", (in, out) -> {
                throw new IllegalStateException("no such element #quit");
            });
            lua.run("function probe() local ok, err = pcall(boom) return ok, err end");
            int fn = lua.refFunction(0, "probe");
            lua.args();
            assertEquals(LuaState.OK, lua.callValues(fn, 2), lua.lastError());
            assertTrue(!lua.results().bool());
            assertTrue(lua.results().string().contains("no such element #quit"));
            assertEquals(LuaState.ERR_RUN, lua.run("boom()"));
            assertTrue(lua.lastError().contains("no such element #quit"), lua.lastError());
        }
    }

    @Test
    void unencodableValuesAreLuaErrors() {
        try (LuaState lua = CendaLua.newState(0)) {
            lua.registerValues(0, "sink", (in, out) -> 0);
            assertEquals(LuaState.ERR_RUN, lua.run("sink(print)"));
            assertTrue(lua.lastError().contains("function"), lua.lastError());
            assertEquals(LuaState.ERR_RUN, lua.run("sink({1, 2, x = 3})"));
        }
    }

    @Test
    void hostFunctionsMayReenterTheState() {
        try (LuaState lua = CendaLua.newState(0)) {
            int[] square = new int[1];
            lua.registerValues(0, "outer", (in, out) -> {
                double x = in.number();
                lua.args().number(x);
                if (lua.callValues(square[0], 1) != LuaState.OK) {
                    throw new IllegalStateException(lua.lastError());
                }
                double sq = lua.results().number();
                out.number(sq + 1).string("depth ok");
                return out.count();
            });
            lua.registerValues(0, "inner", (in, out) -> {
                double v = in.number();
                out.number(v * v);
                return 1;
            });
            lua.run("function square(x) return inner(x) end\n"
                + "function probe(x) local a, b = outer(x) return a, b end");
            square[0] = lua.refFunction(0, "square");
            int probe = lua.refFunction(0, "probe");
            lua.args().number(7);
            assertEquals(LuaState.OK, lua.callValues(probe, 2), lua.lastError());
            assertEquals(50.0, lua.results().number());
            assertEquals("depth ok", lua.results().string());
        }
    }

    @Test
    void largeResultsGrowTheHostBuffer() {
        try (LuaState lua = CendaLua.newState(0)) {
            String big = "x".repeat(2_000_000);
            lua.registerValues(0, "big", (in, out) -> {
                out.string(big);
                return 1;
            });
            lua.run("function probe() return #big() end");
            int fn = lua.refFunction(0, "probe");
            lua.args();
            assertEquals(LuaState.OK, lua.callValues(fn, 1), lua.lastError());
            assertEquals(2_000_000.0, lua.results().number());
        }
    }

    @Test
    void numberCallsAllocateNothing() {
        try (LuaState lua = CendaLua.newState(0)) {
            lua.run("local t = 0 function tick(dt, on) if on then t = t + dt end return t end");
            int fn = lua.refFunction(0, "tick");
            assumeTrue(AllocationMeter.available(), "no allocation meter on this JVM");
            double sum = 0;
            for (int i = 0; i < 20_000; i++) { // warm up past JIT tiers
                lua.args().number(0.016).bool(true);
                lua.callValues(fn, 1);
                sum += lua.results().number();
            }
            long before = AllocationMeter.allocatedBytes();
            for (int i = 0; i < 10_000; i++) {
                lua.args().number(0.016).bool(true);
                lua.callValues(fn, 1);
                sum += lua.results().number();
            }
            long garbage = AllocationMeter.allocatedBytes() - before;
            assertTrue(sum > 0);
            assertTrue(garbage < 4096, "10k value calls allocated " + garbage + " bytes");
        }
    }
}
