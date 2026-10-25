package com.stonebreak.rpg.classes;

/**
 * A core ability of a player class, unlocked by its first rank in the class tree. Core abilities
 * are tier-I nodes keyed {@code "<classId>:<index>"}.
 *
 * @param cpCost   deprecated and unused: the CP price of a rank is set by the class's multiclass slot
 *                 ({@code CharacterStats.getCpCostPerRank}). Kept so existing definitions compile.
 * @param iconPath classpath resource path to a 16x16 icon (e.g. {@code /ui/abilities/berserker/Rampage.png}),
 *                 or {@code null} if the ability has no icon yet.
 */
public record ClassAbility(String name, String description, int cpCost, String iconPath) {}
