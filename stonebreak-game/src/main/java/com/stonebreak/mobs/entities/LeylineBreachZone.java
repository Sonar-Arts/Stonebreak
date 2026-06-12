package com.stonebreak.mobs.entities;

import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_AMPLIFY_BONUS;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_AMPLIFY_DURATION;
import static com.stonebreak.player.PlayerConstants.LEYLINE_BREACH_PULSE_INTERVAL;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Persistent ground zone spawned by the Arcanist's Leyline Breach. Detached from the
 * caster: it lives in the world for its duration, pulsing arcane damage (which applies
 * the Amplified debuff), and constantly pulling enemies toward its center — a force
 * enemies can move against, not a root. All scaling (INT, Resonance damage penalty,
 * Overloaded multipliers) is precomputed by the casting ability; the zone stays dumb.
 */
public class LeylineBreachZone extends Entity {

    private final float radius;
    private final float pullForce;
    private final float pulseDamage;
    private final float duration;
    private final boolean overloaded;
    private float pulseTimer;

    public LeylineBreachZone(World world, Vector3f position, float radius, float pullForce,
                             float pulseDamage, float duration, boolean overloaded) {
        super(world, position);
        this.radius = radius;
        this.pullForce = pullForce;
        this.pulseDamage = pulseDamage;
        this.duration = duration;
        this.overloaded = overloaded;
        this.width = 0.4f;
        this.height = 0.4f;
        this.length = 0.4f;
        // Render as a flat slab covering the zone footprint
        this.scale.set(radius * 2f, 0.15f, radius * 2f);
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;

        // Keep velocity zeroed so EntityManager's physics step is a no-op (stationary zone)
        velocity.set(0, 0, 0);
        onGround = true;

        if (age > duration) {
            alive = false;
            return;
        }

        EntityManager em = Game.getEntityManager();
        if (em == null) return;

        boolean pulse = false;
        pulseTimer += deltaTime;
        if (pulseTimer >= LEYLINE_BREACH_PULSE_INTERVAL) {
            pulseTimer -= LEYLINE_BREACH_PULSE_INTERVAL;
            pulse = true;
        }

        for (LivingEntity entity : em.getLivingEntities()) {
            if (!entity.isAlive()) continue;
            if (entity.getPosition().distance(position) > radius) continue;

            // Constant inward force, scaled by dt — a pull, not a root
            Vector3f toCenter = new Vector3f(position).sub(entity.getPosition());
            entity.applyKnockback(toCenter, pullForce * deltaTime, 0f);

            if (pulse) {
                // Damage before applying Amplified: the first pulse is unamplified, later
                // pulses benefit (2.5s debuff > 1.2s interval keeps it refreshed in-zone).
                entity.damage(pulseDamage, LivingEntity.DamageSource.ARCANE);
                entity.applyStatusEffect(StatusEffectType.AMPLIFIED,
                    LEYLINE_BREACH_AMPLIFY_DURATION, LEYLINE_BREACH_AMPLIFY_BONUS);
            }
        }
    }

    public float getRadius() { return radius; }

    public float getDuration() { return duration; }

    public boolean isOverloaded() { return overloaded; }

    /** 0..1 fraction of the zone's lifetime remaining. */
    public float getRemainingFraction() {
        return Math.max(0f, 1f - age / duration);
    }

    @Override
    public boolean isSelfPropelled() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering is handled externally by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.LEYLINE_BREACH_ZONE;
    }
}
