package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.diag.UiBudgetTracker;
import com.openmason.engine.ui.diag.UiBudgets;
import com.openmason.engine.ui.diag.UiFrameMonitor;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Re-measures the #283 budgets on the machine it runs on (#296): relayout per invalidation of a
 * 100-element menu, and Lua time per frame of the scripted pause sample (menu budget) and the
 * minigame sample (minigame budget), through the same {@link UiFrameMonitor} the game uses.
 * The budgets were measured on the dev machine only; run this on the slowest supported machine:
 *
 * <pre>mvn test -pl openmason-engine -Dtest=UiBudgetBenchTest -Dui.bench=true</pre>
 *
 * It prints one table and fails when a p95 is over budget. Opt-in, because timings are machine
 * facts, not regressions.
 */
class UiBudgetBenchTest {

    private static final int WARMUP = 300;
    private static final int FRAMES = 600;

    @Test
    void budgetsHoldOnThisMachine() {
        assumeTrue(Boolean.getBoolean("ui.bench"), "opt-in: -Dui.bench=true");
        ScriptRig.assumeLua();
        List<String> rows = new ArrayList<>();
        List<String> over = new ArrayList<>();

        double[] layout = relayoutMillis();
        row(rows, over, "relayout, 100-element menu", layout, UiBudgets.MENU.layoutMillis());

        double[] menu = scriptMillis(ScriptSamples.scriptedPause(), UiBudgets.MENU);
        row(rows, over, "Lua per frame, scripted pause", menu, UiBudgets.MENU.scriptFrameMillis());

        double[] game = scriptMillis(ScriptSamples.minigame(), UiBudgets.MINIGAME);
        row(rows, over, "Lua per frame, minigame (1k sprites)", game, UiBudgets.MINIGAME.scriptFrameMillis());

        System.out.printf(Locale.ROOT, "[ui-bench] %s %s, %d cores, JDK %s%n", System.getProperty("os.name"),
            System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.vm.version"));
        System.out.println("[ui-bench] | measure | p50 ms | p95 ms | max ms | budget ms |");
        rows.forEach(r -> System.out.println("[ui-bench] " + r));
        assertTrue(over.isEmpty(), "over budget on this machine: " + over);
    }

    private static void row(List<String> rows, List<String> over, String name, double[] ms, double budget) {
        Arrays.sort(ms);
        double p50 = ms[ms.length / 2];
        double p95 = ms[ms.length * 95 / 100];
        rows.add(String.format(Locale.ROOT, "| %s | %.4f | %.4f | %.4f | %.2f |", name, p50, p95, ms[ms.length - 1],
            budget));
        if (p95 > budget) {
            over.add(name + " p95 " + p95 + " ms");
        }
    }

    /** One label's text changes per iteration, so every update re-measures and relayouts. */
    private static double[] relayoutMillis() {
        UiDocs.N[] rows = new UiDocs.N[20];
        for (int r = 0; r < rows.length; r++) {
            UiDocs.N[] cells = new UiDocs.N[4];
            for (int c = 0; c < cells.length; c++) {
                cells[c] = label("l" + r + "_" + c, "cell " + r + "," + c).name("l" + r + "_" + c);
            }
            rows[r] = box("row" + r).style("flex-direction", "row").style("column-gap", 4).kids(cells);
        }
        OmuiArchive doc = screen("t:ui/bench", box("root").style("width", "100%").style("height", "100%").kids(rows));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
        try {
            ui.setMetrics(UiMetrics.of(1920, 1080, 1));
            ui.update();
            assertTrue(ui.elements().size() >= 100, "bench tree size " + ui.elements().size());
            double[] ms = new double[FRAMES];
            for (int i = -WARMUP; i < FRAMES; i++) {
                ui.find("l" + (Math.abs(i) % 20) + "_1").setProp("text", com.openmason.engine.format.omui.UiValue.of("text " + i));
                UiDocumentInstance.UpdateStats s = ui.update();
                if (i >= 0) {
                    ms[i] = s.nanos() / 1e6;
                }
            }
            return ms;
        } finally {
            ui.close();
        }
    }

    /** Frames of a scripted sample through a monitor; returns each frame's Lua time. */
    private static double[] scriptMillis(OmuiArchive doc, UiBudgets budgets) {
        try (ScriptRig rig = new ScriptRig(doc)) {
            UiFrameMonitor m = UiFrameMonitor.attach(rig.view, budgets);
            for (int i = 0; i < WARMUP; i++) {
                rig.frame(1 / 60.0);
            }
            double[] ms = new double[FRAMES];
            for (int i = 0; i < FRAMES; i++) {
                rig.frame(1 / 60.0);
                UiBudgetTracker.Snapshot s = m.snapshot();
                ms[i] = s.scriptLastMillis();
            }
            return ms;
        }
    }
}
