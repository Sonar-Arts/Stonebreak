package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import com.stonebreak.world.TimeOfDay;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A camp that thaws lives through the time it spent frozen, once, in coarse steps: it eats from
 * its stash and grows when it can afford to, but earns nothing, loses nobody, and starts no fights.
 */
class CampCatchUpTest {

    /** World ticks in {@code seconds} of in-game time. */
    private static long ticks(float seconds) {
        return Math.round((double) seconds * TimeOfDay.TICKS_PER_DAY / TimeOfDay.DAY_LENGTH_SECONDS);
    }

    /** A frozen camp of {@code goblins}, all on the roster as after a thaw, frozen at tick 0. */
    private static GoblinCamp frozenCamp(int goblins) {
        GoblinCamp camp = new GoblinCamp(new BlockPos(0, 64, 0));
        for (int i = 0; i < goblins; i++) {
            camp.addWaiting(new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        }
        camp.setOfflineSince(0L);
        return camp;
    }

    @Test
    void offlineTimeIsWorldTimeCappedAtADay() {
        assertEquals(120.0f, CampGrowth.catchUpSeconds(1000L, 1000L + ticks(120.0f)), 1e-3f);
        assertEquals(TimeOfDay.DAY_LENGTH_SECONDS, CampGrowth.MAX_CATCH_UP_SECONDS);
        assertEquals(CampGrowth.MAX_CATCH_UP_SECONDS,
                CampGrowth.catchUpSeconds(0L, 10L * TimeOfDay.TICKS_PER_DAY), "ten days away is one day's catch-up");
    }

    @Test
    void aStampInTheFutureOrNoStampIsNoTimeAtAll() {
        assertEquals(0.0f, CampGrowth.catchUpSeconds(5000L, 1000L), "world time went back: nothing passed");
        assertEquals(0.0f, CampGrowth.catchUpSeconds(-1L, 1000L), "no stamp (an old save)");
        assertEquals(0.0f, CampGrowth.catchUpSeconds(1000L, -1L), "no clock");

        GoblinCamp camp = frozenCamp(2);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 10));
        camp.setOfflineSince(5000L);
        assertEquals(0.0f, camp.catchUpTo(1000L, new Random(1)));
        assertEquals(2, camp.population());
        assertEquals(30, camp.stash().food(), "nothing eaten");
        assertEquals(-1L, camp.offlineSince(), "caught up: nothing left owing");
    }

    @Test
    void aWellFedCampGrowsOncePerIntervalItWasAway() {
        GoblinCamp camp = frozenCamp(2);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 10)); // 30 meals

        float lived = camp.catchUpTo(ticks(3 * CampGrowth.INTERVAL_SECONDS), new Random(1));

        assertEquals(3 * CampGrowth.INTERVAL_SECONDS, lived, 1e-3f);
        assertEquals(5, camp.population(), "a new goblin each interval it could afford one");
        assertEquals(5, camp.waiting().size(), "the new ones wait to come out at the fire");
        assertTrue(camp.stash().food() <= 30 - 3 * CampGrowth.COST, "each was paid for: " + camp.stash().food());
    }

    @Test
    void growthStopsAtTheCap() {
        GoblinCamp camp = frozenCamp(2);
        for (int i = 0; i < GoblinCamp.STASH_SLOTS; i++) {
            camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 64));
        }
        camp.catchUpTo(ticks(CampGrowth.MAX_CATCH_UP_SECONDS), new Random(1));
        assertEquals(CampGrowth.MAX_POPULATION, camp.population());
    }

    @Test
    void aDayAwayAndTenDaysAwayCatchUpTheSame() {
        GoblinCamp day = frozenCamp(2);
        GoblinCamp tenDays = frozenCamp(2);
        day.stash().add(new ItemStack(ItemType.COOKED_BEEF, 6));
        tenDays.stash().add(new ItemStack(ItemType.COOKED_BEEF, 6));

        assertEquals(CampGrowth.MAX_CATCH_UP_SECONDS,
                day.catchUpTo(TimeOfDay.TICKS_PER_DAY, new Random(7)));
        assertEquals(CampGrowth.MAX_CATCH_UP_SECONDS,
                tenDays.catchUpTo(10L * TimeOfDay.TICKS_PER_DAY, new Random(7)));
        assertEquals(day.population(), tenDays.population());
        assertEquals(day.stash().food(), tenDays.stash().food());
    }

    @Test
    void hungerIsPaidFromTheStashAsItWouldBeLoaded() {
        GoblinCamp camp = frozenCamp(4);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 2)); // 6 meals: too few to grow on

        camp.catchUpTo(ticks(TimeOfDay.DAY_LENGTH_SECONDS / 2), new Random(1));

        assertEquals(4, camp.population());
        assertEquals(3, camp.stash().food(), "half a day for four is two meals: one beef started");
    }

    @Test
    void nobodyHuntsWhileFrozenSoTheStashNeverFills() {
        GoblinCamp camp = frozenCamp(3);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 1));
        camp.catchUpTo(ticks(CampGrowth.MAX_CATCH_UP_SECONDS), new Random(1));
        assertEquals(0, camp.stash().food(), "eaten, and nothing brought in");
    }

    @Test
    void anEmptyStashStarvesNobody() {
        GoblinCamp camp = frozenCamp(5);
        camp.catchUpTo(ticks(CampGrowth.MAX_CATCH_UP_SECONDS), new Random(1));
        assertEquals(5, camp.population(), "hungry, not dead");
        assertEquals(5, camp.waiting().size());
        assertEquals(0, camp.stash().food());
    }

    @Test
    void aCampWithNoGoblinsStaysEmpty() {
        GoblinCamp camp = frozenCamp(0);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 20));
        camp.catchUpTo(ticks(CampGrowth.MAX_CATCH_UP_SECONDS), new Random(1));
        assertEquals(0, camp.population());
        assertEquals(60, camp.stash().food());
    }

    @Test
    void theRepairWaitCountsDownWhileFrozen() {
        GoblinCamp camp = frozenCamp(2);
        camp.setRepairWait(GoblinCamp.REPAIR_SECONDS);
        camp.catchUpTo(ticks(100.0f), new Random(1));
        assertEquals(GoblinCamp.REPAIR_SECONDS - 100.0f, camp.repairWait(), 0.01f);

        camp.setOfflineSince(0L);
        camp.catchUpTo(ticks(CampGrowth.MAX_CATCH_UP_SECONDS), new Random(1));
        assertEquals(0.0f, camp.repairWait());
    }

    @Test
    void aThawIsCaughtUpOnlyOnce() {
        GoblinCamp camp = frozenCamp(2);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 20));
        long now = ticks(2 * CampGrowth.INTERVAL_SECONDS);

        camp.catchUpTo(now, new Random(1));
        int population = camp.population();
        int food = camp.stash().food();

        assertEquals(0.0f, camp.catchUpTo(now, new Random(1)), "nothing left owing");
        assertEquals(0.0f, camp.catchUpTo(now + TimeOfDay.TICKS_PER_DAY, new Random(1)),
                "time since the catch-up was lived loaded, not frozen");
        assertEquals(population, camp.population());
        assertEquals(food, camp.stash().food());
    }

    @Test
    void aCampFoundedLiveHasNothingToCatchUp() {
        GoblinCamp camp = new GoblinCamp(new BlockPos(0, 64, 0));
        camp.addWaiting(new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 20));
        assertEquals(-1L, camp.offlineSince());
        assertEquals(0.0f, camp.catchUpTo(TimeOfDay.TICKS_PER_DAY, new Random(1)));
        assertEquals(60, camp.stash().food());
    }

    @Test
    void theStampIsWhenItFrozeUntilCaughtUpThenNow() {
        GoblinCamp camp = frozenCamp(1);
        camp.setOfflineSince(1000L);
        assertEquals(1000L, camp.stampAt(9000L), "not lived through yet: still owes from 1000");
        camp.catchUpTo(9000L, new Random(1));
        assertEquals(12000L, camp.stampAt(12000L));
    }
}
