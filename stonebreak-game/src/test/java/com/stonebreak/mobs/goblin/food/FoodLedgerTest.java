package com.stonebreak.mobs.goblin.food;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.world.TimeOfDay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a pack is hungry enough to hunt, when it has caught enough, and how it eats. */
class FoodLedgerTest {

    private static final float DAY = TimeOfDay.DAY_LENGTH_SECONDS;

    private static StackFoodStore storeWith(ItemType item, int count) {
        StackFoodStore store = new StackFoodStore(4);
        if (count > 0) {
            assertNull(store.add(new ItemStack(item, count)));
        }
        return store;
    }

    @Test
    void anEmptyHandedPackHuntsAtOnce() {
        FoodLedger ledger = new FoodLedger();
        assertTrue(ledger.shouldHunt(storeWith(ItemType.RAW_BEEF, 0), 3));
    }

    @Test
    void aHuntIsDoneOnceThereIsAMealForEveryGoblin() {
        FoodLedger ledger = new FoodLedger();
        assertFalse(ledger.satisfied(storeWith(ItemType.RAW_MUTTON, 1), 3), "one sheep feeds two");
        assertTrue(ledger.satisfied(storeWith(ItemType.RAW_BEEF, 1), 3), "one cow feeds three");
        assertTrue(ledger.satisfied(storeWith(ItemType.RAW_MUTTON, 2), 3), "overshoot is fine");
    }

    @Test
    void aFedPackRestsAfterItsHuntEvenIfHungryAgain() {
        FoodLedger ledger = new FoodLedger();
        StackFoodStore empty = storeWith(ItemType.RAW_BEEF, 0);
        ledger.huntEnded();
        assertFalse(ledger.shouldHunt(empty, 2), "cooling down");
        ledger.tick(FoodLedger.MIN_COOLDOWN_SECONDS + 1, 2, empty);
        assertTrue(ledger.shouldHunt(empty, 2));
    }

    @Test
    void aCowFeedsThreeGoblinsAMealEachNotOneGoblinThreeTimes() {
        FoodLedger ledger = new FoodLedger();
        StackFoodStore store = storeWith(ItemType.RAW_BEEF, 1);
        // Three goblins eat one meal each over a day: exactly one beef, nothing left over.
        for (int i = 0; i < 100; i++) {
            ledger.tick(DAY / 100, 3, store);
        }
        assertEquals(0, store.food(), "the beef was eaten");
        assertEquals(0.0f, ledger.foodInHand(store), 0.05f, "and fed the three of them");
    }

    @Test
    void foodRunsDownUntilThePackHuntsAgain() {
        FoodLedger ledger = new FoodLedger();
        StackFoodStore store = storeWith(ItemType.RAW_MUTTON, 2); // 4 meals for 4 goblins
        assertFalse(ledger.shouldHunt(store, 4));
        float hours = 0;
        while (!ledger.shouldHunt(store, 4) && hours < 48) {
            ledger.tick(DAY / 24, 4, store);
            hours++;
        }
        // Below half a meal each = under 2 of 4 meals left, i.e. a little over half a day.
        assertTrue(hours > 12 && hours <= 14, "hunted again after " + hours + "h");
    }
}
