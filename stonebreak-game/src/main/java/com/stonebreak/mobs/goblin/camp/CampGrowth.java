package com.stonebreak.mobs.goblin.camp;

/**
 * How a camp grows: food put by beyond a day's eating pays for another goblin. Pure policy — the
 * numbers in one place, no world.
 */
public final class CampGrowth {

    /** The most goblins a camp holds. */
    public static final int MAX_POPULATION = 12;
    /** Meals a new goblin costs. */
    public static final int COST = 4;
    /** Seconds between new goblins, however rich the camp. */
    public static final float INTERVAL_SECONDS = 120.0f;
    /** Goblins per pack: a camp's goblins go out in bands this size. */
    public static final int PACK_SIZE = 4;

    private CampGrowth() {
    }

    /**
     * Whether a camp of {@code population} with {@code stored} meals put by can afford another
     * goblin: room under the cap, and a day's eating ({@code population} meals) still left over
     * after paying.
     */
    public static boolean canGrow(int population, int stored) {
        return population > 0 && population < MAX_POPULATION && stored >= population + COST;
    }
}
