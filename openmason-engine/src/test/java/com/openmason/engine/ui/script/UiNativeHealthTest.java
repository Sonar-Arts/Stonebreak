package com.openmason.engine.ui.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The launch handshake of the native UI hosts (#292) passes against the built library. */
class UiNativeHealthTest {

    @Test
    void bothNativeUiHostsHandshake() {
        ScriptRig.assumeLua();
        UiNativeHealth.Status s = UiNativeHealth.check();
        assertTrue(s.ok(), s.problems().toString());
        assertTrue(s.luaRelease().startsWith("Lua 5.5"), s.luaRelease());
    }
}
