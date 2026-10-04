package com.openmason.main.systems.scripting;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.cenda.LuaState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #283: the engine's Lua host loads and runs from the tool module (its own
 * test JVM, module path and working directory), not just inside the engine.
 */
@Tag("regression")
class LuaHostToolSmokeTest {

    @Test
    void codeBehindRunsThroughTheEngineLuaHost() {
        assumeTrue(CendaLua.isAvailable(), "Cenda library with the Lua host not built");
        try (LuaState lua = CendaLua.newState(1 << 20)) {
            double[] actions = new double[1];
            lua.register(0, "action", call -> {
                actions[0] = call.arg(0);
                return 0;
            });
            assertEquals(LuaState.OK, lua.run("""
                local slots = {"resume", "settings", "quit"}
                function on_click(slot) action(slot) return #slots[slot] end""", "tool.lua", 0), lua.lastError());
            assertEquals(4.0, lua.call1(lua.refFunction(0, "on_click"), 3));
            assertEquals(3.0, actions[0]);
        }
    }
}
