package com.stonebreak.items.weapon;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the melee {@link WeaponStats} of an item.
 *
 * <p>Data-driven (SBO 1.10, authored in Open Mason's SBO editor): a weapon's
 * item SBO carries a {@code weapon} section ({@link SBOFormat.WeaponData}:
 * group, damage type, damage and swing seconds). Any item without one —
 * blocks, materials, food — strikes like a bare fist ({@link WeaponStats#UNARMED}),
 * as does a section naming a group or damage type the game does not know.
 */
public final class WeaponRules {

    private static final Logger logger = LoggerFactory.getLogger(WeaponRules.class);

    /** Per-item cache; {@code Optional.empty()} = item is not a weapon. */
    private static final Map<ItemType, Optional<WeaponStats>> CACHE = new ConcurrentHashMap<>();

    private WeaponRules() {
    }

    /** Stats for striking with {@code item}; {@link WeaponStats#UNARMED} for {@code null} or non-weapons. */
    public static WeaponStats statsFor(ItemType item) {
        if (item == null) return WeaponStats.UNARMED;
        return CACHE.computeIfAbsent(item, WeaponRules::lookup).orElse(WeaponStats.UNARMED);
    }

    private static Optional<WeaponStats> lookup(ItemType item) {
        String objectId = ItemType.objectIdFor(item);
        if (objectId == null) return Optional.empty();
        try {
            return ItemRegistry.getInstance().get(objectId)
                    .map(e -> e.sboData() != null && e.sboData().manifest() != null
                            ? e.sboData().manifest().weapon() : null)
                    .flatMap(w -> toStats(objectId, w));
        } catch (RuntimeException ex) {
            logger.warn("Failed to read weapon data for {}: {}", item, ex.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<WeaponStats> toStats(String objectId, SBOFormat.WeaponData w) {
        try {
            return Optional.of(new WeaponStats(
                    WeaponGroup.valueOf(w.group().toUpperCase(Locale.ROOT)),
                    DamageType.valueOf(w.damageType().toUpperCase(Locale.ROOT)),
                    w.damage(),
                    w.swingSeconds()));
        } catch (IllegalArgumentException ex) {
            logger.warn("Ignoring weapon data for {} (group '{}', damage type '{}'): {}",
                    objectId, w.group(), w.damageType(), ex.getMessage());
            return Optional.empty();
        }
    }
}
