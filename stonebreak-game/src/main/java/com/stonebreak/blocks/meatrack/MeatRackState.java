package com.stonebreak.blocks.meatrack;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.container.SlotGridState;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.items.ItemType;

/**
 * A meat rack's hooks: {@link #HOOKS} of them along the crossbar, each holding a stack of one
 * food. Only food hangs here. What is on each hook is drawn hanging from it
 * ({@code rendering.models.entities.MeatRackItemRenderer}), so the rack shows what it stores.
 *
 * <p>State string: {@code meat_rack:grid=<token>|<token>|<token>}, hook 0 at the rack's west end.
 * Like a chest's, it names no mesh variant, so hanging food never remeshes the chunk.
 */
public final class MeatRackState extends SlotGridState {

    public static final String STATE_PREFIX = "meat_rack:";
    public static final int HOOKS = 3;

    public MeatRackState(BlockPos pos) {
        super(STATE_PREFIX, HOOKS, pos);
    }

    public static boolean isMeatRackState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /** Whether {@code item} can hang on a rack: food only. */
    public static boolean accepts(Item item) {
        return item instanceof ItemType type && type.isFood();
    }

    /**
     * Hangs as much of {@code food} as there is room for — topping up a hook of the same food
     * first, then a bare hook. {@code food} keeps the rest.
     *
     * @return how many were hung
     */
    public int hang(ItemStack food) {
        if (food == null || food.isEmpty() || !accepts(food.getItem())) {
            return 0;
        }
        int before = food.getCount();
        fill(getSlots(), food);
        return before - food.getCount();
    }

    /** How many of {@code food} {@link #hang} would take right now, changing nothing. */
    public int roomFor(ItemStack food) {
        if (food == null || food.isEmpty() || !accepts(food.getItem())) {
            return 0;
        }
        ItemStack[] hooks = getSlots();
        ItemStack[] probe = new ItemStack[hooks.length];
        for (int i = 0; i < hooks.length; i++) {
            probe[i] = hooks[i] == null ? null : hooks[i].copy();
        }
        ItemStack copy = food.copy();
        fill(probe, copy);
        return food.getCount() - copy.getCount();
    }

    /** Takes the whole stack off the last hook that has any, or {@code null} when the rack is bare. */
    public ItemStack takeLast() {
        ItemStack[] hooks = getSlots();
        for (int i = hooks.length - 1; i >= 0; i--) {
            ItemStack s = hooks[i];
            if (s != null && !s.isEmpty()) {
                hooks[i] = ItemStackStateCodec.empty();
                return s;
            }
        }
        return null;
    }

    /** Puts {@code stack} back on hook {@code hook} (what a taker had no room for). */
    public void restore(int hook, ItemStack stack) {
        getSlots()[hook] = stack == null || stack.isEmpty() ? ItemStackStateCodec.empty() : stack;
    }

    /** The hook {@link #takeLast} would take from, or -1 when the rack is bare. */
    public int lastFilledHook() {
        ItemStack[] hooks = getSlots();
        for (int i = hooks.length - 1; i >= 0; i--) {
            if (hooks[i] != null && !hooks[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}
