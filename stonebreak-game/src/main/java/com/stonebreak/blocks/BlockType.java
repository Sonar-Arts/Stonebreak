package com.stonebreak.blocks;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.voxel.IBlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
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
 * <p><strong>Registry-backed.</strong> The 33 SBO-backed blocks
 * (STONE, GRASS, ...) pull all their data — id, hardness, atlas coords,
 * solid/breakable flags — from {@link BlockRegistry} at class-init time.
 * The SBO files under {@code sbo/blocks/} are the single source of truth.
 *
 * <p>{@code AIR} and {@code WATER} are hardcoded sentinels: AIR has no SBO
 * (it's the empty-space null), and WATER has engine-side physics that
 * references the constant directly. Both must exist regardless of what's on
 * disk.
 *
 * <p>SBO files dropped into {@code sbo/blocks/} are auto-registered as
 * {@link BlockType} instances accessible via {@link #getById(int)},
 * {@link #getByName(String)}, {@link #valueOf(String)}, and {@link #values()}
 * — no enum / static-field declaration needed.
 *
 * <p>API mirrors {@link Enum}: {@link #values()}, {@link #valueOf(String)},
 * {@link #name()} all behave as the original enum did.
 */
public final class BlockType implements Item, IBlockType {

    // ----- Registry storage. Initialized FIRST so the static initializer
    //       blocks below can populate them. ---------------------------------

    private static final Map<String, BlockType> BY_NAME = new LinkedHashMap<>();
    private static final Map<Integer, BlockType> BY_ID = new LinkedHashMap<>();

    // Eagerly load the SBO registry before any static-final assignments
    // below. Idempotent — if BlockRegistry was already loaded by another
    // path, this is a no-op.
    static {
        BlockRegistry.getInstance().ensureLoaded();
    }

    // ----- Hardcoded sentinels (no SBO exists for these). -----------------

    public static final BlockType AIR = createSentinel("AIR", 0, "Air", false, false, -1, -1, 0.0f);
    public static final BlockType WATER = createSentinel("WATER", 8, "Water", false, false, 9, 0, 0.0f);

    // ----- SBO-backed blocks. Data comes from the gameProperties block of
    //       each SBO under sbo/blocks/. The objectId arg below is what's
    //       embedded in the SBO manifest; the second arg is the constant's
    //       SCREAMING_CASE name used by valueOf/name(). ---------------------

    public static final BlockType GRASS = fromRegistry("stonebreak:grass", "GRASS");
    public static final BlockType DIRT = fromRegistry("stonebreak:dirt", "DIRT");
    public static final BlockType STONE = fromRegistry("stonebreak:stone", "STONE");
    public static final BlockType BEDROCK = fromRegistry("stonebreak:bedrock", "BEDROCK");
    public static final BlockType WOOD = fromRegistry("stonebreak:wood", "WOOD");
    public static final BlockType LEAVES = fromRegistry("stonebreak:leaves", "LEAVES");
    public static final BlockType SAND = fromRegistry("stonebreak:sand", "SAND");
    public static final BlockType COAL_ORE = fromRegistry("stonebreak:coal_ore", "COAL_ORE");
    public static final BlockType IRON_ORE = fromRegistry("stonebreak:iron_ore", "IRON_ORE");
    public static final BlockType RED_SAND = fromRegistry("stonebreak:red_sand", "RED_SAND");
    public static final BlockType MAGMA = fromRegistry("stonebreak:magma", "MAGMA");
    public static final BlockType CRYSTAL = fromRegistry("stonebreak:crystal", "CRYSTAL");
    // SBO objectId historically uses "sand_stone" / "red_sand_stone" but the
    // game-side constant name is SANDSTONE / RED_SANDSTONE — explicit mapping.
    public static final BlockType SANDSTONE = fromRegistry("stonebreak:sand_stone", "SANDSTONE");
    public static final BlockType RED_SANDSTONE = fromRegistry("stonebreak:red_sand_stone", "RED_SANDSTONE");
    public static final BlockType ROSE = fromRegistry("stonebreak:rose", "ROSE");
    public static final BlockType DANDELION = fromRegistry("stonebreak:dandelion", "DANDELION");
    public static final BlockType SNOWY_DIRT = fromRegistry("stonebreak:snowy_dirt", "SNOWY_DIRT");
    public static final BlockType PINE_LEAVES = fromRegistry("stonebreak:pine_leaves", "PINE_LEAVES");
    // SBO objectId is "pine_wood_log" but the constant is PINE.
    public static final BlockType PINE = fromRegistry("stonebreak:pine_wood_log", "PINE");
    public static final BlockType ICE = fromRegistry("stonebreak:ice", "ICE");
    public static final BlockType SNOW = fromRegistry("stonebreak:snow", "SNOW");
    public static final BlockType WORKBENCH = fromRegistry("stonebreak:workbench", "WORKBENCH");
    public static final BlockType WOOD_PLANKS = fromRegistry("stonebreak:wood_planks", "WOOD_PLANKS");
    public static final BlockType PINE_WOOD_PLANKS = fromRegistry("stonebreak:pine_wood_planks", "PINE_WOOD_PLANKS");
    public static final BlockType ELM_WOOD_LOG = fromRegistry("stonebreak:elm_wood_log", "ELM_WOOD_LOG");
    public static final BlockType ELM_WOOD_PLANKS = fromRegistry("stonebreak:elm_wood_planks", "ELM_WOOD_PLANKS");
    public static final BlockType ELM_LEAVES = fromRegistry("stonebreak:elm_leaves", "ELM_LEAVES");
    public static final BlockType COBBLESTONE = fromRegistry("stonebreak:cobblestone", "COBBLESTONE");
    public static final BlockType GRAVEL = fromRegistry("stonebreak:gravel", "GRAVEL");
    public static final BlockType CLAY = fromRegistry("stonebreak:clay", "CLAY");
    public static final BlockType RED_SAND_COBBLESTONE = fromRegistry("stonebreak:red_sand_cobblestone", "RED_SAND_COBBLESTONE");
    public static final BlockType SAND_COBBLESTONE = fromRegistry("stonebreak:sand_cobblestone", "SAND_COBBLESTONE");
    public static final BlockType WILDGRASS = fromRegistry("stonebreak:wildgrass", "WILDGRASS");

    // ----- Promote any SBO entries that didn't match a static-final field
    //       above. New SBOs dropped into sbo/blocks/ become BlockType
    //       instances here, so getById/values()/getByName see them. --------

    static {
        for (BlockRegistry.BlockEntry entry : BlockRegistry.getInstance().all()) {
            String enumName = BlockRegistry.sboNameToEnumName(entry.objectId());
            if (BY_NAME.containsKey(enumName) || BY_ID.containsKey(entry.numericId())) {
                continue; // already represented by a sentinel or a static-final field
            }
            SBOFormat.GameProperties gp = entry.properties();
            registerInternal(new BlockType(
                    enumName, gp.numericId(), entry.displayName(),
                    gp.solid(), gp.breakable(), gp.atlasX(), gp.atlasY(), gp.hardness()));
        }
    }

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

    /**
     * Build a sentinel BlockType (no backing SBO). Used for AIR and WATER —
     * blocks whose data is engine-defined rather than asset-defined.
     */
    private static BlockType createSentinel(String enumName, int id, String name, boolean solid, boolean breakable,
                                            int atlasX, int atlasY, float hardness) {
        BlockType bt = new BlockType(enumName, id, name, solid, breakable, atlasX, atlasY, hardness);
        registerInternal(bt);
        return bt;
    }

    /**
     * Build a BlockType by reading data from a registered SBO. Throws if the
     * SBO is missing — startup fails loudly rather than silently using stale
     * defaults.
     */
    private static BlockType fromRegistry(String objectId, String enumName) {
        BlockRegistry.BlockEntry entry = BlockRegistry.getInstance().get(objectId)
                .orElseThrow(() -> new IllegalStateException(
                        "BlockType." + enumName + " requires SBO '" + objectId
                                + "' but none is registered. Check sbo/blocks/."));
        SBOFormat.GameProperties gp = entry.properties();
        BlockType bt = new BlockType(
                enumName, gp.numericId(), entry.displayName(),
                gp.solid(), gp.breakable(), gp.atlasX(), gp.atlasY(), gp.hardness());
        registerInternal(bt);
        return bt;
    }

    /**
     * Externally-callable register hook. Most SBO promotion happens in the
     * BlockType static initializer; this remains for future paths (e.g.
     * runtime mod loading) that need to add a block after class-init.
     * Idempotent — returns the existing instance if name or id collides.
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

    public static BlockType[] values() {
        return BY_NAME.values().toArray(new BlockType[0]);
    }

    public static Collection<BlockType> all() {
        return Collections.unmodifiableCollection(BY_NAME.values());
    }

    public static BlockType valueOf(String name) {
        BlockType bt = BY_NAME.get(name);
        if (bt == null) {
            throw new IllegalArgumentException("No BlockType named " + name);
        }
        return bt;
    }

    public static BlockType getById(int id) {
        return BY_ID.get(id);
    }

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

    public float getVisualHeight(int layerCount) {
        if (this == SNOW) {
            return Math.min(1.0f, Math.max(0.125f, layerCount * 0.125f));
        }
        return 1.0f;
    }

    public float getVisualHeight() {
        return getVisualHeight(1);
    }

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
        return new float[]{atlasX, atlasY};
    }

    @Override
    public String toString() {
        return enumName;
    }
}
