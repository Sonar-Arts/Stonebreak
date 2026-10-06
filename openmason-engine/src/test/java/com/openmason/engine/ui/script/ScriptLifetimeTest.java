package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.button;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.declared;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coroutines that await host actions are cancelled on close, reload and world change, and stale
 * results never apply; hot reload swaps modules atomically (#292).
 */
class ScriptLifetimeTest {

    static final String AWAITING = """
        opens = opens or 0 -- an environment global: survives hot reload
        function on_open(ui)
          opens = opens + 1
          ui.log("open " .. opens)
          ui.q("#go"):on("click", function()
            local guard <close> = setmetatable({}, { __close = function() ui.log("task closed") end })
            ui.q("#out"):setText("waiting")
            local r = ui.await(ui.action("test:net.ping", { n = 7 }))
            ui.q("#out"):setText("APPLIED " .. tostring(r and r.pong))
          end)
        end
        """;

    static OmuiArchive doc(String script) {
        return declared(ScriptRig.withCode(screen("t:ui/life", root(button("go"), label("out", "idle").name("out"))),
            script));
    }

    static ScriptRig rig(ScriptBehaviourTest.Host h, OmuiArchive doc) {
        return new ScriptRig(doc, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS, UiScriptServices.NONE);
    }

    @Test
    void closingTheScreenCancelsAwaitingTasks() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        ScriptRig rig = rig(h, doc(AWAITING));
        rig.click("go");
        assertEquals("waiting", rig.text("out"));
        assertEquals(1, rig.rt.pendingCount());
        rig.close();
        assertEquals(0, rig.rt.pendingCount());
        h.complete(0, 5); // arrives after close: dropped
        assertEquals("waiting", rig.text("out"));
        assertTrue(rig.log().contains("task closed"), "the task's <close> handlers ran: " + rig.log());
        assertFalse(rig.log().stream().anyMatch(l -> l.contains("APPLIED")));
    }

    @Test
    void reloadCancelsAwaitingTasksAndDropsTheirLateResults() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        OmuiArchive doc = doc(AWAITING);
        try (ScriptRig rig = rig(h, doc)) {
            rig.click("go");
            rig.reload(doc);
            assertTrue(rig.log().contains("task closed"), rig.log().toString());
            assertTrue(rig.log().contains("open 2"), "on_open ran again for the new tree, keeping env globals");
            h.complete(0, 5);
            rig.frame(0.016);
            assertEquals("waiting", rig.text("out"), "the stale result never applied");
            // The reloaded handler works on the new elements.
            rig.click("go");
            h.complete(1, 9);
            rig.frame(0.016);
            assertEquals("APPLIED 9", rig.text("out"));
        }
    }

    @Test
    void leavingTheWorldCancelsAwaitingTasks() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        try (ScriptRig rig = rig(h, doc(AWAITING))) {
            rig.click("go");
            h.host.advanceEpoch("left the world");
            h.host.drain();
            rig.frame(0.016);
            assertTrue(rig.log().contains("task closed"), rig.log().toString());
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.TASK_CANCELLED), rig.codes().toString());
            h.held.getFirst().complete(ScriptBehaviourTest.obj("pong", 3));
            h.host.drain();
            rig.frame(0.016);
            assertEquals("waiting", rig.text("out"));
        }
    }

    @Test
    void awaitOutsideATaskIsAClearError() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        try (ScriptRig rig = rig(h, doc("""
            local tries = 0
            function update(dt)
              tries = tries + 1
              if tries == 1 then ui.await(ui.sleep(1)) end
            end
            """))) {
            rig.frame(0.016);
            assertTrue(rig.rt.diagnostics().getFirst().headline().contains("ui.await needs a task"),
                rig.rt.diagnostics().toString());
        }
    }

    @Test
    void sleepResumesAfterItsTime() {
        try (ScriptRig rig = new ScriptRig(ScriptRig.withCode(screen("t:ui/sleep", root(label("out", "").name("out"))), """
            function on_open(ui)
              ui.q("#out"):setText("a")
              ui.await(ui.sleep(0.5))
              ui.q("#out"):setText("b")
            end
            """))) {
            rig.frame(0.3);
            assertEquals("a", rig.text("out"));
            rig.frame(0.3);
            assertEquals("b", rig.text("out"));
        }
    }

    // ── hot reload ──────────────────────────────────────────────────────────

    static String version(String tag) {
        return """
            clicks = clicks or 0
            function on_open(ui)
              ui.q("#go"):on("click", function()
                clicks = clicks + 1
                ui.q("#out"):setText("%s clicks=" .. clicks)
              end)
            end
            """.formatted(tag);
    }

    @Test
    void hotReloadSwapsTheModuleAndKeepsCompatibleState() {
        try (ScriptRig rig = new ScriptRig(doc(version("v1")))) {
            rig.click("go");
            assertEquals("v1 clicks=1", rig.text("out"));
            rig.reload(doc(version("v2")));
            assertEquals("v1 clicks=1", rig.text("out"), "local element state survives by key");
            rig.click("go");
            assertEquals("v2 clicks=2", rig.text("out"), "new code, old globals");
        }
    }

    @Test
    void aBrokenEditKeepsTheLastGoodVersionRunning() {
        try (ScriptRig rig = new ScriptRig(doc(version("v1")))) {
            rig.reload(doc("function on_open(ui) this is not lua"));
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.SYNTAX), rig.codes().toString());
            rig.click("go");
            assertEquals("v1 clicks=1", rig.text("out"), "the previous version re-attached to the new tree");
        }
    }

    @Test
    void reloadAddsAndRemovesComponentContexts() {
        OmuiArchive withTwo = ScriptRig.withCode(screen("t:ui/r", root(
            com.openmason.engine.ui.runtime.UiDocs.inst("a", "t:ui/counter", java.util.Map.of()),
            com.openmason.engine.ui.runtime.UiDocs.inst("b", "t:ui/counter", java.util.Map.of()))), "-- screen");
        OmuiArchive withOne = ScriptRig.withCode(screen("t:ui/r", root(
            com.openmason.engine.ui.runtime.UiDocs.inst("a", "t:ui/counter", java.util.Map.of()))), "-- screen");
        UiDocumentSource src = UiDocumentSource.of(java.util.Map.of("t:ui/counter",
            ScriptBehaviourTest.counterComponent()), java.util.Map.of());
        try (ScriptRig rig = new ScriptRig(withTwo, src, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            rig.click("a/plus");
            rig.reload(withOne);
            assertEquals(List.of("main.lua", "counter.lua@a"), rig.rt.modules());
            assertTrue(rig.log().contains("close b"), rig.log().toString());
            rig.click("a/plus");
            assertEquals("2", rig.text("a/value"), "a's environment (and its count) survived");
        }
    }
}
