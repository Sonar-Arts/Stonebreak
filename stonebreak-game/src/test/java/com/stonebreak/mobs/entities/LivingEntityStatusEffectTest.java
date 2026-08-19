package com.stonebreak.mobs.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stonebreak.mobs.entities.status.StatusEffectType;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the status-effect refresh path in {@link LivingEntity}: applying an
 * effect twice extends its duration AND replaces its magnitude with the latest application
 * (issue #232). DOT effects (BLEED/BURNING) therefore tick at the newly refreshed rate, and
 * strength-backed debuffs (SHAKEN) scale with reapplication rather than freezing at the first
 * application's value.
 */
class LivingEntityStatusEffectTest {

    @Test
    void reapplicationUpdatesMagnitudeNotJustDuration() {
        StubMob mob = new StubMob(EntityType.COW);
        mob.applyStatusEffect(StatusEffectType.SHAKEN, 2f, 0.15f);
        assertEquals(0.15f, mob.getStatusEffectMagnitude(StatusEffectType.SHAKEN));

        mob.applyStatusEffect(StatusEffectType.SHAKEN, 2f, 0.45f);
        assertEquals(0.45f, mob.getStatusEffectMagnitude(StatusEffectType.SHAKEN),
                "a reapplication must raise the effect's magnitude — 3 Doubt stacks must "
                + "hesitate at 0.45s, not stay frozen at the one-stack 0.15s (issue #232)");
    }

    @Test
    void reapplicationTicksAtTheNewDotRate() {
        StubMob mob = new StubMob(EntityType.COW);
        float startHealth = mob.getHealth();

        mob.applyStatusEffect(StatusEffectType.BLEED, 10f, 5f);
        mob.applyStatusEffect(StatusEffectType.BLEED, 10f, 1f); // a weaker source should win on reapply

        mob.updateStatusEffects(1.1f); // one full DOT tick elapses

        assertEquals(startHealth - 1f, mob.getHealth(), 1e-3f,
                "BLEED must tick at the latest refreshed magnitude (1 DPS), not the first (5 DPS)");
    }

    @Test
    void aPotencyDebuffIsNeverWeakenedByReapplication() {
        StubMob mob = new StubMob(EntityType.COW);
        mob.applyStatusEffect(StatusEffectType.CRIPPLE, 3f, 0.6f);
        mob.applyStatusEffect(StatusEffectType.CRIPPLE, 2f, 0.4f); // weaker source refreshes

        assertEquals(0.6f, mob.getStatusEffectMagnitude(StatusEffectType.CRIPPLE), 1e-6f,
                "a weaker re-application must not downgrade an active potency debuff");
    }

    @Test
    void aFreshEffectInheritsItsCreationMagnitude() {
        StubMob mob = new StubMob(EntityType.COW);
        mob.applyStatusEffect(StatusEffectType.SHAKEN, 2f, 0.3f);

        assertEquals(0.3f, mob.getStatusEffectMagnitude(StatusEffectType.SHAKEN));
        assertEquals(0f, mob.getStatusEffectMagnitude(StatusEffectType.BURNING),
                "an absent effect reads as zero magnitude");
    }
}
