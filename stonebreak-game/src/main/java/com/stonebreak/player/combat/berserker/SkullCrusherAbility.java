package com.stonebreak.player.combat.berserker;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.interaction.RaycastEngine;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

import static com.stonebreak.player.PlayerConstants.*;

/**
 * Berserker active: a slow, heavily telegraphed overhead slam on a single target (or a
 * targeted ground point). Tier-at-cast determines whether the impact also produces a
 * shockwave, a stun, and/or a persistent armor-breaking crater.
 * State machine: IDLE -&gt; WINDUP -&gt; COOLDOWN.
 */
public class SkullCrusherAbility {

    private enum State { IDLE, WINDUP, COOLDOWN }

    /** A lingering Armor Break zone left by a T3 slam. */
    private static final class CraterZone {
        final Vector3f position;
        float remaining;
        CraterZone(Vector3f position, float remaining) {
            this.position = position;
            this.remaining = remaining;
        }
    }

    private State state = State.IDLE;
    private float windupRemaining;
    private float windupDuration = SKULLCRUSHER_WINDUP_DURATION;
    private float cooldownRemaining;

    private RageTier tier = RageTier.NONE;
    private LivingEntity targetEntity;
    private final Vector3f impactPoint = new Vector3f();
    private final List<CraterZone> craterZones = new ArrayList<>();

    public boolean isActive() { return state != State.IDLE; }
    public boolean isOnCooldown() { return state == State.COOLDOWN; }
    public boolean isWindingUp() { return state == State.WINDUP; }

    /** 0..1 telegraph progress while winding up; 0 otherwise. Renderer hook for the visible tell. */
    public float getWindupProgress() {
        if (state != State.WINDUP || windupDuration <= 0f) return 0f;
        return Math.min(1f, (windupDuration - windupRemaining) / windupDuration);
    }

    /** Attempts to begin the slam at the given (pre-consumption) Rage tier. Returns true if the cast started. */
    public boolean tryActivate(Player player, RaycastEngine raycastEngine, RageTier tierAtCast) {
        if (state != State.IDLE) return false;

        EntityManager entityManager = Game.getEntityManager();
        LivingEntity raycastTarget = entityManager != null
            ? raycastEngine.raycastEntity(entityManager.getLivingEntities())
            : null;

        if (raycastTarget != null) {
            this.targetEntity = raycastTarget;
            this.impactPoint.set(raycastTarget.getPosition());
        } else {
            this.targetEntity = null;
            Vector3i blockHit = player.raycast();
            if (blockHit != null) {
                this.impactPoint.set(blockHit.x + 0.5f, blockHit.y + 1f, blockHit.z + 0.5f);
            } else {
                Vector3f front = new Vector3f(player.getCamera().getFront()).normalize();
                this.impactPoint.set(front.mul(SKULLCRUSHER_RANGE).add(player.getPosition()));
            }
        }

        this.tier = tierAtCast;
        this.windupDuration = SKULLCRUSHER_WINDUP_DURATION;
        this.windupRemaining = windupDuration;
        this.state = State.WINDUP;
        return true;
    }

    public void update(float deltaTime, Player player) {
        switch (state) {
            case WINDUP -> {
                windupRemaining -= deltaTime;
                if (windupRemaining <= 0f) {
                    impact(player);
                    state = State.COOLDOWN;
                    cooldownRemaining = SKULLCRUSHER_COOLDOWN;
                }
            }
            case COOLDOWN -> {
                cooldownRemaining -= deltaTime;
                if (cooldownRemaining <= 0f) {
                    state = State.IDLE;
                }
            }
            case IDLE -> { }
        }
        updateCraterZones(deltaTime);
    }

    private void impact(Player player) {
        if (targetEntity != null && targetEntity.isAlive()) {
            impactPoint.set(targetEntity.getPosition());
            targetEntity.damage(SKULLCRUSHER_DAMAGE, LivingEntity.DamageSource.PLAYER);
        }

        if (tier == RageTier.NONE) {
            return; // T0: direct hit only, no secondary effect
        }

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive()) continue;
            if (entity.getPosition().distance(impactPoint) > SKULLCRUSHER_SHOCKWAVE_RADIUS) continue;

            entity.damage(SKULLCRUSHER_SHOCKWAVE_DAMAGE, LivingEntity.DamageSource.PLAYER);

            if (tier.atLeast(RageTier.T2)) {
                entity.applyStatusEffect(StatusEffectType.STUNNED, SKULLCRUSHER_STUN_DURATION, 0f);
            }
        }

        if (tier == RageTier.T3) {
            craterZones.add(new CraterZone(new Vector3f(impactPoint), SKULLCRUSHER_CRATER_DURATION));
        }
    }

    private void updateCraterZones(float deltaTime) {
        if (craterZones.isEmpty()) return;

        EntityManager entityManager = Game.getEntityManager();
        List<LivingEntity> living = entityManager != null ? entityManager.getLivingEntities() : List.of();

        var it = craterZones.iterator();
        while (it.hasNext()) {
            CraterZone zone = it.next();
            zone.remaining -= deltaTime;
            if (zone.remaining <= 0f) {
                it.remove();
                continue;
            }
            for (LivingEntity entity : living) {
                if (!entity.isAlive()) continue;
                if (entity.getPosition().distance(zone.position) <= SKULLCRUSHER_CRATER_RADIUS) {
                    entity.applyStatusEffect(StatusEffectType.ARMOR_BREAK,
                        SKULLCRUSHER_ARMOR_BREAK_DURATION, SKULLCRUSHER_ARMOR_BREAK_MAGNITUDE);
                }
            }
        }
    }
}
