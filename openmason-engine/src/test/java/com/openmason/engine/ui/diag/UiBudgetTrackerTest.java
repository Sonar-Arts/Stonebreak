package com.openmason.engine.ui.diag;

import com.openmason.engine.ui.diag.UiBudgetTracker.Metric;
import com.openmason.engine.ui.diag.UiBudgetTracker.Overrun;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The soft-budget rules of #296 on synthetic measurements (no clock, no native library). */
class UiBudgetTrackerTest {

    private static final long MS = 1_000_000L;

    private final List<Overrun> seen = new ArrayList<>();
    private final UiBudgetTracker menu = new UiBudgetTracker(UiBudgets.MENU, 60, seen::add);

    @Test
    void scriptTimeIsJudgedOnTheWindowMeanNotOneSpike() {
        menu.frame(5 * MS, 0); // one 5 ms hitch (GC, JIT) on the first frame
        for (int i = 0; i < 59; i++) {
            menu.frame(MS / 10, 0);
        }
        assertTrue(seen.isEmpty(), "mean 0.18 ms is inside 0.5 ms: " + seen);
        assertEquals(5.0, menu.snapshot().scriptMaxMillis(), 1e-9);

        for (int i = 0; i < 60; i++) {
            menu.frame(MS, 0); // sustained 1 ms per frame
        }
        assertEquals(1, seen.size());
        Overrun o = seen.get(0);
        assertEquals(Metric.SCRIPT_FRAME, o.metric());
        assertEquals(0.5, o.budget());
        assertTrue(o.value() > 0.5, o.message());
        assertEquals(Set.of(Metric.SCRIPT_FRAME), menu.snapshot().over());
    }

    @Test
    void noVerdictBeforeThirtyFrames() {
        for (int i = 0; i < 29; i++) {
            menu.frame(10 * MS, 0);
        }
        assertTrue(seen.isEmpty());
        menu.frame(10 * MS, 0);
        assertEquals(List.of(Metric.SCRIPT_FRAME, Metric.SCRIPT_SPIKE), seen.stream().map(Overrun::metric).toList());
    }

    @Test
    void recurringOneFrameStallsAreSpikesEvenWhenTheMeanIsFine() {
        for (int i = 0; i < 60; i++) {
            menu.frame(i % 20 == 10 ? 6 * MS : MS / 20, 0); // a 6 ms stall every 20 frames
        }
        assertEquals(List.of(Metric.SCRIPT_SPIKE), seen.stream().map(Overrun::metric).toList(),
            "mean " + menu.snapshot().scriptMeanMillis() + " ms is inside 0.5 ms; the stalls are not");
        assertTrue(seen.get(0).message().contains("spike limit is 4.00 ms"), seen.get(0).message());
        for (int i = 0; i < 60; i++) {
            menu.frame(MS / 20, 0);
        }
        assertEquals(Set.of(), menu.snapshot().over(), "a window without spikes clears it");
    }

    @Test
    void theFirstLayoutAfterAReloadIsColdAgain() {
        menu.layout(20 * MS, 50); // cold
        for (int i = 0; i < 3; i++) {
            menu.layout(MS / 100, 50);
        }
        for (int reload = 0; reload < 5; reload++) {
            menu.coldStart();
            menu.layout(20 * MS, 50); // rebuilt tree after each reload
            menu.layout(MS / 100, 50);
        }
        assertTrue(seen.isEmpty(), "reload rebuilds are not a layout trend: " + seen);
        assertEquals(20.0, menu.snapshot().coldLayoutMillis(), 1e-9);
    }

    @Test
    void hudBudgetsAreTighterThanMenusAndChosenByTheHost() {
        assertTrue(UiBudgets.HUD.scriptFrameMillis() < UiBudgets.MENU.scriptFrameMillis());
        assertTrue(UiBudgets.HUD.deadlineMillis() <= 16.7, "a HUD stall must stay within one frame");
        assertEquals(2.0, UiBudgets.HUD.scriptSpikeMillis(), 1e-9);
    }

    @Test
    void reportsOnTheRisingEdgeWithACooldown() {
        for (int i = 0; i < 40; i++) {
            menu.frame(MS, 0);
        }
        assertEquals(1, seen.size(), "stays over: no repeat");
        for (int i = 0; i < 60; i++) {
            menu.frame(0, 0);
        }
        assertTrue(menu.snapshot().over().isEmpty(), "cleared below 80 %");
        for (int i = 0; i < 60; i++) {
            menu.frame(MS, 0);
        }
        assertEquals(2, seen.size(), "over again after the cool-down");
        assertEquals(2, menu.snapshot().overruns());
    }

    @Test
    void memoryWarnsBeforeTheHardCapAndClearsWithHysteresis() {
        long cap = UiBudgets.MENU.memoryBytes();
        menu.frame(0, (long) (cap * 0.79));
        assertTrue(seen.isEmpty(), "79 % of the cap is fine");
        menu.frame(0, (long) (cap * 0.81));
        assertEquals(Metric.MEMORY, seen.get(0).metric(), "warned while the Lua state can still allocate");
        menu.frame(0, (long) (cap * 0.75));
        assertEquals(Set.of(Metric.MEMORY), menu.snapshot().over(), "75 % is not yet clear (needs < 72 %)");
        menu.frame(0, cap / 2);
        assertTrue(menu.snapshot().over().isEmpty());
        assertEquals((long) (cap * 0.81), menu.snapshot().memoryPeakBytes());
    }

    @Test
    void layoutHoveringAtTheThresholdDoesNotFlap() {
        menu.layout(MS, 46); // cold
        for (int i = 0; i < 3; i++) {
            menu.layout(MS, 46);
        }
        assertEquals(1, menu.snapshot().overruns());
        for (int i = 0; i < 64; i++) {
            menu.layout(i % 2 == 0 ? MS : MS / 20, 46); // half the relayouts over budget
        }
        assertEquals(1, menu.snapshot().overruns(), "one steady condition is one overrun");
        for (int i = 0; i < 16; i++) {
            menu.layout(MS / 20, 46);
        }
        assertTrue(menu.snapshot().over().isEmpty(), "clears once at most one of 16 is over");
    }

    @Test
    void theColdFirstLayoutIsRecordedButNotJudged() {
        menu.layout(3 * MS, 46);
        assertTrue(seen.isEmpty());
        assertEquals(3.0, menu.snapshot().coldLayoutMillis(), 1e-9);
        assertEquals(0, menu.snapshot().layouts());

        menu.layout(MS / 20, 46);
        assertTrue(seen.isEmpty(), "0.05 ms is inside 0.25 ms");
        menu.layout(MS / 2, 46);
        menu.layout(MS / 2, 46);
        assertTrue(seen.isEmpty(), "two spikes are not a trend");
        menu.layout(MS / 2, 46);
        assertEquals(Metric.LAYOUT, seen.get(0).metric(), "the third of 16 is");
        assertEquals(4, menu.snapshot().layouts());
        assertEquals(0.5, menu.snapshot().layoutMaxMillis(), 1e-9);
    }

    @Test
    void layoutSpikesThatAgeOutOfTheWindowDoNotAccumulate() {
        menu.layout(MS, 46); // cold
        for (int i = 0; i < 64; i++) {
            menu.layout(i % 8 == 0 ? MS : MS / 20, 46); // 2 spikes per 16
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void layoutBudgetScalesPastTheNodeLimit() {
        assertEquals(0.25, UiBudgets.MENU.layoutMillisFor(46), 1e-12);
        assertEquals(0.5, UiBudgets.MENU.layoutMillisFor(200), 1e-12);
        menu.layout(MS, 10);
        menu.layout(MS * 4 / 10, 200); // 0.4 ms for 200 nodes: inside 0.5 ms
        assertTrue(seen.isEmpty());
    }

    @Test
    void snapshotLinesAreAsciiAndMarkOverMetrics() {
        menu.frame(0, UiBudgets.MENU.memoryBytes() * 2);
        List<String> lines = menu.snapshot().lines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("! lua heap 8.00 MiB"), lines.get(1));
        for (String l : lines) {
            assertTrue(l.chars().allMatch(ch -> ch < 128), "ASCII only for ImGui: " + l);
        }
    }

    @Test
    void minigameDocumentsGetTheLargerBudgets() {
        assertEquals(2.0, UiBudgets.MINIGAME.scriptFrameMillis());
        assertEquals(32L << 20, UiBudgets.MINIGAME.scriptOptions().memoryLimitBytes());
        assertEquals(50, UiBudgets.MENU.scriptOptions().deadlineMillis());
        assertEquals(4L << 20, UiBudgets.MENU.scriptOptions().memoryLimitBytes());
    }
}
