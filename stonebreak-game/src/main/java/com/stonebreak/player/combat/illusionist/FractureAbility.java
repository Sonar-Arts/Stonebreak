package com.stonebreak.player.combat.illusionist;

import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_MAX_STACKS;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_FRACTURE_BEWILDERED_DURATION;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_FRACTURE_CONTAGION_RADIUS;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_FRACTURE_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_FRACTURE_RANGE;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_FRACTURE_STUN_BASE;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_FRACTURE_STUN_PER_STACK;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.IllusionDecoy;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.DoubtController;
import org.joml.Vector3f;

import java.util.List;

/**
 * Illusionist active: Fracture. Detonates the Doubt accumulated on nearby enemies, stunning each
 * for a duration that scales with its stack count and inflicting escalating control: at 2 stacks the
 * target is forced to attack the nearest entity once; at full stacks (Bewildered) it panics and
 * spreads a stack of Doubt to bystanders. Every affected target's Doubt is consumed afterwards.
 */
public class FractureAbility {

    private boolean active;
    private float cooldownRemaining;

    public boolean isActive() { return active; }
    public boolean isOnCooldown() { return cooldownRemaining > 0f; }
    public float getCooldownRemaining() { return cooldownRemaining; }

    /**
     * Fires Fracture on every Doubt-stacked enemy within range. Fails (no cooldown consumed) when
     * on cooldown or no doubted enemy is in range.
     */
    public boolean tryActivate(Player player, DoubtController doubt) {
        if (cooldownRemaining > 0f) return false;

        Vector3f origin = player.getPosition();
        float rangeSq = ILLUSIONIST_FRACTURE_RANGE * ILLUSIONIST_FRACTURE_RANGE;

        List<LivingEntity> doubted = doubt.getAllDoubted();
        List<LivingEntity> affected = new java.util.ArrayList<>();
        for (LivingEntity target : doubted) {
            if (target.getPosition().distanceSquared(origin) > rangeSq) continue;
            affected.add(target);
        }
        if (affected.isEmpty()) return false;

        for (LivingEntity target : affected) {
            applyFracture(target, doubt);
            doubt.consumeAll(target);
        }

        cooldownRemaining = ILLUSIONIST_FRACTURE_COOLDOWN;
        return true;
    }

    public void update(float deltaTime) {
        if (cooldownRemaining > 0f) {
            cooldownRemaining -= deltaTime;
        }
    }

    /** Clears the cooldown (world reload). */
    public void reset() {
        active = false;
        cooldownRemaining = 0f;
    }

    private void applyFracture(LivingEntity target, DoubtController doubt) {
        int stacks = doubt.getStacks(target);
        float stunDuration = ILLUSIONIST_FRACTURE_STUN_BASE
            + Math.max(0, stacks - 1) * ILLUSIONIST_FRACTURE_STUN_PER_STACK;
        target.applyStatusEffect(StatusEffectType.STUNNED, stunDuration, 0f);

        if (stacks >= ILLUSIONIST_DOUBT_MAX_STACKS) {
            // Bewildered: panic, then spread one Doubt to untracked bystanders.
            target.setBewildered(ILLUSIONIST_FRACTURE_BEWILDERED_DURATION);
            spreadContagion(target, doubt);
        } else if (stacks == 2) {
            // Forced to attack the nearest entity once.
            target.setForcedAttackTarget(nearestOther(target));
        }
    }

    private void spreadContagion(LivingEntity source, DoubtController doubt) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;
        List<Entity> nearby = entityManager.getEntitiesInRange(
            source.getPosition(), ILLUSIONIST_FRACTURE_CONTAGION_RADIUS);
        for (Entity entity : nearby) {
            if (entity == source || entity instanceof IllusionDecoy) continue;
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            if (doubt.getStacks(living) > 0) continue; // only untracked bystanders
            doubt.addStack(living);
        }
    }

    private LivingEntity nearestOther(LivingEntity from) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return null;
        LivingEntity nearest = null;
        float nearestDistSq = Float.MAX_VALUE;
        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (entity == from || entity instanceof IllusionDecoy || !entity.isAlive()) continue;
            float distSq = entity.getPosition().distanceSquared(from.getPosition());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entity;
            }
        }
        return nearest;
    }
}
