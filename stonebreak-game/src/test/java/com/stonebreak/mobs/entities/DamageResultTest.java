package com.stonebreak.mobs.entities;

import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LivingEntity.damage} reports whether a hit counted, so callers only pay out Rage,
 * lifesteal, Quarry and debuffs for hits that did (#374).
 */
class DamageResultTest {

    private static final LivingEntity.DamageSource PLAYER = LivingEntity.DamageSource.PLAYER;

    @Test
    void localHitCounts() {
        StubMob mob = new StubMob(EntityType.COW);
        assertTrue(mob.damage(1f, PLAYER));
    }

    @Test
    void hitDuringInvulnerabilityDoesNotCount() {
        StubMob mob = new StubMob(EntityType.COW);
        mob.damage(1f, PLAYER);
        assertFalse(mob.damage(1f, PLAYER));
    }

    @Test
    void hitOnDeadEntityDoesNotCount() {
        StubMob mob = new StubMob(EntityType.COW);
        mob.damage(10_000f, PLAYER);
        assertFalse(mob.isAlive());
        assertFalse(mob.damage(1f, PLAYER));
    }

    @Test
    void shadowWithNetworkIdForwardsTheHit() {
        StubMob shadow = new StubMob(EntityType.COW);
        shadow.setNetworkShadow(true);
        shadow.setNetworkId(5);
        float before = shadow.getHealth();
        assertTrue(shadow.damage(1f, PLAYER));
        assertEquals(before, shadow.getHealth(), "a shadow never mutates local health");
    }

    @Test
    void shadowWithoutNetworkIdDropsTheHit() {
        StubMob shadow = new StubMob(EntityType.COW);
        shadow.setNetworkShadow(true); // like a RemotePlayer: no id, nothing to forward to
        assertFalse(shadow.damage(1f, PLAYER));
    }

    @Test
    void knockbackAppliesOnlyWhenTheHitCounts() {
        StubMob mob = new StubMob(EntityType.COW);
        Knockback kb = new Knockback(1f, 0f, 9f, 4f);
        assertTrue(mob.damage(1f, PLAYER, kb));
        assertTrue(mob.getVelocity().x > 0f);

        Vector3f afterFirst = new Vector3f(mob.getVelocity());
        assertFalse(mob.damage(1f, PLAYER, kb)); // invulnerable now
        assertEquals(afterFirst, mob.getVelocity());
    }

    @Test
    void remotePlayersAreNotAttackable() {
        assertFalse(new RemotePlayer(null, new Vector3f(0, 64, 0), 2, "other").isPlayerAttackable());
    }

    @Test
    void onlyLivingMobsAreAttackable() {
        assertTrue(new StubMob(EntityType.COW).isPlayerAttackable());
        StubMob dead = new StubMob(EntityType.COW);
        dead.damage(10_000f, PLAYER);
        assertFalse(dead.isPlayerAttackable());
    }
}
