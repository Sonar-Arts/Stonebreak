package com.stonebreak.rpg.classes;

/**
 * An ability within a player class that can be unlocked using Class Points (CP).
 *
 * @param iconPath classpath resource path to a 16x16 icon (e.g. {@code /ui/abilities/berserker/Rampage.png}),
 *                 or {@code null} if the ability has no icon yet.
 */
public record ClassAbility(String name, String description, int cpCost, String iconPath) {}
