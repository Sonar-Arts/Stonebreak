package com.stonebreak.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.ui.LoadProgressTracker.Phase;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadProgressTrackerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final double EPS = 1e-6;

    private final long[] now = {0L};

    private LoadProgressTracker tracker(Path store) {
        return new LoadProgressTracker(store, () -> now[0]);
    }

    private void advance(double seconds) {
        now[0] += (long) (seconds * SECOND);
    }

    @Test
    void etaBeforeAnyPhaseIsTheSumOfDefaults() {
        LoadProgressTracker t = tracker(null);
        t.start();

        LoadProgressTracker.Snapshot s = t.snapshot();

        assertEquals(30.0 + 5.0 + 20.0 + 2.0, s.etaSeconds(), EPS);
        assertEquals(0.0, s.fraction(), EPS);
    }

    @Test
    void skippedPhasesDropOutOfTheEstimate() {
        LoadProgressTracker t = tracker(null);
        t.start();

        t.beginPhase(Phase.PREGEN); // services + spawn search never ran

        assertEquals(20.0 + 2.0, t.snapshot().etaSeconds(), EPS);
    }

    @Test
    void liveRateReplacesTheExpectationOnceTheFractionIsTrustworthy() {
        LoadProgressTracker t = tracker(null);
        t.start();
        t.beginPhase(Phase.PREGEN);

        advance(10.0);
        t.setPhaseFraction(0.5); // 10 s for half the chunks → 10 s left, not the 20 s default

        LoadProgressTracker.Snapshot s = t.snapshot();
        assertEquals(10.0 + 2.0, s.etaSeconds(), EPS);
        assertEquals(10.0 / (10.0 + 12.0), s.fraction(), EPS);
    }

    @Test
    void overrunningPhaseWithoutProgressNeverPredictsZero() {
        LoadProgressTracker t = tracker(null);
        t.start();
        t.beginPhase(Phase.STREAM);

        advance(60.0); // 30x the 2 s expectation, no fraction reported

        assertTrue(t.snapshot().etaSeconds() > 0.0);
    }

    @Test
    void fractionNeverMovesBackwards() {
        LoadProgressTracker t = tracker(null);
        t.start();
        t.beginPhase(Phase.PREGEN);
        advance(10.0);
        t.setPhaseFraction(0.9);
        double high = t.snapshot().fraction();

        t.beginPhase(Phase.STREAM);
        advance(0.1);
        t.setPhaseFraction(0.0);

        assertTrue(t.snapshot().fraction() >= high);
    }

    @Test
    void phaseFractionIgnoresRegressions() {
        LoadProgressTracker t = tracker(null);
        t.start();
        t.beginPhase(Phase.PREGEN);
        advance(10.0);
        t.setPhaseFraction(0.5);
        t.setPhaseFraction(0.1);

        assertEquals(12.0, t.snapshot().etaSeconds(), EPS);
    }

    @Test
    void earlierPhaseAfterALaterOneIsIgnored() {
        LoadProgressTracker t = tracker(null);
        t.start();
        t.beginPhase(Phase.STREAM);

        t.beginPhase(Phase.SERVICES);

        assertEquals(Phase.STREAM, t.snapshot().phase());
    }

    @Test
    void finishedLoadBecomesTheNextEstimateForItsProfile(@TempDir Path dir) {
        Path store = dir.resolve("timings.properties");
        LoadProgressTracker first = tracker(store);
        first.start();
        first.setProfile("DIFFUSION.new");
        first.beginPhase(Phase.PREGEN);
        advance(12.0);
        first.beginPhase(Phase.STREAM);
        advance(1.0);
        first.finish();

        LoadProgressTracker second = tracker(store);
        second.start();
        second.setProfile("DIFFUSION.new");

        // Skipped phases were stored as 0; the ones that ran as measured.
        assertEquals(12.0 + 1.0, second.snapshot().etaSeconds(), 1e-3);
    }

    @Test
    void storedDurationsBlendWithHistory(@TempDir Path dir) {
        Path store = dir.resolve("timings.properties");
        for (double pregenSeconds : new double[] {10.0, 20.0}) {
            LoadProgressTracker t = tracker(store);
            t.start();
            t.beginPhase(Phase.PREGEN);
            advance(pregenSeconds);
            t.finish();
        }

        LoadProgressTracker next = tracker(store);
        next.start();
        next.beginPhase(Phase.PREGEN);

        // PREGEN blends 10 and 20; STREAM was never reached, so it keeps its default.
        assertEquals(15.0 + 2.0, next.snapshot().etaSeconds(), 1e-3);
    }

    @Test
    void phaseThatWasSkippedLastTimeFallsBackToItsDefaultWhenItRuns(@TempDir Path dir) {
        Path store = dir.resolve("timings.properties");
        LoadProgressTracker warm = tracker(store);
        warm.start();
        warm.beginPhase(Phase.STREAM); // services were already up
        warm.finish();

        LoadProgressTracker cold = tracker(store);
        cold.start();
        cold.beginPhase(Phase.SERVICES);

        assertTrue(cold.snapshot().etaSeconds() >= 30.0 - EPS);
    }
}
