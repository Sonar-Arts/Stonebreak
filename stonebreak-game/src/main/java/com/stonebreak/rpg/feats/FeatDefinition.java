package com.stonebreak.rpg.feats;

/** Immutable definition of a feat. */
public record FeatDefinition(
    String id,
    String name,
    String description,
    int level,
    boolean isGeneral
) {}
