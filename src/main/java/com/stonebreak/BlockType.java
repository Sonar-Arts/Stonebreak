package com.stonebreak;

/**
 * Defines all block types in the game.
 */
public enum BlockType {
    // Added atlasX, atlasY for inventory/hotbar display.
    // These are the primary texture coordinates for the block.
    // For blocks like GRASS with multiple textures, this will be the "icon" texture (e.g., grass top).
    AIR(0, "Air", false, false, -1, -1, 0.0f), // No texture for air
    GRASS(1, "Grass", true, true, 0, 0, 1.0f),     // Grass Top
    DIRT(2, "Dirt", true, true, 2, 0, 2.0f),
    STONE(3, "Stone", true, true, 3, 0, 4.0f),
    BEDROCK(4, "Bedrock", true, false, 4, 0, Float.POSITIVE_INFINITY),
    WOOD(5, "Wood", true, true, 5, 0, 3.0f),       // Wood Side (or could be Wood Top: 5,1)
    LEAVES(6, "Leaves", true, true, 7, 0, 0.5f), // Atlas X changed from 6 to 7
    SAND(7, "Sand", true, true, 8, 0, 1.5f),       // Atlas X changed from 7 to 8
    WATER(8, "Water", false, false, 9, 0, 0.0f),    // Atlas X changed from 8 to 9
    COAL_ORE(9, "Coal Ore", true, true, 0, 1, 6.0f),
    IRON_ORE(10, "Iron Ore", true, true, 1, 1, 8.0f),
    RED_SAND(11, "Red Sand", true, true, 2, 1, 1.5f), // Was Obsidian, now red sand at old obsidian atlas coords
    MAGMA(12, "Magma", true, true, 3, 1, 10.0f),       // Placeholder atlas coords
    CRYSTAL(13, "Crystal", true, true, 4, 1, 12.0f),   // Placeholder atlas coords
    SANDSTONE(14, "Sandstone", true, true, 5, 1, 5.0f), // Top texture for icon
    RED_SANDSTONE(15, "Red Sandstone", true, true, 6, 1, 5.0f), // Top texture for icon
    ROSE(16, "Rose", false, true, 10, 1, 0.1f), // Moved from 7,1
    DANDELION(17, "Dandelion", false, true, 11, 1, 0.1f), // Moved from 8,1
    SNOWY_DIRT(18, "Snowy Dirt", true, true, 0, 2, 1.0f), // Snow Top (clone of grass)
    SNOWY_LEAVES(19, "Snowy Leaves", true, true, 4, 2, 0.5f),
    PINE(20, "Pine", true, true, 2, 2, 3.0f), // Darker wood variant
    ICE(21, "Ice", true, true, 3, 2, 2.0f),
    SNOW(22, "Snow", false, true, 5, 2, 0.1f), // Layered snow block
    WORKBENCH(23, "Workbench", true, true, 6, 2, 3.0f), // Placeholder atlas coords (6,2)
    WOOD_PLANKS(24, "Wood Planks", true, true, 0, 3, 2.0f), // Atlas coords (0,3) placeholder
    PINE_WOOD_PLANKS(25, "Pine Wood Planks", true, true, 2, 3, 2.0f), // Atlas coords (2,3)
    STICK(26, "Stick", false, true, 1, 3, 0.1f); // Atlas coords (1,3)

    public enum Face {
        TOP(0), BOTTOM(1), SIDE_NORTH(2), SIDE_SOUTH(3), SIDE_EAST(4), SIDE_WEST(5);
        private final int index;
        Face(int index) { this.index = index; }
        public int getIndex() { return index; }
    }
    
    private final int id;
    private final String name;
    private final boolean solid;
    private final boolean breakable;
    private final int atlasX; // Texture atlas X coordinate for UI
    private final int atlasY; // Texture atlas Y coordinate for UI
    private final float hardness; // Time in seconds to break with bare hands
    
    BlockType(int id, String name, boolean solid, boolean breakable, int atlasX, int atlasY, float hardness) {
        this.id = id;
        this.name = name;
        this.solid = solid;
        this.breakable = breakable;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
        this.hardness = hardness;
    }
    
    public int getId() {
        return id;
    }
    
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
        return this == AIR || this == WATER || this == LEAVES || this == ROSE || this == DANDELION || this == SNOWY_LEAVES || this == ICE || this == SNOW || this == STICK;
    }

    public int getAtlasX() {
        return atlasX;
    }

    public int getAtlasY() {
        return atlasY;
    }
    
    public float getHardness() {
        return hardness;
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
     * Items like sticks and tools should not be placeable
     */
    public boolean isPlaceable() {
        return this != STICK;
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
     * Get texture coordinates for the block type.
     * @param face The face of the block (0=top, 1=bottom, 2-5=sides)
     * @return Array with [x, y] coordinates in the texture atlas
     */
    public float[] getTextureCoords(Face face) {
        return switch (this) {
            case GRASS -> {
                if (face == Face.TOP) yield new float[]{0, 0}; // Top - grass
                if (face == Face.BOTTOM) yield new float[]{2, 0}; // Bottom - dirt
                yield new float[]{1, 0}; // Sides - grass side
            }
            case DIRT -> new float[]{2, 0};
            case STONE -> new float[]{3, 0};
            case BEDROCK -> new float[]{4, 0};
            case WOOD -> {
                if (face == Face.TOP) yield new float[]{5, 1}; // Top
                if (face == Face.BOTTOM) yield new float[]{2, 0}; // Bottom - using DIRT texture for now
                yield new float[]{5, 0}; // Sides
            }
            case LEAVES -> new float[]{7, 0}; // Atlas X changed from 6 to 7
            case SAND -> new float[]{8, 0}; // Atlas X changed from 7 to 8
            case WATER -> new float[]{9, 0}; // Atlas X changed from 8 to 9
            case COAL_ORE -> new float[]{0, 1};
            case IRON_ORE -> new float[]{1, 1};
            case RED_SAND -> new float[]{2, 1}; // Use its unique atlas coordinates
            case MAGMA -> new float[]{3, 1}; // Placeholder atlas coords
            case CRYSTAL -> new float[]{4, 1}; // Placeholder atlas coords
            case SANDSTONE -> {
                if (face == Face.TOP) yield new float[]{5, 1}; // Top
                if (face == Face.BOTTOM) yield new float[]{5, 1}; // Bottom (same as top)
                yield new float[]{7, 1}; // Sides
            }
            case RED_SANDSTONE -> {
                if (face == Face.TOP) yield new float[]{6, 1}; // Top
                if (face == Face.BOTTOM) yield new float[]{6, 1}; // Bottom (same as top)
                yield new float[]{8, 1}; // Sides
            }
            case ROSE -> new float[]{10, 1}; // Moved from 7,1
            case DANDELION -> new float[]{11, 1}; // Moved from 8,1
            case SNOWY_DIRT -> {
                if (face == Face.TOP) yield new float[]{0, 2}; // Top - snow
                if (face == Face.BOTTOM) yield new float[]{2, 0}; // Bottom - dirt
                yield new float[]{1, 2}; // Sides - snow side
            }
            case SNOW -> new float[]{5, 2}; // Pure snow texture for layers
            case SNOWY_LEAVES -> new float[]{4, 2};
            case PINE -> {
                if (face == Face.TOP) yield new float[]{2, 2}; // Top
                if (face == Face.BOTTOM) yield new float[]{2, 0}; // Bottom - dirt
                yield new float[]{2, 2}; // Sides
            }
            case ICE -> new float[]{3, 2};
            case WORKBENCH -> {
                if (face == Face.TOP) yield new float[]{6, 2}; // Top - main texture
                if (face == Face.BOTTOM) yield new float[]{2, 0}; // Bottom - Dirt texture (like wood)
                yield new float[]{7, 2}; // Sides - placeholder side texture
            }
            case WOOD_PLANKS -> new float[]{0, 3}; // All faces use (0,3)
            case PINE_WOOD_PLANKS -> new float[]{2, 3}; // All faces use (2,3)
            case STICK -> new float[]{1, 3}; // All faces use (1,3)
            default -> new float[]{0, 0};
        };
    }
}
