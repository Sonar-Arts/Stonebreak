package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A slow host action invoked from a script (the pause screen's Resync, found live) must not
 * count against the script's deadline, while a runaway loop after it still does.
 */
class ScriptHostTimeTest {

    private static final HostContract NET = HostContract.of("test:net", 1);

    private static ScriptBehaviourTest.Host slowHost(AtomicInteger runs) {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        h.host.actions().register(ActionSpec.of("test:net.resync", NET, null, DataType.ANY), (args, ctx) -> {
            runs.incrementAndGet();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(UiValue.of(true));
        });
        return h;
    }

    private static OmuiArchive doc(String handler) {
        return UiDocs.declare(ScriptSafetyTest.twoButtons("""
            function on_open(ui)
              ui.q("#bad"):on("click", function()
            """ + handler + """
              end)
            end
            """), List.of(), "test:session@1", "test:net@1");
    }

    @Test
    void aSlowHostActionCompletesAndTheScriptStaysEnabled() {
        AtomicInteger runs = new AtomicInteger();
        ScriptBehaviourTest.Host h = slowHost(runs);
        OmuiArchive doc = doc("""
                ui.action("test:net.resync", {})
                ui.q("#out"):setText("resynced " .. tostring(ui.q("#out"):text() ~= nil))
            """);
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, h.host,
            UiScriptOptions.DEFAULTS.withDeadline(50), UiScriptServices.NONE)) {
            rig.click("bad");
            assertFalse(rig.codes().contains(UiScriptDiagnostic.Code.DEADLINE), rig.codes().toString());
            assertEquals("resynced true", rig.text("out"));
            rig.click("bad");
            assertEquals(2, runs.get(), "the handler is still enabled");
            assertTrue(rig.codes().isEmpty(), rig.codes().toString());
        }
    }

    @Test
    void aRunawayLoopAfterTheSlowActionStillTripsTheDeadline() {
        AtomicInteger runs = new AtomicInteger();
        ScriptBehaviourTest.Host h = slowHost(runs);
        OmuiArchive doc = doc("""
                ui.action("test:net.resync", {})
                while true do end
            """);
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, h.host,
            UiScriptOptions.DEFAULTS.withDeadline(50), UiScriptServices.NONE)) {
            long t0 = System.nanoTime();
            rig.click("bad");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 120 + 1000, "stopped after " + ms + " ms");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.DEADLINE), rig.codes().toString());
            assertEquals(1, runs.get());
        }
    }
}
