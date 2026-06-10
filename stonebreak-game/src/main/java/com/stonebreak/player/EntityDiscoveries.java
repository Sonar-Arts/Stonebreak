package com.stonebreak.player;

import com.stonebreak.mobs.entities.EntityType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks per-player discovery data for the Entity Glossary screen.
 * Lives on {@link Player}, snapshotted into PlayerData on save, restored on load.
 *
 * <p>Discovery is gated on gameplay events:
 * <ul>
 *   <li><b>Variants seen</b> — player is within ~16 blocks and roughly in view of an entity</li>
 *   <li><b>Weakness discovered</b> — Ranger fully Studies the entity as Quarry (isMarkedPrey)</li>
 * </ul>
 */
public class EntityDiscoveries {

    private final Map<EntityType, Set<String>> variantsSeen = new EnumMap<>(EntityType.class);
    private final EnumSet<EntityType> weaknessesDiscovered = EnumSet.noneOf(EntityType.class);

    /**
     * Records that the player has seen the given texture variant for an entity type.
     * Normalizes variant name to lowercase to absorb pre-existing case inconsistencies.
     */
    public void recordVariantSeen(EntityType type, String variant) {
        if (type == null || variant == null) return;
        variantsSeen.computeIfAbsent(type, k -> new HashSet<>())
            .add(variant.toLowerCase());
    }

    /**
     * Records that the player has discovered the weakness of the given entity type.
     * Idempotent — calling multiple times has no additional effect.
     */
    public void recordWeaknessDiscovered(EntityType type) {
        if (type == null) return;
        weaknessesDiscovered.add(type);
    }

    /** True if the player has seen the given variant for this entity type. */
    public boolean hasSeenVariant(EntityType type, String variant) {
        if (type == null || variant == null) return false;
        Set<String> seen = variantsSeen.get(type);
        return seen != null && seen.contains(variant.toLowerCase());
    }

    /** True if the player has discovered the weakness of this entity type. */
    public boolean isWeaknessDiscovered(EntityType type) {
        return type != null && weaknessesDiscovered.contains(type);
    }

    /** Returns an unmodifiable view of all variants seen per entity type. */
    public Map<EntityType, Set<String>> getVariantsSeen() {
        return variantsSeen;
    }

    /** Returns an unmodifiable view of entity types whose weakness has been discovered. */
    public Set<EntityType> getWeaknessesDiscovered() {
        return Collections.unmodifiableSet(weaknessesDiscovered);
    }

    /**
     * Restores discovery state from saved data. Called during world load.
     */
    public void restore(Map<EntityType, Set<String>> variants, Set<EntityType> weaknesses) {
        variantsSeen.clear();
        if (variants != null) {
            for (Map.Entry<EntityType, Set<String>> entry : variants.entrySet()) {
                variantsSeen.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }
        weaknessesDiscovered.clear();
        if (weaknesses != null) {
            weaknessesDiscovered.addAll(weaknesses);
        }
    }
}
