package com.stonebreak.rpg.classes;

/** An ability within a player class that can be unlocked using Class Points (CP). */
public record ClassAbility(String name, String description, int cpCost) {}
