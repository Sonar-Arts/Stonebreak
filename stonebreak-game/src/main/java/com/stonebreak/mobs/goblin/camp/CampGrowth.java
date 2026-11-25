package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.world.TimeOfDay;

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
    /**
     * The most time a thawed camp lives through for its time frozen: a day. Longer away is still a
     * day, so a camp left alone for a month is not a month further on.
     */
    public static final float MAX_CATCH_UP_SECONDS = TimeOfDay.DAY_LENGTH_SECONDS;

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

    /**
     * In-game seconds a camp frozen at world tick {@code sinceTicks} has to live through at
     * {@code nowTicks}, capped at {@link #MAX_CATCH_UP_SECONDS}. No stamp or no clock (negative),
     * or a stamp in the future (world time set back), is no time at all.
     */
    public static float catchUpSeconds(long sinceTicks, long nowTicks) {
        if (sinceTicks < 0 || nowTicks <= sinceTicks) {
            return 0.0f;
        }
        double seconds = (double) (nowTicks - sinceTicks) * TimeOfDay.DAY_LENGTH_SECONDS / TimeOfDay.TICKS_PER_DAY;
        return (float) Math.min(seconds, MAX_CATCH_UP_SECONDS);
    }
}
