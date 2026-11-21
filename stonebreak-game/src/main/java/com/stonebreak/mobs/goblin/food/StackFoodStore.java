package com.stonebreak.mobs.goblin.food;

import com.stonebreak.items.FoodRules;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;

/**
 * A {@link FoodStore} over a fixed row of item slots. It holds any item (a goblin may pocket
 * whatever the camp gives it), but only meat counts as food.
 */
public class StackFoodStore implements FoodStore {

    private final ItemStack[] slots;
    /** Slots owned by a block grid (chest, rack): emptied with an empty stack, never {@code null}. */
    private final boolean backed;

    public StackFoodStore(int size) {
        this.slots = new ItemStack[size];
        this.backed = false;
    }

    /**
     * Over slots that live elsewhere — a chest's — read and written in place. Emptied slots get
     * {@link ItemStackStateCodec#empty()}: the grid and the container code expect no {@code null}s.
     */
    public StackFoodStore(ItemStack[] backing) {
        this.slots = backing;
        this.backed = true;
    }

    /** The live slots ({@code null} or an empty stack for empty): read them, do not keep them. */
    public ItemStack[] slots() {
        return slots;
    }

    public boolean isEmpty() {
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Whether any slot is empty. */
    public boolean hasFreeSlot() {
        for (ItemStack s : slots) {
            if (s == null || s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether at least one more of {@code stack}'s item would fit. */
    public boolean hasRoomFor(ItemStack stack) {
        for (ItemStack s : slots) {
            if (s == null || s.isEmpty()
                    || (stacksWith(s, stack) && s.getCount() < s.getItem().getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int food() {
        int total = 0;
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) {
                total += FoodRules.goblinFood(s.getItem()) * s.getCount();
            }
        }
        return total;
    }

    @Override
    public int eatOne() {
        for (int i = 0; i < slots.length; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isEmpty() && FoodRules.isMeat(s.getItem())) {
                int value = FoodRules.goblinFood(s.getItem());
                s.decrementCount(1);
                if (s.isEmpty()) {
                    slots[i] = cleared();
                }
                return value;
            }
        }
        return 0;
    }

    @Override
    public ItemStack add(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        int left = stack.getCount();
        int max = stack.getItem().getMaxStackSize();
        for (ItemStack s : slots) { // top up matching stacks first
            if (left > 0 && s != null && !s.isEmpty() && stacksWith(s, stack) && s.getCount() < max) {
                int moved = Math.min(left, max - s.getCount());
                s.incrementCount(moved);
                left -= moved;
            }
        }
        for (int i = 0; i < slots.length && left > 0; i++) {
            if (slots[i] == null || slots[i].isEmpty()) {
                int moved = Math.min(left, max);
                slots[i] = new ItemStack(stack.getItem(), moved, stack.getState());
                left -= moved;
            }
        }
        return left > 0 ? new ItemStack(stack.getItem(), left, stack.getState()) : null;
    }

    /** Takes everything out, leaving the slots empty. */
    public ItemStack[] takeAll() {
        int n = 0;
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) {
                n++;
            }
        }
        ItemStack[] out = new ItemStack[n];
        n = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                out[n++] = slots[i];
            }
            slots[i] = cleared();
        }
        return out;
    }

    private ItemStack cleared() {
        return backed ? ItemStackStateCodec.empty() : null;
    }

    private static boolean stacksWith(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && java.util.Objects.equals(a.getState(), b.getState());
    }
}
