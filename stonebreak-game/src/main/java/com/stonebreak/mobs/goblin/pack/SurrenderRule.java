package com.stonebreak.mobs.goblin.pack;

/**
 * When a pack in a fight gives up: once it has lost at least half the goblins it went in with, and
 * what it has left is clearly outmatched by the other side. The survivors then join the winners.
 * Pure policy.
 */
public final class SurrenderRule {

    /** Share of its starting strength a pack must have lost before it will give up. */
    public static final float LOSSES = 0.5f;
    /** ...and its strength must be below this share of the other side's. */
    public static final float OUTMATCHED = 0.6f;

    private SurrenderRule() {
    }

    /**
     * @param started  goblins the pack went into the fight with
     * @param now      goblins it has left
     * @param enemyNow goblins the other side has left
     */
    public static boolean surrenders(int started, int now, int enemyNow) {
        return now > 0 && enemyNow > 0
                && now <= started * (1.0f - LOSSES)
                && now < enemyNow * OUTMATCHED;
    }
}
