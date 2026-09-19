package com.stonebreak.blocks.cactus;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.LivingEntity.DamageSource;
import com.stonebreak.world.World;

/**
 * World-living contact-damage system for mobs: every {@link CactusContactRules#PULSE_INTERVAL}
 * one pulse damages every living entity whose body touches a cactus cell
 * ({@link CactusContactRules#DAMAGE_PER_PULSE}, ENVIRONMENT source).
 *
 * <p>Tick on BOTH authoritative worlds (WorldUpdateOrchestrator update / updateSimulation) —
 * a render-only client never ticks it: mob shadows aren't damaged locally
 * (LivingEntityCombat forwards the intent instead of mutating).
 *
 * <p>Pulse pattern follows {@code LeylineBreachZone} (accumulate a timer, pulse on the
 * interval, damage every entity in contact) and the entity scan of {@code CaltropCluster}.
 * The mobs' own 0.5 s i-frames align with the pulse interval, so a stationary cactus never
 * over-damages a mob that stays pressed against it — and the ENVIRONMENT source never
 * credits the local player, so no knockback/kill-credit plumbing is involved.
 */
public final class CactusContactSystem {

    private final World world;
    private float timer;

    public CactusContactSystem(World world) {
        this.world = world;
    }

    public void tick(float deltaTime) {
        timer += deltaTime;
        if (timer < CactusContactRules.PULSE_INTERVAL) {
            return;
        }
        timer %= CactusContactRules.PULSE_INTERVAL; // keep remainders so slow frames don't lose pulses
        pulse();
    }

    /** Damages every living entity in this world whose body touches a cactus cell. */
    private void pulse() {
        EntityManager entityManager = world.getEntityManager(); // scan the OWNING world
        if (entityManager == null) {
            entityManager = Game.getEntityManager(); // rendered-world fallback, like CaltropCluster
        }
        if (entityManager == null) {
            return;
        }

        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive()) {
                continue;
            }
            if (!touchesCactus(entity)) {
                continue;
            }
            // attackerPos = the entity's own position: a stationary cactus applies
            // no knockback (the direction away from itself is zero), and ENVIRONMENT
            // never credits a player.
            entity.damage(CactusContactRules.DAMAGE_PER_PULSE, DamageSource.ENVIRONMENT,
                    entity.getPosition(), false);
        }
    }

    /**
     * True when any cactus cell touches the mob's body: the standing footprint cells
     * expanded by the probe margin, so a body flush against the solid spiky cell
     * (or resting on its tip) reads as contact.
     */
    private boolean touchesCactus(LivingEntity entity) {
        var position = entity.getPosition();
        float halfWidth = entity.getWidth() / 2;
        float halfLength = entity.getLength() / 2;
        return CactusContactRules.touchesCactus(world,
                position.x - halfWidth, position.y, position.z - halfLength,
                position.x + halfWidth, position.y + entity.getHeight(), position.z + halfLength);
    }
}
