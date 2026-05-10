package com.stonebreak.blocks;

import com.openmason.engine.voxel.IBlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemCategory;
import com.stonebreak.config.Settings;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines all block types in the game.
 *
 * <p><strong>This was originally an enum.</strong> It is now a final class so
 * that new blocks can be added at runtime from SBO files via
 * {@link #register(String, int, String, boolean, boolean, int, int, float)}
 * without requiring an enum constant. The 35 hardcoded constants below remain
 * as {@code public static final} fields so existing call sites
 * ({@code BlockType.STONE} etc.) keep working unchanged.
 *
 * <p>Behavioural API mirrors {@link Enum}: {@link #values()}, {@link #valueOf(String)},
 * {@link #name()} all behave as they did before. Reference equality is the
 * canonical comparison since all instances are unique.
 *
 * <p>{@code AIR} and {@code WATER} are sentinels with engine-side semantics
 * (collision skip, water physics) — they intentionally do not have backing
 * SBOs and are guaranteed to exist regardless of what's on disk.
 */
public final class BlockType implements Item, IBlockType {

    // ----- Registry storage (populated by static initializer + register()) -----

    private static final Map<String, BlockType> BY_NAME = new LinkedHashMap<>();
    private static final Map<Integer, BlockType> BY_ID = new LinkedHashMap<>();

    // ----- Hardcoded constants (canonical 35 blocks). These are the SAME
    //       blocks the legacy enum had; their IDs are the values persisted
    //       in chunk save files and MUST stay stable.

    public static final BlockType AIR = create("AIR", 0, "Air", false, false, -1, -1, 0.0f);
    public static final BlockType GRASS = create("GRASS", 1, "Grass", true, true, 0, 0, 1.0f);
    public static final BlockType DIRT = create("DIRT", 2, "Dirt", true, true, 2, 0, 2.0f);
    public static final BlockType STONE = create("STONE", 3, "Stone", true, true, 3, 0, 4.0f);
    public static final BlockType BEDROCK = create("BEDROCK", 4, "Bedrock", true, false, 4, 0, Float.POSITIVE_INFINITY);
    public static final BlockType WOOD = create("WOOD", 5, "Wood", true, true, 5, 0, 3.0f);
    public static final BlockType LEAVES = create("LEAVES", 6, "Leaves", true, true, 7, 0, 0.5f);
    public static final BlockType SAND = create("SAND", 7, "Sand", true, true, 8, 0, 1.5f);
    public static final BlockType WATER = create("WATER", 8, "Water", false, false, 9, 0, 0.0f);
    public static final BlockType COAL_ORE = create("COAL_ORE", 9, "Coal Ore", true, true, 0, 1, 6.0f);
    public static final BlockType IRON_ORE = create("IRON_ORE", 10, "Iron Ore", true, true, 1, 1, 8.0f);
    public static final BlockType RED_SAND = create("RED_SAND", 11, "Red Sand", true, true, 2, 1, 1.5f);
    public static final BlockType MAGMA = create("MAGMA", 12, "Magma", true, true, 3, 1, 10.0f);
    public static final BlockType CRYSTAL = create("CRYSTAL", 13, "Crystal", true, true, 4, 1, 12.0f);
    public static final BlockType SANDSTONE = create("SANDSTONE", 14, "Sandstone", true, true, 5, 1, 5.0f);
    public static final BlockType RED_SANDSTONE = create("RED_SANDSTONE", 15, "Red Sandstone", true, true, 6, 1, 5.0f);
    public static final BlockType ROSE = create("ROSE", 16, "Rose", false, true, 10, 1, 0.1f);
    public static final BlockType DANDELION = create("DANDELION", 17, "Dandelion", false, true, 11, 1, 0.1f);
    public static final BlockType SNOWY_DIRT = create("SNOWY_DIRT", 18, "Snowy Dirt", true, true, 0, 2, 1.0f);
    public static final BlockType PINE_LEAVES = create("PINE_LEAVES", 19, "Pine Leaves", true, true, 3, 4, 0.5f);
    public static final BlockType PINE = create("PINE", 20, "Pine", true, true, 2, 2, 3.0f);
    public static final BlockType ICE = create("ICE", 21, "Ice", true, true, 3, 2, 2.0f);
    public static final BlockType SNOW = create("SNOW", 22, "Snow", false, true, 5, 2, 0.1f);
    public static final BlockType WORKBENCH = create("WORKBENCH", 23, "Workbench", true, true, 6, 2, 3.0f);
    public static final BlockType WOOD_PLANKS = create("WOOD_PLANKS", 24, "Wood Planks", true, true, 0, 3, 3.0f);
    public static final BlockType PINE_WOOD_PLANKS = create("PINE_WOOD_PLANKS", 25, "Pine Wood Planks", true, true, 2, 3, 3.0f);
    public static final BlockType ELM_WOOD_LOG = create("ELM_WOOD_LOG", 26, "Elm Wood Log", true, true, 4, 3, 3.0f);
    public static final BlockType ELM_WOOD_PLANKS = create("ELM_WOOD_PLANKS", 27, "Elm Wood Planks", true, true, 5, 3, 3.0f);
    public static final BlockType ELM_LEAVES = create("ELM_LEAVES", 28, "Elm Leaves", true, true, 6, 3, 0.5f);
    public static final BlockType COBBLESTONE = create("COBBLESTONE", 29, "Cobblestone", true, true, 7, 3, 4.0f);
    public static final BlockType GRAVEL = create("GRAVEL", 30, "Gravel", true, true, 8, 3, 1.5f);
    public static final BlockType CLAY = create("CLAY", 31, "Clay", true, true, 0, 4, 2.0f);
    public static final BlockType RED_SAND_COBBLESTONE = create("RED_SAND_COBBLESTONE", 32, "Red Sand Cobblestone", true, true, 1, 4, 4.0f);
    public static final BlockType SAND_COBBLESTONE = create("SAND_COBBLESTONE", 33, "Sand Cobblestone", true, true, 2, 4, 4.0f);
    public static final BlockType WILDGRASS = create("WILDGRASS", 34, "Wildgrass", false, true, 6, 12, 0.1f);

    /**
     * Face identifier for per-face texture lookups. Stays an enum because it
     * is small, closed, and used in switch expressions throughout the
     * rendering pipeline.
     */
    public enum Face {
        TOP(0), BOTTOM(1), SIDE_NORTH(2), SIDE_SOUTH(3), SIDE_EAST(4), SIDE_WEST(5);
        private final int index;
        Face(int index) { this.index = index; }
        public int getIndex() { return index; }
    }

    // ----- Instance fields (immutable) -----

    private final String enumName;
    private final int id;
    private final String name;
    private final boolean solid;
    private final boolean breakable;
    private final int atlasX;
    private final int atlasY;
    private final float hardness;

    private BlockType(String enumName, int id, String name, boolean solid, boolean breakable,
                      int atlasX, int atlasY, float hardness) {
        this.enumName = enumName;
        this.id = id;
        this.name = name;
        this.solid = solid;
        this.breakable = breakable;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
        this.hardness = hardness;
    }

    /** Internal factory used for the hardcoded static-final constants. */
    private static BlockType create(String enumName, int id, String name, boolean solid, boolean breakable,
                                    int atlasX, int atlasY, float hardness) {
        BlockType bt = new BlockType(enumName, id, name, solid, breakable, atlasX, atlasY, hardness);
        registerInternal(bt);
        return bt;
    }

    /**
     * Register a new BlockType discovered at runtime (from SBO loading).
     * Returns the existing instance if a block with this {@code enumName} or
     * {@code id} is already registered — duplicate registration is a no-op,
     * not an error, so reloading the registry is idempotent.
     *
     * <p>Called by {@code BlockRegistry.loadFrom()} for SBO blocks that don't
     * have a hardcoded static-final constant.
     *
     * @param enumName name analogue (uppercase, underscores; e.g. "MARBLE")
     * @return the registered BlockType (new or existing)
     */
    public static synchronized BlockType register(String enumName, int id, String name,
                                                  boolean solid, boolean breakable,
                                                  int atlasX, int atlasY, float hardness) {
        BlockType existing = BY_NAME.get(enumName);
        if (existing != null) return existing;
        BlockType byIdExisting = BY_ID.get(id);
        if (byIdExisting != null) return byIdExisting;

        BlockType bt = new BlockType(enumName, id, name, solid, breakable, atlasX, atlasY, hardness);
        registerInternal(bt);
        return bt;
    }

    private static void registerInternal(BlockType bt) {
        BY_NAME.put(bt.enumName, bt);
        BY_ID.put(bt.id, bt);
    }

    // ----- Enum-compat static API -----

    /** Returns all known block types in registration order. Mirrors {@code Enum.values()}. */
    public static BlockType[] values() {
        return BY_NAME.values().toArray(new BlockType[0]);
    }

    /** Returns all known block types as an unmodifiable collection (no array copy). */
    public static Collection<BlockType> all() {
        return Collections.unmodifiableCollection(BY_NAME.values());
    }

    /**
     * Mirrors {@code Enum.valueOf(String)}. Throws {@link IllegalArgumentException}
     * with the same shape if no block with that name exists.
     */
    public static BlockType valueOf(String name) {
        BlockType bt = BY_NAME.get(name);
        if (bt == null) {
            throw new IllegalArgumentException("No BlockType named " + name);
        }
        return bt;
    }

    /** Look up a block by its stable numeric ID; null if unknown. */
    public static BlockType getById(int id) {
        return BY_ID.get(id);
    }

    /** Look up a block by its display name (case-insensitive); null if unknown. */
    public static BlockType getByName(String name) {
        if (name == null) return null;
        for (BlockType type : BY_NAME.values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    // ----- Instance accessors -----

    /** Mirrors {@code Enum.name()} — the SCREAMING_CASE constant name. */
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

    public boolean isSolid() {
        return solid;
    }

    public boolean isBreakable() {
        return breakable;
    }

    @Override
    public boolean isAir() {
        return this == AIR;
    }

    /**
     * Determines if the block is transparent (allows rendering of faces behind it).
     */
    public boolean isTransparent() {
        if (this == LEAVES || this == PINE_LEAVES || this == ELM_LEAVES) {
            try {
                return Settings.getInstance().getLeafTransparency();
            } catch (Exception e) {
                return true;
            }
        }
        return this == AIR || this == WATER || this == ROSE || this == DANDELION
                || this == WILDGRASS || this == ICE || this == SNOW;
    }

    @Override
    public int getAtlasX() {
        return atlasX;
    }

    @Override
    public int getAtlasY() {
        return atlasY;
    }

    public float getHardness() {
        return hardness;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public ItemCategory getCategory() {
        return ItemCategory.BLOCKS;
    }

    /**
     * Visual height of the block (0.0 to 1.0). Snow uses layer count.
     */
    public float getVisualHeight(int layerCount) {
        if (this == SNOW) {
            return Math.min(1.0f, Math.max(0.125f, layerCount * 0.125f));
        }
        return 1.0f;
    }

    public float getVisualHeight() {
        return getVisualHeight(1);
    }

    /**
     * Collision height of the block (0.0 to 1.0). Snow uses layer count;
     * non-solid blocks have zero collision.
     */
    public float getCollisionHeight(int layerCount) {
        if (this == SNOW) {
            return Math.min(1.0f, Math.max(0.125f, layerCount * 0.125f));
        }
        return solid ? 1.0f : 0.0f;
    }

    public float getCollisionHeight() {
        return getCollisionHeight(1);
    }

    public boolean isStackable() {
        return this == SNOW;
    }

    public boolean isPlaceable() {
        return true;
    }

    public boolean isFlower() {
        return this == ROSE || this == DANDELION || this == WILDGRASS;
    }

    /**
     * Texture atlas coordinates for the given face. Hardcoded for the legacy
     * 35 blocks; returns the default atlas coords for SBO-only blocks (the
     * SBO/CBR system handles per-face texturing for those).
     */
    public float[] getTextureCoords(Face face) {
        // Most blocks use the same coords for all faces — handle the
        // multi-face exceptions first, then fall through to single-coord blocks.
        if (this == GRASS) {
            if (face == Face.TOP) return new float[]{0, 0};
            if (face == Face.BOTTOM) return new float[]{2, 0};
            return new float[]{1, 0};
        }
        if (this == WOOD) {
            if (face == Face.TOP || face == Face.BOTTOM) return new float[]{6, 0};
            return new float[]{5, 0};
        }
        if (this == SANDSTONE) {
            if (face == Face.TOP || face == Face.BOTTOM) return new float[]{5, 1};
            return new float[]{7, 1};
        }
        if (this == RED_SANDSTONE) {
            if (face == Face.TOP || face == Face.BOTTOM) return new float[]{6, 1};
            return new float[]{8, 1};
        }
        if (this == SNOWY_DIRT) {
            if (face == Face.TOP) return new float[]{0, 2};
            if (face == Face.BOTTOM) return new float[]{2, 0};
            return new float[]{1, 2};
        }
        if (this == PINE) {
            if (face == Face.BOTTOM) return new float[]{2, 0};
            return new float[]{2, 2};
        }
        if (this == WORKBENCH) {
            if (face == Face.TOP) return new float[]{6, 2};
            if (face == Face.BOTTOM) return new float[]{2, 0};
            return new float[]{7, 2};
        }
        if (this == ELM_WOOD_LOG) {
            if (face == Face.TOP || face == Face.BOTTOM) return new float[]{4, 3};
            return new float[]{7, 3};
        }
        // Single-coord blocks: just return the atlas position. Falls through
        // to the default for SBO-only blocks too.
        return new float[]{atlasX, atlasY};
    }

    @Override
    public String toString() {
        return enumName;
    }
}
