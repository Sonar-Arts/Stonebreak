package com.stonebreak.blocks;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemCategory;
import com.stonebreak.textures.atlas.AtlasIdManager;
import com.stonebreak.textures.atlas.AtlasMetadataCache;

/**
 * Defines all block types in the game.
 * These are items that can be placed in the world as blocks.
 */
public enum BlockType implements Item {
    // Updated to use atlas ID system with namespaced identifiers
    // IDs now align with atlas system expectations for proper texture mapping
    // Based on test data: grass=0, dirt=1, stone=2, sand=6, dandelion=16
    AIR(0, "Air", "air", false, false, 0.0f),
    GRASS(1, "Grass", "grass_block", true, true, 1.0f),
    DIRT(2, "Dirt", "dirt_block", true, true, 2.0f),
    STONE(3, "Stone", "stone", true, true, 4.0f),
    BEDROCK(4, "Bedrock", "bedrock", true, false, Float.POSITIVE_INFINITY),
    WOOD(5, "Wood", "wood", true, true, 3.0f),
    LEAVES(6, "Leaves", "leaves", true, true, 0.5f),
    SAND(7, "Sand", "sand", true, true, 1.5f),
    WATER(8, "Water", "water_temp", false, false, 0.0f),
    COAL_ORE(9, "Coal Ore", "coal_ore", true, true, 6.0f),
    IRON_ORE(10, "Iron Ore", "iron_ore", true, true, 8.0f),
    RED_SAND(11, "Red Sand", "red_sand", true, true, 1.5f),
    MAGMA(12, "Magma", "magma", true, true, 10.0f),
    CRYSTAL(13, "Crystal", "crystal", true, true, 12.0f),
    SANDSTONE(14, "Sandstone", "sandstone", true, true, 5.0f),
    RED_SANDSTONE(15, "Red Sandstone", "red_sandstone", true, true, 5.0f),
    ROSE(16, "Rose", "rose", false, true, 0.1f),
    DANDELION(17, "Dandelion", "dandelion", false, true, 0.1f),
    SNOWY_DIRT(18, "Snowy Dirt", "snowy_dirt", true, true, 1.0f),
    SNOWY_LEAVES(19, "Snowy Leaves", "snowy_leaves", true, true, 0.5f),
    PINE(20, "Pine", "pine_wood", true, true, 3.0f),
    ICE(21, "Ice", "ice", true, true, 2.0f),
    SNOW(22, "Snow", "snow", false, true, 0.1f),
    WORKBENCH(23, "Workbench", "workbench", true, true, 3.0f),
    WOOD_PLANKS(24, "Wood Planks", "wood_planks_custom", true, true, 3.0f),
    PINE_WOOD_PLANKS(25, "Pine Wood Planks", "pine_wood_planks_custom", true, true, 3.0f),
    ELM_WOOD_LOG(26, "Elm Wood Log", "elm_wood_log", true, true, 3.0f),
    ELM_WOOD_PLANKS(27, "Elm Wood Planks", "elm_wood_planks_custom", true, true, 3.0f),
    ELM_LEAVES(28, "Elm Leaves", "elm_leaves", true, true, 0.5f),
    COBBLESTONE(29, "Cobblestone", "cobblestone", true, true, 4.0f),
    GRAVEL(30, "Gravel", "gravel", true, true, 1.5f);

    public enum Face {
        TOP(0), BOTTOM(1), SIDE_NORTH(2), SIDE_SOUTH(3), SIDE_EAST(4), SIDE_WEST(5);
        private final int index;
        Face(int index) { this.index = index; }
        public int getIndex() { return index; }
    }
    
    private final int id;
    private final String name;
    private final String atlasName; // Atlas namespaced identifier (e.g., "grass_block")
    private final boolean solid;
    private final boolean breakable;
    private final float hardness; // Time in seconds to break with bare hands

    // Static atlas cache for dynamic coordinate lookups
    private static AtlasMetadataCache atlasCache;

    BlockType(int id, String name, String atlasName, boolean solid, boolean breakable, float hardness) {
        this.id = id;
        this.name = name;
        this.atlasName = atlasName;
        this.solid = solid;
        this.breakable = breakable;
        this.hardness = hardness;
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
    
    /**
     * Determines if the block is transparent (allows rendering of faces behind it)
     * @return true if the block is transparent (like air or water)
     */
    public boolean isTransparent() {
        return this == AIR || this == WATER || this == LEAVES || this == ROSE || this == DANDELION || this == SNOWY_LEAVES || this == ICE || this == SNOW || this == ELM_LEAVES;
    }

    /**
     * Gets the atlas namespaced name for this block type.
     * @return The atlas identifier (e.g., "grass_block")
     */
    public String getAtlasName() {
        return atlasName;
    }

    /**
     * Gets the full namespaced atlas identifier.
     * @return The full atlas name (e.g., "stonebreak:grass_block")
     */
    public String getNamespacedAtlasName() {
        return "stonebreak:" + atlasName;
    }

    /**
     * Sets the atlas cache for dynamic coordinate lookups.
     * Should be called during game initialization.
     */
    public static void setAtlasCache(AtlasMetadataCache cache) {
        atlasCache = cache;
    }

    @Override
    public int getAtlasX() {
        if (atlasCache != null && this != AIR) {
            var coords = atlasCache.get(getNamespacedAtlasName() + "_top");
            if (coords == null) {
                coords = atlasCache.get(getNamespacedAtlasName());
            }
            if (coords != null) {
                return coords.atlasX;
            }
        }
        return this == AIR ? -1 : 0; // Fallback
    }

    @Override
    public int getAtlasY() {
        if (atlasCache != null && this != AIR) {
            var coords = atlasCache.get(getNamespacedAtlasName() + "_top");
            if (coords == null) {
                coords = atlasCache.get(getNamespacedAtlasName());
            }
            if (coords != null) {
                return coords.atlasY;
            }
        }
        return this == AIR ? -1 : 0; // Fallback
    }
    
    public float getHardness() {
        return hardness;
    }
    
    // Item interface implementation
    @Override
    public int getMaxStackSize() {
        return 64; // Default stack size for blocks
    }
    
    @Override
    public ItemCategory getCategory() {
        return ItemCategory.BLOCKS;
    }
    
    /**
     * Gets the visual height of the block (0.0 to 1.0 where 1.0 is full block height)
     * For snow blocks, this can be less than 1.0 to represent layers
     * @param layerCount Number of snow layers (1-8 for snow blocks)
     */
    public float getVisualHeight(int layerCount) {
        if (this == SNOW) {
            return Math.min(1.0f, Math.max(0.125f, layerCount * 0.125f)); // 1-8 layers
        }
        return 1.0f; // Full block height for all other blocks
    }
    
    /**
     * Gets the visual height of the block with default layer count
     */
    public float getVisualHeight() {
        return getVisualHeight(1);
    }
    
    /**
     * Gets the collision height of the block (0.0 to 1.0 where 1.0 is full block height)
     * This determines what portion of the block has collision
     * @param layerCount Number of snow layers (1-8 for snow blocks)
     */
    public float getCollisionHeight(int layerCount) {
        if (this == SNOW) {
            return Math.min(1.0f, Math.max(0.125f, layerCount * 0.125f)); // Only the snow portion has collision
        }
        return solid ? 1.0f : 0.0f; // Full collision for solid blocks, none for non-solid
    }
    
    /**
     * Gets the collision height of the block with default layer count
     */
    public float getCollisionHeight() {
        return getCollisionHeight(1);
    }
    
    /**
     * Determines if this block type can be stacked (like snow layers)
     */
    public boolean isStackable() {
        return this == SNOW;
    }
    
    /**
     * Determines if this item can be placed as a block in the world
     * All BlockType items are placeable by definition
     */
    public boolean isPlaceable() {
        return true;
    }
    
    /**
     * Get block type by ID.
     */
    public static BlockType getById(int id) {
        for (BlockType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return AIR; // Default to air
    }
    
    /**
     * Get dynamic texture coordinates for the block type using atlas cache.
     * @param face The face of the block (TOP, BOTTOM, SIDE_NORTH, etc.)
     * @return Array with [x, y] pixel coordinates in the texture atlas, or [0, 0] if not found
     */
    public float[] getTextureCoords(Face face) {
        if (this == AIR) {
            return new float[]{-1, -1}; // No texture for air
        }

        if (atlasCache == null) {
            // Fallback to basic coordinates if atlas cache not available
            return new float[]{0, 0};
        }

        // Build texture name based on face and block atlas name
        String textureName = getNamespacedAtlasName();
        String faceSuffix = getFaceSuffix(face);

        // Try face-specific texture first
        if (!faceSuffix.isEmpty()) {
            var coords = atlasCache.get(textureName + "_" + faceSuffix);
            if (coords != null) {
                return new float[]{coords.atlasX, coords.atlasY};
            }
        }

        // Fall back to general texture
        var coords = atlasCache.get(textureName);
        if (coords != null) {
            return new float[]{coords.atlasX, coords.atlasY};
        }

        // Ultimate fallback for missing textures
        System.err.println("Warning: No atlas texture found for " + textureName + " face " + face);
        return new float[]{0, 0};
    }

    /**
     * Gets the face suffix for atlas texture lookups.
     * @param face The block face
     * @return The suffix string for the face (e.g., \"top\", \"bottom\", \"north\")
     */
    private String getFaceSuffix(Face face) {
        return switch (face) {
            case TOP -> "top";
            case BOTTOM -> "bottom";
            case SIDE_NORTH -> "north";
            case SIDE_SOUTH -> "south";
            case SIDE_EAST -> "east";
            case SIDE_WEST -> "west";
        };
    }

    /**
     * Get UV coordinates for the block type using atlas cache.
     * @param face The face of the block
     * @return Array with [u1, v1, u2, v2] UV coordinates, or [0, 0, 1, 1] if not found
     */
    public float[] getUVCoords(Face face) {
        if (this == AIR) {
            return new float[]{0, 0, 0, 0}; // No UV for air
        }

        if (atlasCache == null) {
            // Fallback UV coordinates if atlas cache not available
            return new float[]{0, 0, 1, 1};
        }

        String textureName = getNamespacedAtlasName();
        String faceSuffix = getFaceSuffix(face);

        // Try face-specific texture first
        if (!faceSuffix.isEmpty()) {
            var coords = atlasCache.get(textureName + "_" + faceSuffix);
            if (coords != null) {
                return coords.getUVArray();
            }
        }

        // Fall back to general texture
        var coords = atlasCache.get(textureName);
        if (coords != null) {
            return coords.getUVArray();
        }

        // Ultimate fallback
        System.err.println("Warning: No UV coordinates found for " + textureName + " face " + face);
        return new float[]{0, 0, 1, 1};
    }
}
