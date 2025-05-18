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
    IRON_ORE(10, "Iron Ore", true, true, 1, 1);
    
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
        return this == AIR || this == WATER || this == LEAVES;
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
        switch (this) {
            case GRASS:
                if (face == 0) return new float[]{0, 0}; // Top - grass
                if (face == 1) return new float[]{2, 0}; // Bottom - dirt
                return new float[]{1, 0}; // Sides - grass side
            case DIRT:
                return new float[]{2, 0};
            case STONE:
                return new float[]{3, 0};
            case BEDROCK:
                return new float[]{4, 0};
            case WOOD:
                if (face <= 1) return new float[]{5, 1}; // Top/bottom
                return new float[]{5, 0}; // Sides
            case LEAVES:
                return new float[]{7, 0}; // Atlas X changed from 6 to 7
            case SAND:
                return new float[]{8, 0}; // Atlas X changed from 7 to 8
            case WATER:
                return new float[]{9, 0}; // Atlas X changed from 8 to 9
            case COAL_ORE:
                return new float[]{0, 1};
            case IRON_ORE:
                return new float[]{1, 1};
            default:
                return new float[]{0, 0};
        }
    }
}
