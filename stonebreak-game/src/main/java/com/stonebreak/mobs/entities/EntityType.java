package com.stonebreak.mobs.entities;

/**
 * Enumeration of all entity types that can exist in the game world.
 * Each type defines properties specific to that entity category.
 */
public enum EntityType {
    // Living creatures (adjusted to match new model proportions)
    // Parameters: displayName, EntityAttributes(STR,DEX,CON,INT,WIS,CHA), height, width, length, legHeight, sbeObjectId,
    //              weakness, weaknessDescription, textureVariants, specialAbilities
    COW("Cow",
        new EntityAttributes(8, 4, 5, 2, 3, 4),
        0.4f, 1.1f, 1.3f, 0.62f, "stonebreak:cow",
        LivingEntity.DamageSource.BLEED,
        "Thick hide resists blows, but bleeds heavily once cut.",
        new String[]{"Default", "Angus", "Highland"},
        new String[0]),
    SHEEP("Sheep",
        new EntityAttributes(5, 6, 4, 2, 3, 6),
        0.55f, 0.875f, 1.075f, 0.55f, "stonebreak:Sheep",
        LivingEntity.DamageSource.FIRE,
        "Dry wool catches fire instantly.",
        new String[]{"Default"},
        new String[0]),
    // legHeight is 0: the SB_Chicken.sbe model's origin sits at the feet, so the
    // entity position (placed at groundSurface + legHeight) must rest on the
    // ground itself. height covers the body+head bulk for collision.
    CHICKEN("Chicken",
        new EntityAttributes(1, 10, 2, 1, 2, 3),
        0.7f, 0.45f, 0.6f, 0.0f, "stonebreak:chicken",
        LivingEntity.DamageSource.ARROW,
        "A small, fragile frame — one clean arrow does it.",
        new String[]{"Default"},
        new String[0]),

    // Drop entities (small, physics-based items)
    BLOCK_DROP("Block Drop", 1.0f, 0.0f, 0.25f, 0.25f, 0.25f, 0.0f, false, null),
    ITEM_DROP("Item Drop", 1.0f, 0.0f, 0.25f, 0.25f, 0.25f, 0.0f, false, null),

    // Remote (multiplayer) player; rendered as a cylinder.
    // height = 1.8 (head-to-foot), width/length = 0.6, no separate legs (legHeight=0).
    REMOTE_PLAYER("Remote Player", 20.0f, 0.0f, 1.8f, 0.6f, 0.6f, 0.0f, true, null,
        "Varies", "Varies"),

    // Illusionist Mirrored Deceit decoy; player-shaped, 1 HP so any hit kills it.
    // Same dimensions as REMOTE_PLAYER and rendered through the same cylinder path.
    ILLUSION_DECOY("Illusion Decoy", 1.0f, 0.0f, 1.8f, 0.6f, 0.6f, 0.0f, true, null),

    // Projectile entities
    FIRE_BOLT("Fire Bolt", 1.0f, 0.0f, 0.3f, 0.3f, 0.3f, 0.0f, false, null),
    ARROW("Arrow", 1.0f, 0.0f, 0.1f, 0.05f, 0.5f, 0.0f, false, null),
    NULL_SPIKE("Null Spike", 1.0f, 0.0f, 0.25f, 0.25f, 0.25f, 0.0f, false, null),

    // Persistent spell zone (Arcanist's Leyline Breach)
    LEYLINE_BREACH_ZONE("Leyline Breach", 1.0f, 0.0f, 0.4f, 0.4f, 0.4f, 0.0f, false, null),

    // Fishing bobber
    BOBBER("Bobber", 1.0f, 0.0f, 0.2f, 0.2f, 0.2f, 0.0f, false, "stonebreak:Bobber"),

    // Future entities can be added here
    ;

    /** Entity types that appear as cards in the Glossary screen. */
    public static final EntityType[] GLOSSARY_TYPES = {COW, SHEEP, CHICKEN};

    private final String displayName;
    private final float maxHealth;      // literal for non-living; unused when attributes != null
    private final float moveSpeed;      // literal for non-living; unused when attributes != null
    private final float height;
    private final float width;
    private final float length;
    private final float legHeight; // Height from ground to bottom of body (leg length)
    private final boolean isLiving;
    private final String sbeObjectId; // SBE asset object id, or null if not SBE-driven
    private final String armorType;   // Quarry reveal descriptor (display-only)
    private final String resistances; // Quarry reveal descriptor (display-only)

    // Entity attributes (null for non-living types using legacy literal constructor)
    private final EntityAttributes attributes;

    // Glossary data (only populated for living creatures)
    private final LivingEntity.DamageSource weakness;
    private final String weaknessDescription;
    private final String[] textureVariants;
    private final String[] specialAbilities;

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
     * Creates a non-living entity type (block drops, projectiles, etc.) with literal
     * maxHealth/moveSpeed and default armor/resistance descriptors.
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
        this.attributes = null;
        this.weakness = null;
        this.weaknessDescription = null;
        this.textureVariants = new String[0];
        this.specialAbilities = new String[0];
    }

    /**
     * Creates a living-creature entity type driven by {@link EntityAttributes} plus
     * glossary metadata (weakness, variants, abilities).
     */
    EntityType(String displayName,
               EntityAttributes attributes,
               float height, float width, float length, float legHeight,
               String sbeObjectId,
               LivingEntity.DamageSource weakness,
               String weaknessDescription,
               String[] textureVariants,
               String[] specialAbilities) {
        this.displayName = displayName;
        this.maxHealth = attributes.deriveMaxHealth();
        this.moveSpeed = attributes.deriveMoveSpeed();
        this.height = height;
        this.width = width;
        this.length = length;
        this.legHeight = legHeight;
        this.isLiving = true;
        this.sbeObjectId = sbeObjectId;
        this.armorType = "Unarmored";
        this.resistances = "None";
        this.attributes = attributes;
        this.weakness = weakness;
        this.weaknessDescription = weaknessDescription;
        this.textureVariants = textureVariants;
        this.specialAbilities = specialAbilities;
    }

    /**
     * Gets the display name for this entity type.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the maximum health for this entity type.
     * For living creatures, derived from {@link EntityAttributes#deriveMaxHealth()}.
     */
    public float getMaxHealth() {
        return maxHealth;
    }

    /**
     * Gets the movement speed for this entity type.
     * For living creatures, derived from {@link EntityAttributes#deriveMoveSpeed()}.
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
     * Gets the six-attribute allocation for this entity type.
     * Returns null for non-living types.
     */
    public EntityAttributes getAttributes() {
        return attributes;
    }

    /**
     * Gets the elemental weakness for this entity type (display-only, glossary).
     */
    public LivingEntity.DamageSource getWeakness() {
        return weakness;
    }

    /**
     * Gets the human-readable weakness description (display-only, glossary).
     */
    public String getWeaknessDescription() {
        return weaknessDescription;
    }

    /**
     * Gets the texture variant names available for this entity type.
     */
    public String[] getTextureVariants() {
        return textureVariants;
    }

    /**
     * Gets the special ability names for this entity type (empty array if none).
     */
    public String[] getSpecialAbilities() {
        return specialAbilities;
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
