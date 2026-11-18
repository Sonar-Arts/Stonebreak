package com.stonebreak.ui.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The F3 stream rates: per-second deltas of the native counters, sampled every half second. */
class DebugDiagnosticsStreamRatesTest {

    @Test
    void ratesAreDeltasPerSecond() {
        DebugDiagnostics.StreamRates r = new DebugDiagnostics.StreamRates();
        assertArrayEquals(new double[]{0, 0}, r.sample(0L, new long[]{100, 10}), "first sample: no rate yet");
        double[] mid = r.sample(200_000_000L, new long[]{150, 20});
        assertArrayEquals(new double[]{0, 0}, mid, "under 500 ms: the previous rates hold");
        double[] rates = r.sample(1_000_000_000L, new long[]{400, 30});
        assertEquals(300.0, rates[0], 1e-9);
        assertEquals(20.0, rates[1], 1e-9);
    }

    @Test
    void aCounterGoingBackwardsReadsZero() {
        DebugDiagnostics.StreamRates r = new DebugDiagnostics.StreamRates();
        r.sample(0L, new long[]{500});
        assertEquals(0.0, r.sample(1_000_000_000L, new long[]{10})[0], "a new world restarts the counters");
        assertEquals(5.0, r.sample(2_000_000_000L, new long[]{15})[0], 1e-9);
    }
}
