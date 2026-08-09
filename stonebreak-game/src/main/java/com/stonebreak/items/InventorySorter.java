package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure sort logic for a single inventory region (hotbar or main inventory).
 * Merges partial stacks of the same item type + state into as few full
 * stacks as possible, orders the result by category then name, and pads
 * the remainder with empty (AIR) stacks.
 */
public final class InventorySorter {

    private InventorySorter() {}

    static final Comparator<ItemStack> COMPARATOR =
        Comparator.comparing(ItemStack::getCategory)
                  .thenComparing(ItemStack::getName, String.CASE_INSENSITIVE_ORDER)
                  .thenComparing(s -> s.getState() == null ? "" : s.getState());

    /**
     * Sorts a region array in place: merges partial stacks of the same item
     * type + state into as few full stacks as possible (same grouping rule
     * as {@link ItemStack#canStackWith}), sorts the result by category then
     * name, and pads the remainder with fresh AIR stacks.
     */
    public static void sortRegion(ItemStack[] region) {
        List<ItemStack> groupReps = new ArrayList<>();
        List<Integer> groupTotals = new ArrayList<>();

        for (ItemStack stack : region) {
            if (stack == null || stack.isEmpty()) continue;

            int groupIndex = -1;
            for (int g = 0; g < groupReps.size(); g++) {
                ItemStack rep = groupReps.get(g);
                if (rep.getItem().isSameType(stack.getItem())
                        && Objects.equals(rep.getState(), stack.getState())) {
                    groupIndex = g;
                    break;
                }
            }
            if (groupIndex == -1) {
                groupReps.add(stack);
                groupTotals.add(stack.getCount());
            } else {
                groupTotals.set(groupIndex, groupTotals.get(groupIndex) + stack.getCount());
            }
        }

        List<ItemStack> merged = new ArrayList<>();
        for (int g = 0; g < groupReps.size(); g++) {
            ItemStack rep = groupReps.get(g);
            int remaining = groupTotals.get(g);
            int maxStack = rep.getMaxStackSize();
            while (remaining > 0) {
                int stackCount = Math.min(remaining, maxStack);
                merged.add(new ItemStack(rep.getItem(), stackCount, rep.getState()));
                remaining -= stackCount;
            }
        }

        merged.sort(COMPARATOR);

        int i = 0;
        for (; i < merged.size(); i++) {
            region[i] = merged.get(i);
        }
        for (; i < region.length; i++) {
            region[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
    }
}
