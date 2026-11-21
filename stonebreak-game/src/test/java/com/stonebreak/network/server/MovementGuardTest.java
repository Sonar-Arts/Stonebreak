package com.stonebreak.network.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 3D movement budget (issue #336, cubic world #447): walking and climbing spend, falling never does. */
class MovementGuardTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    void horizontalBurstIsAllowedAndBeyondItRefused() {
        MovementGuard g = new MovementGuard();
        assertTrue(g.allowMove(0, 64, 0, MovementGuard.BURST_BLOCKS - 1, 64, 0, T0));
        assertFalse(g.allowMove(31, 64, 0, 31 + 10, 64, 0, T0), "the burst is spent");
        assertEquals(1, g.refusals());
    }

    @Test
    void climbingSpendsItsOwnBudget() {
        MovementGuard g = new MovementGuard();
        assertTrue(g.allowMove(0, 64, 0, 0, 64 + MovementGuard.CLIMB_BURST_BLOCKS - 1, 0, T0));
        assertFalse(g.allowMove(0, 127, 0, 0, 127 + 20, 0, T0), "a teleport straight up is refused");
        // The refused climb spent nothing horizontally either.
        assertTrue(g.allowMove(0, 127, 0, 20, 127, 0, T0));
    }

    @Test
    void fallingIsNeverRefused() {
        MovementGuard g = new MovementGuard();
        assertTrue(g.allowMove(0, 600, 0, 0, -380, 0, T0), "a 980-block fall in one report");
        assertTrue(g.allowMove(0, -380, 0, 0, -383, 0, T0));
        assertEquals(0, g.refusals());
    }

    @Test
    void budgetsRefillOverTimeAndOnReset() {
        MovementGuard g = new MovementGuard();
        assertTrue(g.allowMove(0, 0, 0, 0, MovementGuard.CLIMB_BURST_BLOCKS, 0, T0));
        assertFalse(g.allowMove(0, 64, 0, 0, 74, 0, T0));
        assertTrue(g.allowMove(0, 64, 0, 0, 74, 0, T0 + 500_000_000L), "half a second refills 40 blocks of climb");
        g.reset(T0 + 500_000_000L);
        assertTrue(g.allowMove(0, 74, 0, 0, 74 + MovementGuard.CLIMB_BURST_BLOCKS, 0, T0 + 500_000_000L));
    }

    @Test
    void farFromTheOriginTheDistanceIsExact() {
        MovementGuard g = new MovementGuard();
        double far = 3.0e12;
        assertTrue(g.allowMove(far, 64, far, far + 10, 64, far, T0));
        assertFalse(g.allowMove(far, 64, far, far + 500, 64, far, T0));
        assertFalse(g.allowMove(0, 64, 0, Double.NaN, 64, 0, T0), "non-finite targets are refused");
    }
}
