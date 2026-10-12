package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.cenda.CendaLua;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The startup handshake of the native UI hosts (#292): the Lua host (scripting) and the Yoga flex
 * host (layout) have no Java fallback, so the game and the tool call {@link #check()} at launch.
 * It loads both bindings (each verifies its ABI against the library) and logs every problem as an
 * error, so a missing or stale library is reported when the program starts, not when a scripted
 * screen first opens. Hosts also show the problems to the user.
 */
public final class UiNativeHealth {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiNativeHealth.class);

    /**
     * @param problems one line per unavailable host (empty when both loaded)
     * @param luaRelease "Lua 5.5.1" when the Lua host loaded, else null
     */
    public record Status(List<String> problems, String luaRelease) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private UiNativeHealth() {
    }

    public static Status check() {
        List<String> problems = new ArrayList<>();
        String release = null;
        try {
            CendaLua.require();
            release = CendaLua.luaRelease();
        } catch (RuntimeException | LinkageError e) {
            problems.add("UI scripting unavailable: " + e.getMessage());
        }
        try {
            CendaFlex.require();
        } catch (RuntimeException | LinkageError e) {
            problems.add("UI layout unavailable: " + e.getMessage());
        }
        for (String p : problems) {
            LOGGER.error("[ui-native] {}", p);
        }
        if (problems.isEmpty()) {
            LOGGER.info("[ui-native] Lua host ({}, ABI {}) and flex host (ABI {}) ready", release,
                CendaLua.EXPECTED_ABI, CendaFlex.EXPECTED_ABI);
        }
        return new Status(List.copyOf(problems), release);
    }
}
