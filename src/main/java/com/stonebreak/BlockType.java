package com.stonebreak;

/**
 * Defines all block types in the game.
 */
public enum BlockType {
    // Added atlasX, atlasY for inventory/hotbar display.
    // These are the primary texture coordinates for the block.
    // For blocks like GRASS with multiple textures, this will be the "icon" texture (e.g., grass top).
    AIR(0, "Air", false, false, -1, -1), // No texture for air
    GRASS(1, "Grass", true, true, 0, 0),     // Grass Top
    DIRT(2, "Dirt", true, true, 2, 0),
    STONE(3, "Stone", true, true, 3, 0),
    BEDROCK(4, "Bedrock", true, false, 4, 0),
    WOOD(5, "Wood", true, true, 5, 0),       // Wood Side (or could be Wood Top: 5,1)
    LEAVES(6, "Leaves", true, true, 7, 0), // Atlas X changed from 6 to 7
    SAND(7, "Sand", true, true, 8, 0),       // Atlas X changed from 7 to 8
    WATER(8, "Water", false, false, 9, 0),    // Atlas X changed from 8 to 9
    COAL_ORE(9, "Coal Ore", true, true, 0, 1),
    IRON_ORE(10, "Iron Ore", true, true, 1, 1),
    RED_SAND(11, "Red Sand", true, true, 2, 1), // Was Obsidian, now red sand at old obsidian atlas coords
    MAGMA(12, "Magma", true, true, 3, 1),       // Placeholder atlas coords
    CRYSTAL(13, "Crystal", true, true, 4, 1),   // Placeholder atlas coords
    SANDSTONE(14, "Sandstone", true, true, 5, 1), // Top texture for icon
    RED_SANDSTONE(15, "Red Sandstone", true, true, 6, 1), // Top texture for icon
    ROSE(16, "Rose", false, true, 10, 1), // Moved from 7,1
    DANDELION(17, "Dandelion", false, true, 11, 1); // Moved from 8,1
    
    private final int id;
    private final String name;
    private final boolean solid;
    private final boolean breakable;
    private final int atlasX; // Texture atlas X coordinate for UI
    private final int atlasY; // Texture atlas Y coordinate for UI
    
    BlockType(int id, String name, boolean solid, boolean breakable, int atlasX, int atlasY) {
        this.id = id;
        this.name = name;
        this.solid = solid;
        this.breakable = breakable;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
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
        return this == AIR || this == WATER || this == LEAVES || this == ROSE || this == DANDELION;
    }

    public int getAtlasX() {
        return atlasX;
    }

    public int getAtlasY() {
        return atlasY;
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
    public float[] getTextureCoords(int face) {
        return switch (this) {
            case GRASS -> {
                if (face == 0) yield new float[]{0, 0}; // Top - grass
                if (face == 1) yield new float[]{2, 0}; // Bottom - dirt
                yield new float[]{1, 0}; // Sides - grass side
            }
            case DIRT -> new float[]{2, 0};
            case STONE -> new float[]{3, 0};
            case BEDROCK -> new float[]{4, 0};
            case WOOD -> {
                if (face == 0) yield new float[]{5, 1}; // Top
                if (face == 1) yield new float[]{2, 0}; // Bottom - using DIRT texture for now
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
                if (face == 0) yield new float[]{5, 1}; // Top
                if (face == 1) yield new float[]{5, 1}; // Bottom (same as top)
                yield new float[]{7, 1}; // Sides
            }
            case RED_SANDSTONE -> {
                if (face == 0) yield new float[]{6, 1}; // Top
                if (face == 1) yield new float[]{6, 1}; // Bottom (same as top)
                yield new float[]{8, 1}; // Sides
            }
            case ROSE -> new float[]{10, 1}; // Moved from 7,1
            case DANDELION -> new float[]{11, 1}; // Moved from 8,1
            default -> new float[]{0, 0};
        };
    }
}
