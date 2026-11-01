package com.stonebreak.mobs.sbe;

import java.util.Locale;

/**
 * Stonebreak's entity types, the vocabulary of an SBE's free-form {@code entityType} (#383: this was
 * {@code SBEFormat.EntityType} in the engine). The editor's picker and the {@code sbe} target
 * folders follow it; unknown names read as {@link #OTHER}.
 */
public enum SbeEntityKind {
    MOB("mob", "Mob"),
    NPC("npc", "NPC"),
    PROJECTILE("projectile", "Projectile"),
    VEHICLE("vehicle", "Vehicle"),
    OTHER("other", "Other");

    private final String id;
    private final String label;

    SbeEntityKind(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The manifest name ({@code mob}). */
    public String id() {
        return id;
    }

    /** The display name ({@code Mob}). */
    public String label() {
        return label;
    }

    /** The kind named {@code id} (case-insensitive); {@link #OTHER} when unknown or null. */
    public static SbeEntityKind fromId(String id) {
        if (id != null) {
            String n = id.trim().toLowerCase(Locale.ROOT);
            for (SbeEntityKind k : values()) {
                if (k.id.equals(n)) return k;
            }
        }
        return OTHER;
    }
}
