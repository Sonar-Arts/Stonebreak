package com.stonebreak.player;

import java.util.List;

/**
 * Stub data holder for RPG character statistics.
 * All values are placeholder constants until RPG systems are implemented.
 * Health is live-read from the Player instance; everything else is a default.
 */
public class CharacterStats {

    private final Player player;

    public CharacterStats(Player player) {
        this.player = player;
    }

    // ─────────────────────────────────────────────── Ability scores (stubs)

    public int getStrength()     { return 10; }
    public int getDexterity()    { return 10; }
    public int getConstitution() { return 10; }
    public int getIntelligence() { return 10; }
    public int getWisdom()       { return 10; }
    public int getCharisma()     { return 10; }

    /** Standard modifier: floor((score - 10) / 2). */
    public int getModifier(int score) { return Math.floorDiv(score - 10, 2); }

    // ─────────────────────────────────────────────── Class / progression (stubs)

    public String getCharacterClass()      { return "Adventurer"; }
    public List<String> getFeats()         { return List.of(); }
    public List<String> getStatusEffects() { return List.of(); }

    // ─────────────────────────────────────────────── Point currencies (stubs)

    public int getClassPoints() { return 100; }
    public int getSkillPoints() { return 100; }
    public int getFeatPoints()  { return 100; }

    // ─────────────────────────────────────────────── Vitals

    /** Live health from player. */
    public float getHealth()    { return player != null ? player.getHealth()    : 0f; }
    public float getMaxHealth() { return player != null ? player.getMaxHealth() : 20f; }

    /** Mana — not yet implemented. */
    public float getMana()    { return 0f; }
    public float getMaxMana() { return 0f; }
}
