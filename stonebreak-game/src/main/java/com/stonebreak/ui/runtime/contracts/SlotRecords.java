package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.registry.ItemRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one item-slot record every container contract publishes (#289): furnace slots, the
 * inventory and hotbar grids and the stack on the cursor. Item icons (#298/#300) read
 * {@code item}/{@code objectId}, tooltips read {@code name}, counts read {@code count}.
 *
 * <pre>{@code
 * { slot: "main:4", item: 12, objectId: "stonebreak:dirt", name: "Dirt", count: 5, state: "", maxStack: 64 }
 * }</pre>
 * An empty slot has {@code item: 0}, {@code count: 0} and empty strings.
 */
public final class SlotRecords {

    /** Fields of one stack, without the slot address (used for {@code carried} and furnace slots). */
    public static final DataType.Obj STACK = DataType.object("item", DataType.integer(), "objectId", DataType.string(),
        "name", DataType.string(), "count", DataType.integer(), "state", DataType.string(),
        "maxStack", DataType.integer());

    /** A grid slot: {@link #STACK} plus its address, the identity of collection rows. */
    public static final DataType.Obj SLOT = DataType.object("slot", DataType.string(), "item", DataType.integer(),
        "objectId", DataType.string(), "name", DataType.string(), "count", DataType.integer(),
        "state", DataType.string(), "maxStack", DataType.integer(), "selected", DataType.bool());

    public static final DataType.ListOf GRID = DataType.list(SLOT, "slot");

    private static final Map<Integer, String> OBJECT_IDS = new ConcurrentHashMap<>();

    private SlotRecords() {
    }

    /** {@code stack} as a {@link #STACK} record. */
    public static UiValue.Obj stack(ItemStack stack) {
        return new UiValue.Obj(stackFields(stack, null, false));
    }

    /** {@code stack} in {@code slot} as a {@link #SLOT} record. */
    public static UiValue.Obj slot(String slot, ItemStack stack, boolean selected) {
        return new UiValue.Obj(stackFields(stack, slot, selected));
    }

    public static UiValue.Obj emptyStack() {
        return stack(null);
    }

    private static Map<String, UiValue> stackFields(ItemStack stack, String slot, boolean selected) {
        boolean empty = stack == null || stack.isEmpty();
        int id = empty ? 0 : stack.getBlockTypeId();
        java.util.LinkedHashMap<String, UiValue> m = new java.util.LinkedHashMap<>();
        if (slot != null) {
            m.put("slot", UiValue.of(slot));
        }
        m.put("item", UiValue.of(id));
        m.put("objectId", UiValue.of(empty ? "" : objectId(id)));
        m.put("name", UiValue.of(empty ? "" : stack.getName()));
        m.put("count", UiValue.of(empty ? 0 : stack.getCount()));
        m.put("state", UiValue.of(empty || stack.getState() == null ? "" : stack.getState()));
        m.put("maxStack", UiValue.of(empty ? 0 : stack.getMaxStackSize()));
        if (slot != null) {
            m.put("selected", UiValue.of(selected));
        }
        return m;
    }

    /** SBO object id of a numeric item/block id ({@code stonebreak:dirt}), or "" when it has none. */
    public static String objectId(int numericId) {
        if (numericId <= 0) {
            return "";
        }
        return OBJECT_IDS.computeIfAbsent(numericId, SlotRecords::lookupObjectId);
    }

    private static String lookupObjectId(int id) {
        try {
            var block = BlockRegistry.getInstance().getById(id);
            if (block.isPresent()) {
                return block.get().objectId();
            }
            return ItemRegistry.getInstance().getById(id).map(ItemRegistry.ItemEntry::objectId).orElse("");
        } catch (RuntimeException | ExceptionInInitializerError e) {
            return ""; // registries unavailable (headless tests without SBO resources)
        }
    }
}
