package com.stonebreak.mobs.goblin.camp;

/**
 * When a camp sends a pack to raid a rival camp: when it is going hungry, or when it clearly
 * outnumbers the rival. Pure policy.
 */
public final class RaidPlanner {

    /** How far a camp looks for a rival to raid. */
    public static final float RANGE = 96.0f;
    /** Food per goblin below which a camp is hungry enough to raid. */
    public static final float HUNGRY_BELOW = 0.25f;
    /** How many times the rival's numbers a camp needs to raid it on strength alone. */
    public static final float STRONGER_BY = 1.5f;
    /** Seconds between a camp's raids. */
    public static final float COOLDOWN_SECONDS = 600.0f;

    private RaidPlanner() {
    }

    public static boolean shouldRaid(int population, float food, int rivalPopulation) {
        if (population < 2) {
            return false;
        }
        return food < HUNGRY_BELOW * population || population >= STRONGER_BY * rivalPopulation;
    }
}
