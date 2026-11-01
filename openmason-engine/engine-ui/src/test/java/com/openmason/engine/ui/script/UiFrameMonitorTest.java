package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.diag.UiBudgetTracker;
import com.openmason.engine.ui.diag.UiBudgets;
import com.openmason.engine.ui.diag.UiFrameMonitor;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live budget monitor (#296) on a real scripted view: it charges Lua time to frames, reads
 * the Lua heap, judges relayouts, and reports overruns as instance diagnostics. Budgets are set
 * absurdly tight or loose so the verdicts never depend on machine speed.
 */
class UiFrameMonitorTest {

    private static OmuiArchive doc(String lua) {
        return ScriptRig.withCode(screen("t:ui/budget", box("root").style("width", 400).style("height", 300)
            .kids(label("out", "0").name("out"))), lua);
    }

    private static UiBudgets budgets(double scriptMs, long memory, double layoutMs) {
        return new UiBudgets(UiBudgets.Kind.MENU, scriptMs, memory, 50, layoutMs, 100);
    }

    @Test
    void sustainedScriptWorkGoesOverTheFrameBudget() {
        try (ScriptRig rig = new ScriptRig(doc("""
            local n = 0
            function update(dt)
              for i = 1, 20000 do n = n + i end
            end
            """))) {
            List<UiBudgetTracker.Overrun> seen = new ArrayList<>();
            UiFrameMonitor m = UiFrameMonitor.attach(rig.view, budgets(1e-6, 1L << 30, 1e3)).onOverrun(seen::add);
            assertSame(m, UiFrameMonitor.attach(rig.view), "one monitor per view");
            for (int i = 0; i < 40; i++) {
                rig.frame(1 / 60.0);
            }
            assertEquals(UiBudgetTracker.Metric.SCRIPT_FRAME, seen.get(0).metric(), seen.toString());
            assertTrue(m.snapshot().scriptMeanMillis() > 0, "Lua time was charged to frames");
            assertTrue(rig.ui.diagnostics().stream()
                .anyMatch(d -> d.code() == UiRuntimeDiagnostic.Code.BUDGET_SCRIPT_FRAME), rig.ui.diagnostics().toString());
        }
    }

    @Test
    void loadAndOnOpenAreNotChargedToTheFirstFrame() {
        try (ScriptRig rig = new ScriptRig(doc("""
            function on_open()
              local n = 0
              for i = 1, 2000000 do n = n + i end
            end
            """))) {
            UiFrameMonitor m = UiFrameMonitor.attach(rig.view, budgets(1e3, 1L << 30, 1e3));
            rig.frame(1 / 60.0);
            assertTrue(m.snapshot().scriptLastMillis() < 1, "on_open happened before the monitor's baseline: "
                + m.snapshot().scriptLastMillis());
        }
    }

    @Test
    void heapOverTheBudgetIsReported() {
        try (ScriptRig rig = new ScriptRig(doc("""
            big = {}
            for i = 1, 20000 do big[i] = "entry " .. i end
            """))) {
            List<UiBudgetTracker.Overrun> seen = new ArrayList<>();
            UiFrameMonitor m = UiFrameMonitor.attach(rig.view, budgets(1e3, 64 * 1024, 1e3)).onOverrun(seen::add);
            rig.frame(1 / 60.0);
            assertEquals(UiBudgetTracker.Metric.MEMORY, seen.get(0).metric());
            assertTrue(m.snapshot().memoryBytes() > 64 * 1024);
        }
    }

    @Test
    void relayoutsAreJudgedAfterTheColdOne() {
        try (ScriptRig rig = new ScriptRig(doc("""
            local n = 0
            function update(dt)
              n = n + 1
              ui.q("#out"):setText(string.rep("x", n % 7 + 1))
            end
            """))) {
            List<UiBudgetTracker.Overrun> seen = new ArrayList<>();
            UiFrameMonitor m = UiFrameMonitor.attach(rig.view, budgets(1e3, 1L << 30, 1e-9)).onOverrun(seen::add);
            for (int i = 0; i < 5; i++) {
                rig.frame(1 / 60.0);
            }
            assertTrue(m.snapshot().layouts() >= 4, "each text change relayouts: " + m.snapshot());
            assertEquals(UiBudgetTracker.Metric.LAYOUT, seen.get(0).metric());
            assertEquals(2, m.snapshot().nodes());
        }
    }

    @Test
    void aDocumentWithoutScriptsIsMonitoredToo() {
        UiDocs.N root = box("root").style("width", 100).style("height", 50);
        try (ScriptRig rig = new ScriptRig(screen("t:ui/static", root), UiDocumentSource.EMPTY, null,
            UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            UiFrameMonitor m = UiFrameMonitor.attach(rig.view);
            assertEquals(UiBudgets.MENU, m.budgets());
            rig.frame(1 / 60.0);
            assertEquals(0, m.snapshot().memoryBytes());
            assertEquals(1, m.snapshot().frames());
        }
    }
}
