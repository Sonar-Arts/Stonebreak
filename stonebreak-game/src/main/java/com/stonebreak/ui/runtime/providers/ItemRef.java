package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.drops.BlockDropTables;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

/**
 * What an item-icon element shows: the item, its SBO state, the stack count and an optional
 * durability fraction. Sources, first match wins per field:
 * <ol>
 *   <li>the typed props {@code item}, {@code count}, {@code state}, {@code durability};</li>
 *   <li>a whole slot record in prop {@code stack}: how a binding hands over a host slot
 *       ({@code contracts.SlotRecords}: {@code {slot, item, objectId, name, count, state, maxStack}},
 *       numeric {@code item} used when {@code objectId} is blank), plus an optional {@code durability};</li>
 *   <li>the provider {@code params} object: the same field names, or {@code params.stack}.</li>
 * </ol>
 * The item may be an SBO objectId ({@code "stonebreak:dirt"}), a numeric block/item id (as a
 * number or a digit string; blocks first, as the legacy {@code renderItemIcon(int)} looked up),
 * or a block/item name.
 *
 * @param durability remaining durability in {@code [0, 1]}, or NaN for none
 */
public record ItemRef(Item item, String state, int count, double durability) {

    /** What {@code element} shows, or null when it names no known item (empty slot). */
    public static ItemRef of(UiElement element) {
        UiValue.Obj p = element.prop("params") instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY;
        UiValue.Obj record = prop(element, "stack") instanceof UiValue.Obj o ? o
            : p.get("stack") instanceof UiValue.Obj o2 ? o2 : null;
        // first that resolves: a host slot record carries objectId ("" when empty) and numeric item
        Item resolved = firstItem(prop(element, "item"), p.get("item"), field(record, "objectId"),
            field(record, "item"), field(record, "id"), p.get("objectId"));
        if (resolved == null || resolved == BlockType.AIR) {
            return null;
        }
        int count = (int) number(first(prop(element, "count"), field(record, "count"), p.get("count")), 1);
        if (count <= 0) {
            return null; // an emptied stack whose id lingers
        }
        String state = text(first(prop(element, "state"), field(record, "state"), p.get("state")));
        double durability = number(first(prop(element, "durability"), field(record, "durability"),
            p.get("durability")), Double.NaN);
        return new ItemRef(resolved, state, count, durability);
    }

    private static Item firstItem(UiValue... candidates) {
        for (UiValue v : candidates) {
            Item item = v == null ? null : resolve(v);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    /** The block or item {@code value} names, or null. */
    public static Item resolve(UiValue value) {
        return switch (value) {
            case UiValue.Num n when n.isIntegral() -> byId((int) n.value());
            case UiValue.Str s -> resolve(s.value());
            case null, default -> null;
        };
    }

    /** objectId, numeric id or block/item name; null when unknown. */
    public static Item resolve(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        if (t.chars().allMatch(Character::isDigit) && t.length() <= 9) {
            return byId(Integer.parseInt(t));
        }
        Item byObjectId = BlockDropTables.resolveItem(t);
        if (byObjectId != null) {
            return byObjectId;
        }
        BlockType block = BlockType.getByName(t); // display name
        if (block != null) {
            return block;
        }
        try {
            return BlockType.valueOf(t.toUpperCase(java.util.Locale.ROOT)); // constant name
        } catch (IllegalArgumentException notABlock) {
            return ItemType.getByName(t);
        }
    }

    private static Item byId(int id) {
        BlockType block = BlockType.getById(id);
        return block != null ? block : ItemType.getById(id);
    }

    private static UiValue prop(UiElement element, String name) {
        UiValue v = element.prop(name);
        return v instanceof UiValue.Null ? null : v;
    }

    private static UiValue field(UiValue.Obj record, String name) {
        return record == null ? null : record.get(name);
    }

    private static UiValue first(UiValue... values) {
        for (UiValue v : values) {
            if (v != null && !(v instanceof UiValue.Null)) {
                return v;
            }
        }
        return null;
    }

    private static double number(UiValue v, double fallback) {
        return v instanceof UiValue.Num n ? n.value() : fallback;
    }

    private static String text(UiValue v) {
        return v instanceof UiValue.Str s && !s.value().isBlank() ? s.value() : null;
    }
}
