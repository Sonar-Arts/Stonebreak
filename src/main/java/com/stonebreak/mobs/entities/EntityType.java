package com.stonebreak.mobs.entities;

/**
 * Enumeration of all entity types that can exist in the game world.
 * Each type defines properties specific to that entity category.
 */
public enum EntityType {
    // Living creatures (adjusted to match new model proportions)
    // Parameters: name, maxHealth, moveSpeed, height, width, length, legHeight, isLiving
    // Width reduced by 0.1 (0.05 per side) to move legs inward toward body
    // legHeight set to 0.62f to match visual model feet bottom (leg center -0.31f + leg half-height 0.31f = 0.62f from body to feet)
    COW("Cow", 10.0f, 1.2f, 1.125f, 0.65f, 0.625f, 0.62f, true),
    
    // Future entities can be added here
    // SHEEP("Sheep", 8.0f, 1.5f, 1.3f, 0.9f, 1.3f, true),
    // PIG("Pig", 10.0f, 1.0f, 0.9f, 0.9f, 1.3f, true),
    // CHICKEN("Chicken", 4.0f, 2.5f, 0.7f, 0.4f, 0.8f, true);
    ;
    
    private final String displayName;
    private final float maxHealth;
    private final float moveSpeed;
    private final float height;
    private final float width;
    private final float length;
    private final float legHeight; // Height from ground to bottom of body (leg length)
    private final boolean isLiving;
    
    /**
     * Creates a new entity type with the specified properties.
     */
    EntityType(String displayName, float maxHealth, float moveSpeed, 
               float height, float width, float length, float legHeight, boolean isLiving) {
        this.displayName = displayName;
        this.maxHealth = maxHealth;
        this.moveSpeed = moveSpeed;
        this.height = height;
        this.width = width;
        this.length = length;
        this.legHeight = legHeight;
        this.isLiving = isLiving;
    }
    
    /**
     * Gets the display name for this entity type.
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the maximum health for this entity type.
     */
    public float getMaxHealth() {
        return maxHealth;
    }
    
    /**
     * Gets the movement speed for this entity type.
     */
    public float getMoveSpeed() {
        return moveSpeed;
    }
    
    /**
     * Gets the height of this entity type.
     */
    public float getHeight() {
        return height;
    }
    
    /**
     * Gets the width of this entity type.
     */
    public float getWidth() {
        return width;
    }
    
    /**
     * Gets the length of this entity type.
     */
    public float getLength() {
        return length;
    }
    
    /**
     * Gets the leg height (distance from ground to bottom of body) for this entity type.
     */
    public float getLegHeight() {
        return legHeight;
    }
    
    /**
     * Checks if this entity type represents a living creature.
     */
    public boolean isLiving() {
        return isLiving;
    }
    
    /**
     * Gets the entity type by name (case-insensitive).
     */
    public static EntityType getByName(String name) {
        for (EntityType type : values()) {
            if (type.displayName.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}