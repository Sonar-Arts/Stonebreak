package com.stonebreak.items;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines non-placeable items in the game (tools, materials, consumables, etc.).
 *
 * <p><strong>This was originally an enum.</strong> It is now a final class so
 * that new items can be added at runtime from SBO files via
 * {@link #register(String, int, String, int, int, ItemCategory, int)} without
 * requiring a Java constant. The 11 hardcoded constants remain as
 * {@code public static final} fields so existing call sites
 * ({@code ItemType.STICK} etc.) keep working unchanged.
 *
 * <p>API mirrors {@link Enum}: {@link #values()}, {@link #valueOf(String)},
 * {@link #name()} all behave as they did before.
 */
public final class ItemType implements Item {

    private static final Map<String, ItemType> BY_NAME = new LinkedHashMap<>();
    private static final Map<Integer, ItemType> BY_ID = new LinkedHashMap<>();

    // ----- Hardcoded constants. IDs are the values persisted in inventory
    //       save data and MUST stay stable. ----------------------------------

    // Materials
    public static final ItemType STICK = create("STICK", 1001, "Stick", 1, 3, ItemCategory.MATERIALS, 64);

    // Tools
    public static final ItemType WOODEN_PICKAXE = create("WOODEN_PICKAXE", 1002, "Wooden Pickaxe", 3, 3, ItemCategory.TOOLS, 1);
    public static final ItemType WOODEN_AXE = create("WOODEN_AXE", 1003, "Wooden Axe", 8, 3, ItemCategory.TOOLS, 1);
    public static final ItemType WOODEN_BUCKET = create("WOODEN_BUCKET", 1004, "Wooden Bucket", 0, 4, ItemCategory.TOOLS, 16);
    public static final ItemType WOODEN_BUCKET_WATER = create("WOODEN_BUCKET_WATER", 1005, "Wooden Water Bucket", 1, 4, ItemCategory.TOOLS, 1);

    // Items previously sourced from SBT files; now sourced from SBOs under sbo/items/
    // (the hardcoded entries here remain so existing inventory references keep working).
    public static final ItemType PATTY_SMACKER = create("PATTY_SMACKER", 1006, "Patty Smacker", 0, 0, ItemCategory.TOOLS, 1);
    public static final ItemType SNOWBALL = create("SNOWBALL", 1007, "Snowball", 0, 0, ItemCategory.MATERIALS, 16);
    public static final ItemType STONE_SHOVEL = create("STONE_SHOVEL", 1008, "Stone Shovel", 0, 0, ItemCategory.TOOLS, 1);
    public static final ItemType SWORD = create("SWORD", 1009, "Sword", 0, 0, ItemCategory.TOOLS, 1);
    public static final ItemType WAR_AXE = create("WAR_AXE", 1010, "War Axe", 0, 0, ItemCategory.TOOLS, 1);
    public static final ItemType WOODEN_SHOVEL = create("WOODEN_SHOVEL", 1011, "Wooden Shovel", 0, 0, ItemCategory.TOOLS, 1);

    // ----- Instance fields (immutable) -----

    private final String enumName;
    private final int id;
    private final String name;
    private final int atlasX;
    private final int atlasY;
    private final ItemCategory category;
    private final int maxStackSize;

    private ItemType(String enumName, int id, String name, int atlasX, int atlasY,
                     ItemCategory category, int maxStackSize) {
        this.enumName = enumName;
        this.id = id;
        this.name = name;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
        this.category = category;
        this.maxStackSize = maxStackSize;
    }

    private static ItemType create(String enumName, int id, String name, int atlasX, int atlasY,
                                   ItemCategory category, int maxStackSize) {
        ItemType it = new ItemType(enumName, id, name, atlasX, atlasY, category, maxStackSize);
        registerInternal(it);
        return it;
    }

    /**
     * Register a new ItemType discovered at runtime (from SBO loading).
     * Returns the existing instance if one with this name or ID is already
     * registered — duplicate registration is a no-op, so reloading the
     * registry is idempotent.
     */
    public static synchronized ItemType register(String enumName, int id, String name,
                                                 int atlasX, int atlasY,
                                                 ItemCategory category, int maxStackSize) {
        ItemType existing = BY_NAME.get(enumName);
        if (existing != null) return existing;
        ItemType byIdExisting = BY_ID.get(id);
        if (byIdExisting != null) return byIdExisting;

        ItemType it = new ItemType(enumName, id, name, atlasX, atlasY, category, maxStackSize);
        registerInternal(it);
        return it;
    }

    private static void registerInternal(ItemType it) {
        BY_NAME.put(it.enumName, it);
        BY_ID.put(it.id, it);
    }

    // ----- Enum-compat static API -----

    public static ItemType[] values() {
        return BY_NAME.values().toArray(new ItemType[0]);
    }

    public static Collection<ItemType> all() {
        return Collections.unmodifiableCollection(BY_NAME.values());
    }

    public static ItemType valueOf(String name) {
        ItemType it = BY_NAME.get(name);
        if (it == null) {
            throw new IllegalArgumentException("No ItemType named " + name);
        }
        return it;
    }

    public static ItemType getById(int id) {
        return BY_ID.get(id);
    }

    public static ItemType getByName(String name) {
        if (name == null) return null;
        for (ItemType type : BY_NAME.values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    // ----- Instance accessors -----

    public String name() {
        return enumName;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getAtlasX() {
        return atlasX;
    }

    @Override
    public int getAtlasY() {
        return atlasY;
    }

    @Override
    public int getMaxStackSize() {
        return maxStackSize;
    }

    @Override
    public ItemCategory getCategory() {
        return category;
    }

    public boolean isTool() {
        return category == ItemCategory.TOOLS;
    }

    public boolean isMaterial() {
        return category == ItemCategory.MATERIALS;
    }

    @Override
    public String toString() {
        return enumName;
    }
}
