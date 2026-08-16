package com.stonebreak.player.combat.rogue;

import static com.stonebreak.player.PlayerConstants.MOMENTUM_MAX_STACKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Rogue's Momentum counter: earned per dodge, capped, spent whole the instant a crit lands,
 * and never decaying on its own — persistence until the crit is the mechanic.
 */
class MomentumControllerTest {

    private final MomentumController momentum = new MomentumController();

    @Test
    void dodgesBuildStacksUpToTheCap() {
        for (int i = 0; i < MOMENTUM_MAX_STACKS + 2; i++) {
            momentum.addStack();
        }

        assertEquals(MOMENTUM_MAX_STACKS, momentum.getStacks());
    }

    @Test
    void aCritSpendsEverythingAndReportsWhatItSpent() {
        momentum.addStack();
        momentum.addStack();

        assertEquals(2, momentum.consumeForCrit(),
                "the crit scales off the stacks it consumed");
        assertEquals(0, momentum.getStacks(), "Momentum is all-in — no partial spend");
    }

    @Test
    void aCritWithNoMomentumSpendsNothing() {
        assertEquals(0, momentum.consumeForCrit());
    }

    @Test
    void resetClearsForWorldReload() {
        momentum.addStack();
        momentum.reset();

        assertEquals(0, momentum.getStacks());
    }
}
