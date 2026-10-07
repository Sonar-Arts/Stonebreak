package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeated open/close and reload must not leak (#296 acceptance: "repeated open/close/reload is
 * measured for leaks"). A screen that watches host data, awaits a host action, sleeps on a timer
 * and runs a tween is opened and closed, and reloaded, many times; afterwards nothing it held
 * may still be alive: no open scope on the host, no data subscriptions, no pending calls or
 * timers, every Lua state and native layout tree freed, and the Lua heap of a reloaded screen
 * stays flat.
 */
class DocumentLifecycleLeakTest {

    private static final int CYCLES = 40;

    static final String BUSY = """
        function on_open(ui)
          ui.watch("session.online", function(v) ui.q("#out"):setText(tostring(v)) end)
          ui.tween(ui.q("#out"), { opacity = 0.5 }, 10)
          ui.q("#go"):on("click", function()
            ui.await(ui.sleep(30))
          end)
          ui.q("#go"):on("click", function()
            ui.await(ui.action("test:net.ping", { n = 1 }))
          end)
        end
        """;

    @Test
    void repeatedOpenAndCloseLeavesNothingBehind() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        int baseline = h.session.subscriberCount();
        OmuiArchive doc = ScriptLifetimeTest.doc(BUSY);
        List<ScriptRig> closed = new ArrayList<>();
        for (int i = 0; i < CYCLES; i++) {
            ScriptRig rig = ScriptLifetimeTest.rig(h, doc);
            rig.click("go");
            rig.frame(0.016);
            assertTrue(rig.rt.pendingCount() > 0, "the screen really holds work while open");
            assertTrue(h.session.subscriberCount() > baseline, "and really watches host data");
            rig.close();
            closed.add(rig);
        }
        assertEquals(0, h.host.openScopes().size(), "every scope closed with its screen");
        assertEquals(baseline, h.session.subscriberCount(), "no data subscription outlives its screen");
        for (ScriptRig rig : closed) {
            assertTrue(rig.rt.isClosed(), "Lua state freed");
            assertTrue(rig.ui.isClosed(), "native layout tree freed");
            assertEquals(0, rig.rt.pendingCount(), "no pending call or timer kept");
        }
        for (int i = 0; i < h.held.size(); i++) {
            h.complete(i, i); // late completions of every closed screen: dropped, no throw
        }
        assertEquals(CYCLES, h.held.size());
    }

    @Test
    void repeatedReloadKeepsOneScopeAndAFlatHeap() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        OmuiArchive doc = ScriptLifetimeTest.doc(BUSY);
        try (ScriptRig rig = ScriptLifetimeTest.rig(h, doc)) {
            int subscribers = -1;
            long settledHeap = 0;
            for (int i = 0; i < CYCLES; i++) {
                rig.click("go");
                rig.frame(0.016);
                rig.reload(doc);
                rig.frame(0.016);
                assertEquals(1, h.host.openScopes().size(), "reload renews the scope, never adds one");
                if (i == 4) {
                    subscribers = h.session.subscriberCount();
                    System.gc();
                    settledHeap = rig.rt.memoryUsed();
                }
                if (i > 4) {
                    assertEquals(subscribers, h.session.subscriberCount(), "reload " + i + " leaked a watch");
                }
            }
            assertEquals(0, rig.rt.pendingCount(), "reload cancelled the awaits and timers of the old tree");
            long heap = rig.rt.memoryUsed();
            assertTrue(heap < settledHeap * 2 + (256 << 10),
                "Lua heap after " + CYCLES + " reloads: " + heap + " bytes (settled at " + settledHeap + ")");
        }
    }
}
