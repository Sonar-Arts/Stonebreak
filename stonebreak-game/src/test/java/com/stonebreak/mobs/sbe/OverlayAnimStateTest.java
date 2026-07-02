package com.stonebreak.mobs.sbe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayAnimStateTest {

    private static final float EPS = 1e-4f;
    private static final float FADE = 0.2f;

    /** Advance in small fixed steps so elapsed matches exactly. */
    private static void step(OverlayAnimState s, float seconds, boolean active) {
        int steps = Math.round(seconds / 0.01f);
        for (int i = 0; i < steps; i++) {
            s.update(0.01f, active);
        }
    }

    @Test
    void rampsInWhileActive() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);                    // entry frame, elapsed = 0
        assertEquals(0f, s.weight(FADE, FADE), EPS);

        step(s, 0.1f, true);                   // halfway through fade-in
        assertEquals(0.5f, s.weight(FADE, FADE), 0.06f);

        step(s, 0.3f, true);                   // past fade-in
        assertEquals(1f, s.weight(FADE, FADE), EPS);
        assertTrue(s.isVisible());
    }

    @Test
    void zeroFadeInSnapsToFull() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);
        assertEquals(1f, s.weight(0f, FADE), EPS);
    }

    @Test
    void timeAdvancesWhileActiveAndDuringFadeOut() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);
        step(s, 0.5f, true);
        assertEquals(0.5f, s.time(), 0.02f);

        step(s, 0.1f, false);                  // deactivated — clock keeps going
        assertEquals(0.6f, s.time(), 0.03f);
    }

    @Test
    void earlyExitFadesFromPartialWeight() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);
        step(s, 0.1f, true);                   // ~0.5 weight, mid fade-in
        float atExit = s.weight(FADE, FADE);
        assertTrue(atExit > 0.3f && atExit < 0.7f);

        s.update(0.001f, false);               // cancel mid-swing
        float justAfter = s.weight(FADE, FADE);
        assertTrue(justAfter <= atExit + EPS, "no pop upward on exit");
        assertTrue(justAfter > atExit * 0.8f, "no pop downward on exit");

        step(s, FADE, false);                  // full fade-out elapsed
        assertEquals(0f, s.weight(FADE, FADE), 0.06f);
    }

    @Test
    void naturalHoldThenExitReachesZeroAndInvisible() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);
        step(s, 1f, true);                     // fully in
        assertEquals(1f, s.weight(FADE, FADE), EPS);

        step(s, FADE * 2f, false);
        assertEquals(0f, s.weight(FADE, FADE), EPS);
        assertFalse(s.isVisible());
    }

    @Test
    void retriggerDuringFadeOutRestartsClipWithoutPop() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);
        step(s, 1f, true);                     // weight 1
        s.weight(FADE, FADE);

        step(s, 0.05f, false);                 // partial fade-out
        float residual = s.weight(FADE, FADE);
        assertTrue(residual > 0.5f && residual < 1f);

        s.update(0.001f, true);                // re-trigger: new swing
        assertEquals(0f, s.time(), 0.01f);     // clip restarts
        float w = s.weight(FADE, FADE);
        assertTrue(w >= residual - 0.05f, "residual weight floors the re-entry ramp");
    }

    @Test
    void resetClearsEverything() {
        OverlayAnimState s = new OverlayAnimState();
        s.update(0f, true);
        step(s, 1f, true);
        s.weight(FADE, FADE);
        s.reset();
        assertFalse(s.isVisible());
        assertEquals(0f, s.time(), EPS);
        assertEquals(0f, s.weight(FADE, FADE), EPS);
    }
}
