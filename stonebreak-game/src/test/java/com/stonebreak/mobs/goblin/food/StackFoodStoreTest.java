package com.stonebreak.mobs.goblin.food;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackFoodStoreTest {

    @Test
    void onlyMeatCountsAsFood() {
        StackFoodStore store = new StackFoodStore(4);
        store.add(new ItemStack(ItemType.RAW_BEEF, 2));
        store.add(new ItemStack(ItemType.BANANA, 5));
        assertEquals(6, store.food());
    }

    @Test
    void stacksFillBeforeNewSlotsAndTheRestComesBack() {
        StackFoodStore store = new StackFoodStore(1);
        assertNull(store.add(new ItemStack(ItemType.RAW_MUTTON, 60)));
        ItemStack left = store.add(new ItemStack(ItemType.RAW_MUTTON, 10));
        assertEquals(64, store.slots()[0].getCount());
        assertEquals(6, left.getCount());
        assertFalse(store.hasRoomFor(new ItemStack(ItemType.RAW_MUTTON, 1)));
        assertFalse(store.hasFreeSlot());
    }

    @Test
    void eatingTakesOneItemAndEmptiesItsSlot() {
        StackFoodStore store = new StackFoodStore(2);
        store.add(new ItemStack(ItemType.RAW_CHICKEN, 1));
        assertEquals(1, store.eatOne());
        assertEquals(0, store.eatOne(), "nothing left");
        assertTrue(store.isEmpty());
    }

    @Test
    void aUnionEatsFromWhicheverPocketHasMeat() {
        StackFoodStore a = new StackFoodStore(1);
        StackFoodStore b = new StackFoodStore(1);
        b.add(new ItemStack(ItemType.RAW_BEEF, 1));
        FoodStore both = FoodStore.union(List.of(a, b));
        assertEquals(3, both.food());
        assertEquals(3, both.eatOne());
        assertEquals(0, both.food());
    }

    @Test
    void aChestBackedStoreNeverLeavesNullSlots() {
        // A chest's slots are read by the container code as never-null (slot.get().isEmpty()).
        ItemStack[] chest = {
                com.stonebreak.items.ItemStackStateCodec.empty(),
                com.stonebreak.items.ItemStackStateCodec.empty()};
        StackFoodStore store = new StackFoodStore(chest);
        store.add(new ItemStack(ItemType.RAW_BEEF, 1));
        store.add(new ItemStack(ItemType.BANANA, 1));
        assertEquals(3, store.eatOne());
        assertTrue(chest[0] != null && chest[0].isEmpty(), "eaten slot is an empty stack, not null");
        assertEquals(1, store.takeAll().length);
        for (ItemStack s : chest) {
            assertTrue(s != null && s.isEmpty(), "taken slot is an empty stack, not null");
        }
    }
}
