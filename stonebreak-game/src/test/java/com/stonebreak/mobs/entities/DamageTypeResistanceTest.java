package com.stonebreak.mobs.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.items.weapon.DamageType;
import org.junit.jupiter.api.Test;

/**
 * Hits are scaled by the target type's per-{@link DamageType} resistance: below 1 resists,
 * above 1 is a weakness, unlisted types (and untyped hits) land in full. Sources without an
 * explicit type keep their natural one (arrows pierce, fire burns).
 */
class DamageTypeResistanceTest {

    private static float damageTaken(EntityType type, DamageType damageType) {
        StubMob mob = new StubMob(type);
        float before = mob.getHealth();
        mob.damage(4f, LivingEntity.DamageSource.UNKNOWN, damageType, null, false);
        return before - mob.getHealth();
    }

    @Test
    void resistedWeakAndNeutralHitsScale() {
        assertEquals(3f, damageTaken(EntityType.COW, DamageType.BLUNT), 1e-4f);
        assertEquals(5f, damageTaken(EntityType.COW, DamageType.SLASHING), 1e-4f);
        assertEquals(4f, damageTaken(EntityType.COW, DamageType.PIERCING), 1e-4f);
        assertEquals(4f, damageTaken(EntityType.COW, DamageType.UNTYPED), 1e-4f);
    }

    @Test
    void sourcesWithoutATypeKeepTheirNaturalOne() {
        StubMob goose = new StubMob(EntityType.GOOSE);
        float before = goose.getHealth();
        goose.damage(4f, LivingEntity.DamageSource.ARROW);
        assertEquals(4f * EntityType.GOOSE.resistance(DamageType.PIERCING), before - goose.getHealth(), 1e-4f);
        assertEquals(DamageType.FIRE, LivingEntity.DamageSource.FIRE.defaultDamageType());
        assertEquals(DamageType.UNTYPED, LivingEntity.DamageSource.PLAYER.defaultDamageType());
    }

    @Test
    void typesWithoutResistancesTakeFullDamageAndSayNone() {
        for (DamageType t : DamageType.values()) {
            assertEquals(1f, EntityType.REMOTE_PLAYER.resistance(t));
        }
        assertEquals("None", EntityType.ILLUSION_DECOY.getResistances());
    }

    @Test
    void quarryRevealListsTheMultipliers() {
        String cow = EntityType.COW.getResistances();
        assertTrue(cow.contains("Blunt x0.75") && cow.contains("Slashing x1.25"), cow);
    }
}
