package com.openmason.main.systems.mortar.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hub's view-switch transition: restart on a new destination, eased progress, guarded deltas. */
class CrossfadeTest {

    private enum View { HOME, LEARN, SETTINGS }

    @Test
    void startsSettledOnTheInitialState() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.5f);
        assertFalse(fade.isTransitioning());
        assertEquals(1f, fade.alpha());
        assertEquals(View.HOME, fade.current());
        assertEquals(View.HOME, fade.previous());
    }

    @Test
    void newDestinationRestartsFromZeroAndRemembersWhereItCameFrom() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.5f);
        fade.to(View.LEARN);
        assertTrue(fade.isTransitioning());
        assertEquals(0f, fade.alpha());
        assertEquals(View.LEARN, fade.current());
        assertEquals(View.HOME, fade.previous());
    }

    @Test
    void sameOrNullDestinationIsANoOp() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.5f);
        fade.to(View.LEARN);
        fade.update(0.25f);
        float midway = fade.alpha();

        fade.to(View.LEARN);
        fade.to(null);
        assertEquals(midway, fade.alpha(), "re-targeting the current view must not restart it");
        assertEquals(View.LEARN, fade.current());
        assertEquals(View.HOME, fade.previous());
    }

    @Test
    void progressIsLinearInTimeAndAlphaIsEasedOutCubic() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.4f);
        fade.to(View.LEARN);
        fade.update(0.2f); // half the duration
        assertEquals(Easing.easeOutCubic(0.5f), fade.alpha(), 1e-6f);
        assertEquals(0.875f, fade.alpha(), 1e-6f);
        assertTrue(fade.isTransitioning());
    }

    @Test
    void finishesAfterTheDurationAndClampsOvershoot() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.3f);
        fade.to(View.LEARN);
        fade.update(0.1f);
        fade.update(0.1f);
        assertTrue(fade.isTransitioning());
        fade.update(5f); // a long hitch frame
        assertFalse(fade.isTransitioning());
        assertEquals(1f, fade.alpha());
    }

    @Test
    void interruptingMidTransitionRestartsFromTheInterruptedDestination() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.5f);
        fade.to(View.LEARN);
        fade.update(0.2f);
        fade.to(View.SETTINGS);
        assertEquals(0f, fade.alpha());
        assertEquals(View.SETTINGS, fade.current());
        assertEquals(View.LEARN, fade.previous());
    }

    @Test
    void nonPositiveOrNonFiniteDeltaIsIgnored() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0.5f);
        fade.to(View.LEARN);
        fade.update(0f);
        fade.update(-1f);
        fade.update(Float.NaN);
        fade.update(Float.POSITIVE_INFINITY);
        assertEquals(0f, fade.alpha());
        assertTrue(fade.isTransitioning());
    }

    @Test
    void invalidDurationFallsBackToTheDefault() {
        Crossfade<View> fade = new Crossfade<>(View.HOME, 0f);
        fade.to(View.LEARN);
        fade.update(0.1f); // half of the 0.2 s default
        assertEquals(Easing.easeOutCubic(0.5f), fade.alpha(), 1e-6f);
        fade.update(0.1f);
        assertFalse(fade.isTransitioning());
    }
}
