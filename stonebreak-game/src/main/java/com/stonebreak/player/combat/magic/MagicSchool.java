package com.stonebreak.player.combat.magic;

/**
 * Categorises spells by the nature of the magic used. Schools drive the Arcanist's
 * Resonance passive (stacks build by varying schools across casts) and will be
 * referenced by future spellcasting classes (Illusionist, Bard, ...).
 */
public enum MagicSchool {
    ARCANA("Arcana", "Raw magical force, direct damage and amplification"),
    CONJURATION("Conjuration", "Zone creation, summoning, terrain effects"),
    ILLUSION("Illusion", "Perception manipulation, misdirection, deception"),
    ENCHANTMENT("Enchantment", "Mind and will influence, crowd control"),
    TRANSMUTATION("Transmutation", "Property alteration, buffs and debuffs"),
    DIVINATION("Divination", "Prediction, information, weakness exposure");

    private final String displayName;
    private final String description;

    MagicSchool(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }

    public String getDescription() { return description; }
}
