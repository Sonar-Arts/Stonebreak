package com.stonebreak.mobs.goblin.food;

import com.stonebreak.world.TimeOfDay;

/**
 * The goblins' stomach and their reason to hunt — pure bookkeeping, no world.
 *
 * <p>Hunger builds at {@link #MEALS_PER_GOBLIN_PER_DAY} per goblin per in-game day and is paid by
 * eating whole meat items from a {@link FoodStore}. An item worth more than is owed leaves the
 * rest as credit: one cow's beef feeds three goblins one meal each, not one goblin three times.
 *
 * <p>A hunt starts when the food in hand (store plus credit) falls below {@link #HUNT_BELOW} meals
 * per goblin — and never sooner than {@link #MIN_COOLDOWN_SECONDS} after the last one ended — and
 * it is done once there is a meal for every goblin. Overshoot is fine: a cow brought down for a
 * pack of two is still a whole cow.
 */
public final class FoodLedger {

    /** Meals each goblin eats per in-game day. */
    public static final float MEALS_PER_GOBLIN_PER_DAY = 1.0f;
    /** Food per goblin below which the pack goes hunting. */
    public static final float HUNT_BELOW = 0.5f;
    /** Rest after a hunt before the next may start, however hungry. */
    public static final float MIN_COOLDOWN_SECONDS = 180.0f;

    private float owed;
    private float credit;
    private float cooldown;

    /**
     * Advances {@code deltaSeconds}: hunger builds for {@code population} goblins and is paid from
     * {@code store} as far as it can be.
     */
    public void tick(float deltaSeconds, int population, FoodStore store) {
        cooldown = Math.max(0.0f, cooldown - deltaSeconds);
        owed += population * MEALS_PER_GOBLIN_PER_DAY * deltaSeconds / TimeOfDay.DAY_LENGTH_SECONDS;
        // Unpaid hunger does not pile up past a day's worth: a starving pack is just hungry.
        owed = Math.min(owed, Math.max(1.0f, population * MEALS_PER_GOBLIN_PER_DAY));
        while (owed > credit) {
            int meal = store.eatOne();
            if (meal <= 0) {
                break;
            }
            credit += meal;
        }
        float paid = Math.min(owed, credit);
        owed -= paid;
        credit -= paid;
    }

    /** Meals in hand: what the store holds plus what is left of an item already started. */
    public float foodInHand(FoodStore store) {
        return store.food() + credit;
    }

    public boolean shouldHunt(FoodStore store, int population) {
        return cooldown <= 0.0f && foodInHand(store) < HUNT_BELOW * population;
    }

    /**
     * Whether there is a meal for every goblin — the hunt can end. Within half a meal: the item
     * being eaten is already a sliver short by the time the hunters are counted.
     */
    public boolean satisfied(FoodStore store, int population) {
        return foodInHand(store) > population - 0.5f;
    }

    /**
     * Takes over {@code other}'s stomach — a band settling into a camp brings its hunger, the meal
     * it is part-way through, and its rest after the last hunt.
     */
    public void absorb(FoodLedger other) {
        owed += other.owed;
        credit += other.credit;
        cooldown = Math.max(cooldown, other.cooldown);
        other.owed = 0.0f;
        other.credit = 0.0f;
    }

    /** Meals left over from something eaten outside the ledger (a goblin paid for out of the stash). */
    public void addCredit(float meals) {
        credit += Math.max(0.0f, meals);
    }

    /** A hunt ended: rest before the next. */
    public void huntEnded() {
        cooldown = MIN_COOLDOWN_SECONDS;
    }

    /** Seconds until the pack may hunt again. */
    public float cooldown() {
        return cooldown;
    }
}
