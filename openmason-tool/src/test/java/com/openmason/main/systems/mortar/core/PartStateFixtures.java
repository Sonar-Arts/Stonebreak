package com.openmason.main.systems.mortar.core;

/**
 * Settled {@link PartState}s for paint tests outside this package ({@code setTargets}/{@code update}
 * are package-private): the blends are driven to their 0/1 targets exactly.
 */
public final class PartStateFixtures {

    private PartStateFixtures() {
    }

    public static PartState rest() {
        return settled(false, false, false);
    }

    public static PartState settled(boolean hovered, boolean pressed, boolean selected) {
        PartState state = new PartState();
        state.setTargets(hovered, pressed, selected);
        state.update(10f); // 1 - e^(-rate*10) == 1f for every blend rate, so each lands on its target
        return state;
    }
}
