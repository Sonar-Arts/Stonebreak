package com.openmason.main.systems.mortar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-part hover/press/selected blends the region animates on a part's behalf. */
class PartStateTest {

    @Test
    void startsAtRest() {
        PartState state = new PartState();
        assertEquals(0f, state.hover());
        assertEquals(0f, state.press());
        assertEquals(0f, state.selected());
    }

    @Test
    void targetsOnlyTakeEffectOnUpdate() {
        PartState state = new PartState();
        state.setTargets(true, true, true);
        assertEquals(0f, state.hover(), "retargeting alone must not jump the blend");
        state.update(1f / 60f);
        assertTrue(state.hover() > 0f && state.hover() < 1f);
        assertTrue(state.press() > 0f && state.press() < 1f);
        assertTrue(state.selected() > 0f && state.selected() < 1f);
    }

    @Test
    void pressRespondsFastestAndSelectionSlowest() {
        PartState state = new PartState();
        state.setTargets(true, true, true);
        state.update(1f / 60f);
        assertTrue(state.press() > state.hover(), "press is the snappiest blend");
        assertTrue(state.hover() > state.selected(), "selection glides in slowest");
    }

    @Test
    void settlesOnTargetsAndReleasesBackToRest() {
        PartState state = new PartState();
        state.setTargets(true, false, true);
        for (int i = 0; i < 120; i++) {
            state.update(1f / 60f);
        }
        assertEquals(1f, state.hover());
        assertEquals(0f, state.press());
        assertEquals(1f, state.selected());

        state.setTargets(false, false, false);
        for (int i = 0; i < 120; i++) {
            state.update(1f / 60f);
        }
        assertEquals(0f, state.hover());
        assertEquals(0f, state.selected());
    }

    @Test
    void fixtureSettlesExactly() {
        PartState state = PartStateFixtures.settled(true, true, false);
        assertEquals(1f, state.hover());
        assertEquals(1f, state.press());
        assertEquals(0f, state.selected());
    }
}
