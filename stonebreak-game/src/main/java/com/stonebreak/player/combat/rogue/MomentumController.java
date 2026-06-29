package com.stonebreak.player.combat.rogue;

import static com.stonebreak.player.PlayerConstants.MOMENTUM_MAX_STACKS;

/**
 * The Rogue's Momentum resource: a 0–{@value com.stonebreak.player.PlayerConstants#MOMENTUM_MAX_STACKS}
 * stack counter gained on a successful dodge (and, later, parry) and spent in full the instant a crit
 * lands. Stacks do not decay passively — they persist until a crit consumes them.
 *
 * <p>Pure state: tier multipliers and debuffs are decided by {@link RogueAbilityController} from the
 * count returned by {@link #consumeForCrit()}.</p>
 */
public class MomentumController {

    private int stacks;

    /** Adds one stack, capped at {@link com.stonebreak.player.PlayerConstants#MOMENTUM_MAX_STACKS}. */
    public void addStack() {
        stacks = Math.min(MOMENTUM_MAX_STACKS, stacks + 1);
    }

    /** Current stack count, for the HUD pip display. */
    public int getStacks() {
        return stacks;
    }

    /** Returns the current stack count and resets to zero — called when a crit consumes Momentum. */
    public int consumeForCrit() {
        int consumed = stacks;
        stacks = 0;
        return consumed;
    }

    /** Clears all stacks (world reload). */
    public void reset() {
        stacks = 0;
    }
}
