package com.stonebreak.rpg.classes;

/**
 * One purchasable node in a class's specialization path. Definition only — node effects are
 * implemented per class later.
 *
 * @param key      {@code "<classId>:p<path>:<tier>"}, see {@link ClassRegistry#pathKey}
 * @param tier     0..3 (Tier I..IV)
 * @param iconPath classpath icon, or {@code null}
 */
public record TalentNode(String key, String name, String description,
                         TalentNodeType type, int tier, int maxRank, String iconPath) {}
