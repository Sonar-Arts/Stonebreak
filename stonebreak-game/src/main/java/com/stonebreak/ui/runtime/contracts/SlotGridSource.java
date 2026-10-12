package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCollection;
import com.stonebreak.items.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Publishes a grid of item slots ({@code main:0..26}, {@code hotbar:0..8}) into a
 * {@link DataCollection}. Item stacks are mutable and changed in place all over the game
 * (stacking, splitting, crafting, network sync), so there is no change event to hook: the UI
 * thread compares a primitive snapshot once per frame and republishes only the rows that
 * changed (the collection's identity diff turns them into single-row updates). An unchanged
 * frame allocates nothing.
 */
public final class SlotGridSource {

    private final String prefix;
    private final DataCollection collection;
    private int[] ids = new int[0];
    private int[] counts = new int[0];
    private String[] states = new String[0];
    private int selected = Integer.MIN_VALUE;
    private boolean published;

    /** @param prefix slot address prefix ({@code main}, {@code hotbar}) */
    public SlotGridSource(String prefix) {
        this.prefix = prefix;
        this.collection = new DataCollection(SlotRecords.GRID);
    }

    public DataCollection collection() {
        return collection;
    }

    /**
     * UI thread: republishes when any slot or the selection changed since the last call.
     *
     * @param n        number of slots
     * @param stacks   slot index → its stack, read in place (null is empty)
     * @param selected index shown as {@code selected} ({@code -1}: none)
     * @return whether anything was published
     */
    public boolean refresh(int n, IntFunction<ItemStack> stacks, int selected) {
        boolean changed = !published || n != ids.length || selected != this.selected;
        if (!changed) {
            for (int i = 0; i < n; i++) {
                ItemStack s = stacks.apply(i);
                boolean empty = s == null || s.isEmpty();
                if (ids[i] != (empty ? 0 : s.getBlockTypeId()) || counts[i] != (empty ? 0 : s.getCount())
                    || !Objects.equals(states[i], empty ? null : s.getState())) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return false;
        }
        if (n != ids.length) {
            ids = new int[n];
            counts = new int[n];
            states = new String[n];
        }
        List<UiValue> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ItemStack s = stacks.apply(i);
            boolean empty = s == null || s.isEmpty();
            ids[i] = empty ? 0 : s.getBlockTypeId();
            counts[i] = empty ? 0 : s.getCount();
            states[i] = empty ? null : s.getState();
            rows.add(SlotRecords.slot(prefix + ":" + i, s, i == selected));
        }
        this.selected = selected;
        published = true;
        collection.setAll(rows);
        return true;
    }

    /** Empties the grid (world left). */
    public void clear() {
        ids = new int[0];
        counts = new int[0];
        states = new String[0];
        selected = Integer.MIN_VALUE;
        published = false;
        collection.setAll(List.of());
    }
}
