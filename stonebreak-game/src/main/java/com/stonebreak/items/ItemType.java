package com.stonebreak.items;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.items.registry.ItemRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines non-placeable items in the game (tools, materials, consumables, etc.).
 *
 * <p><strong>Registry-backed.</strong> SBO-backed items pull their data from
 * {@link ItemRegistry} at class-init time. The SBO files under
 * {@code sbo/items/} are the source of truth for those.
 *
 * <p>The 3 remaining PNG-backed items (STICK, WOODEN_PICKAXE, WOODEN_AXE)
 * are hardcoded — they don't have SBOs yet, just flat PNG sprites under
 * {@code Items/Textures/}. Migrate them to SBO and they'll move to the
 * registry-backed list.
 *
 * <p>SBO files dropped into {@code sbo/items/} are auto-registered via the
 * promotion block at the end of class init.
 */
public final class ItemType implements Item {

    private static final Map<String, ItemType> BY_NAME = new LinkedHashMap<>();
    private static final Map<Integer, ItemType> BY_ID = new LinkedHashMap<>();
    /**
     * Stable lookup from SBO {@code objectId} (e.g. {@code "stonebreak:stick"})
     * to the corresponding {@link ItemType}. Used by callers that hold an
     * objectId rather than a numericId — e.g. recipe loading.
     */
    private static final Map<String, ItemType> BY_OBJECT_ID = new LinkedHashMap<>();
    /**
     * Maps an ItemType's enum name to the SBO objectId that backs it, when
     * the enum name doesn't match the default {@code "stonebreak:" + name}
     * convention.
     */
    private static final Map<String, String> OBJECT_ID_BY_ENUM_NAME = new LinkedHashMap<>();

    static {
        ItemRegistry.getInstance().scanAndLoad();
    }

    // ----- Hardcoded items (PNG-backed, no SBO yet). --------------------

    public static final ItemType STICK = createSentinel("STICK", 1001, "Stick", 1, 3, ItemCategory.MATERIALS, 64);
    public static final ItemType WOODEN_PICKAXE = createSentinel("WOODEN_PICKAXE", 1002, "Wooden Pickaxe", 3, 3, ItemCategory.TOOLS, 1);
    public static final ItemType WOODEN_AXE = createSentinel("WOODEN_AXE", 1003, "Wooden Axe", 8, 3, ItemCategory.TOOLS, 1);

    // PNG-backed sentinels still get an objectId mapping so recipe ingredients
    // and other objectId-keyed callers resolve consistently. The SBO files
    // exist under sbo/items/ but ItemType doesn't promote them since the enum
    // names already match.
    static {
        BY_OBJECT_ID.put("stonebreak:stick", STICK);
        BY_OBJECT_ID.put("stonebreak:wooden_pickaxe", WOODEN_PICKAXE);
        BY_OBJECT_ID.put("stonebreak:wooden_axe", WOODEN_AXE);
    }

    // ----- SBO-backed items. Data comes from sbo/items/ gameProperties. ---

    public static final ItemType PATTY_SMACKER = fromRegistry("stonebreak:patty_smacker", "PATTY_SMACKER");
    public static final ItemType SNOWBALL = fromRegistry("stonebreak:snowball", "SNOWBALL");
    public static final ItemType STONE_SHOVEL = fromRegistry("stonebreak:stone_shovel", "STONE_SHOVEL");
    public static final ItemType SWORD = fromRegistry("stonebreak:sword", "SWORD");
    public static final ItemType WAR_AXE = fromRegistry("stonebreak:war_axe", "WAR_AXE");
    public static final ItemType WOODEN_SHOVEL = fromRegistry("stonebreak:wooden_shovel", "WOODEN_SHOVEL");

    /**
     * Wooden bucket (SBO 1.3, two states: "sb_wooden_bucket_empty" default
     * and "sb_wooden_bucket_water"). Replaces the legacy WOODEN_BUCKET +
     * WOODEN_BUCKET_WATER pair — water-vs-empty is now an ItemStack state
     * rather than a separate item type.
     */
    public static final ItemType BANANA = fromRegistry("stonebreak:banana", "BANANA");
    public static final ItemType WOODEN_BUCKET = fromRegistry("stonebreak:wooden_bucket", "WOODEN_BUCKET");

    // ----- SBO state name constants for the wooden bucket. ----------------
    
    public static final String BUCKET_STATE_EMPTY = "sb_wooden_bucket_empty";
    public static final String BUCKET_STATE_WATER = "sb_wooden_bucket_water";

    // ----- Promote SBO entries that don't match a static-final field above.

    static {
        for (ItemRegistry.ItemEntry entry : ItemRegistry.getInstance().all()) {
            String enumName = ItemRegistry.sboNameToEnumName(entry.objectId());
            if (BY_NAME.containsKey(enumName) || BY_ID.containsKey(entry.numericId())) {
                continue;
            }
            SBOFormat.GameProperties gp = entry.properties();
            ItemCategory category = ItemRegistry.parseCategoryOrDefault(gp.categoryOrDefault());
            ItemType it = new ItemType(
                    enumName, gp.numericId(), entry.displayName(),
                    gp.atlasX(), gp.atlasY(), category, gp.maxStackSize());
            registerInternal(it);
            OBJECT_ID_BY_ENUM_NAME.put(enumName, entry.objectId());
            BY_OBJECT_ID.put(entry.objectId(), it);
        }
    }

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

    private static ItemType createSentinel(String enumName, int id, String name, int atlasX, int atlasY,
                                           ItemCategory category, int maxStackSize) {
        ItemType it = new ItemType(enumName, id, name, atlasX, atlasY, category, maxStackSize);
        registerInternal(it);
        return it;
    }

    private static ItemType fromRegistry(String objectId, String enumName) {
        ItemRegistry.ItemEntry entry = ItemRegistry.getInstance().get(objectId)
                .orElseThrow(() -> new IllegalStateException(
                        "ItemType." + enumName + " requires SBO '" + objectId
                                + "' but none is registered. Check sbo/items/."));
        SBOFormat.GameProperties gp = entry.properties();
        ItemCategory category = ItemRegistry.parseCategoryOrDefault(gp.categoryOrDefault());
        ItemType it = new ItemType(
                enumName, gp.numericId(), entry.displayName(),
                gp.atlasX(), gp.atlasY(), category, gp.maxStackSize());
        registerInternal(it);
        // Remember the explicit objectId so it can be resolved later even
        // when it doesn't match the default "stonebreak:<enum>" convention.
        OBJECT_ID_BY_ENUM_NAME.put(enumName, objectId);
        BY_OBJECT_ID.put(objectId, it);
        return it;
    }

    /**
     * Returns the SBO objectId backing this ItemType, or {@code null} if
     * it isn't SBO-backed. Used by {@code SpriteVoxelizer.sboItemId} to
     * resolve item textures via {@link ItemRegistry}.
     */
    public static String objectIdFor(ItemType type) {
        return type == null ? null : OBJECT_ID_BY_ENUM_NAME.get(type.enumName);
    }

    /**
     * External register hook for runtime additions (e.g. mods). Idempotent.
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

    /**
     * Look up an ItemType by its SBO {@code objectId}
     * (e.g. {@code "stonebreak:stick"}). Returns {@code null} if no item is
     * registered under that objectId.
     */
    public static ItemType getByObjectId(String objectId) {
        return objectId == null ? null : BY_OBJECT_ID.get(objectId);
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


    public float getHealAmount() {
        if (this == BANANA) return 4.0f;
        return 0.0f;
    }

    /**
     * Returns the base melee damage this item deals when used to attack.
     */
    public float getDamage() {
        if (this == SWORD) return 6.0f;
        if (this == WAR_AXE || this == WOODEN_AXE) return 4.0f;
        return 1.0f;
    }

    /**
     * Checks if this item is a tool.
     * @return True if this item is in the TOOLS category
     */
    public boolean isTool() {
        return category == ItemCategory.TOOLS;
    }

    public boolean isMaterial() {
        return category == ItemCategory.MATERIALS;
    }

    public boolean isFood() {
        return category == ItemCategory.FOOD;
    }

    @Override
    public String toString() {
        return enumName;
    }
}
