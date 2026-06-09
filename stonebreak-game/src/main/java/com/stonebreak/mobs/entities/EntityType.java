package com.stonebreak.mobs.entities;

/**
 * Enumeration of all entity types that can exist in the game world.
 * Each type defines properties specific to that entity category.
 */
public enum EntityType {
    // Living creatures (adjusted to match new model proportions)
    // Parameters: name, maxHealth, moveSpeed, height, width, length, legHeight, isLiving, sbeObjectId
    // sbeObjectId references an SBE asset in the SbeEntityRegistry, or null when
    // the entity is not SBE-driven. Living creatures additionally carry armorType and
    // resistances descriptors, revealed to a Ranger studying them as Quarry.
    COW("Cow", 10.0f, 1.2f, 0.4f, 1.1f, 1.3f, 0.62f, true, "stonebreak:cow",
        "Thick Hide", "None"),
    SHEEP("Sheep", 8.0f, 1.3f, 0.55f, 0.875f, 1.075f, 0.55f, true, "stonebreak:Sheep",
        "Wool Padding", "None"),
    // legHeight is 0: the SB_Chicken.sbe model's origin sits at the feet, so the
    // entity position (placed at groundSurface + legHeight) must rest on the
    // ground itself. height covers the body+head bulk for collision.
    CHICKEN("Chicken", 4.0f, 1.5f, 0.7f, 0.45f, 0.6f, 0.0f, true, "stonebreak:chicken",
        "Feathers", "None"),

    // Drop entities (small, physics-based items)
    BLOCK_DROP("Block Drop", 1.0f, 0.0f, 0.25f, 0.25f, 0.25f, 0.0f, false, null),
    ITEM_DROP("Item Drop", 1.0f, 0.0f, 0.25f, 0.25f, 0.25f, 0.0f, false, null),

    // Remote (multiplayer) player; rendered as a cylinder.
    // height = 1.8 (head-to-foot), width/length = 0.6, no separate legs (legHeight=0).
    REMOTE_PLAYER("Remote Player", 20.0f, 0.0f, 1.8f, 0.6f, 0.6f, 0.0f, true, null,
        "Varies", "Varies"),

    // Projectile entities
    FIRE_BOLT("Fire Bolt", 1.0f, 0.0f, 0.3f, 0.3f, 0.3f, 0.0f, false, null),
    ARROW("Arrow", 1.0f, 0.0f, 0.1f, 0.05f, 0.5f, 0.0f, false, null),

    // Fishing bobber
    BOBBER("Bobber", 1.0f, 0.0f, 0.2f, 0.2f, 0.2f, 0.0f, false, "stonebreak:Bobber"),

    // Future entities can be added here
    ;

    private final String displayName;
    private final float maxHealth;
    private final float moveSpeed;
    private final float height;
    private final float width;
    private final float length;
    private final float legHeight; // Height from ground to bottom of body (leg length)
    private final boolean isLiving;
    private final String sbeObjectId; // SBE asset object id, or null if not SBE-driven
    private final String armorType;   // Quarry reveal descriptor (display-only)
    private final String resistances; // Quarry reveal descriptor (display-only)

    /**
     * Creates a new entity type with default armor/resistance descriptors.
     */
    EntityType(String displayName, float maxHealth, float moveSpeed,
               float height, float width, float length, float legHeight, boolean isLiving,
               String sbeObjectId) {
        this(displayName, maxHealth, moveSpeed, height, width, length, legHeight, isLiving,
            sbeObjectId, "Unarmored", "None");
    }

    /**
     * Creates a new entity type with the specified properties.
     */
    EntityType(String displayName, float maxHealth, float moveSpeed,
               float height, float width, float length, float legHeight, boolean isLiving,
               String sbeObjectId, String armorType, String resistances) {
        this.displayName = displayName;
        this.maxHealth = maxHealth;
        this.moveSpeed = moveSpeed;
        this.height = height;
        this.width = width;
        this.length = length;
        this.legHeight = legHeight;
        this.isLiving = isLiving;
        this.sbeObjectId = sbeObjectId;
        this.armorType = armorType;
        this.resistances = resistances;
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
     * The SBE asset object id this entity renders from (e.g. {@code stonebreak:cow}),
     * or {@code null} if the entity is not SBE-driven.
     */
    public String getSbeObjectId() {
        return sbeObjectId;
    }

    /**
     * Gets the armor type descriptor revealed by the Ranger's Quarry study (display-only).
     */
    public String getArmorType() {
        return armorType;
    }

    /**
     * Gets the elemental resistance descriptor revealed by the Ranger's Quarry study (display-only).
     */
    public String getResistances() {
        return resistances;
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