package com.stonebreak.mobs.entities;

import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_BURST_RADIUS;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_MAX_RANGE;
import static com.stonebreak.player.PlayerConstants.NULL_SPIKE_PROJECTILE_SPEED;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * Projectile spawned by the Arcanist's Null Spike. Travels in a straight line and
 * Spellmarks what it damages. Normal casts stop on the first living entity hit;
 * Overloaded casts set the {@code pierce} flag instead — the spike passes through every
 * enemy in its line and detonates in a radial arcane burst where it ends (terrain hit
 * or max range). Damage is fully precomputed at fire time (INT, Resonance stacks,
 * same-school penalty) by the casting ability.
 */
public class NullSpikeProjectile extends Entity {

    private static final float MAX_AGE = 5.0f;
    private static final float HIT_RADIUS = 0.5f;
    // Sub-step granularity for collision sampling, mirroring FireBolt: stops fast
    // projectiles from tunnelling through thin blocks or skipping mobs.
    private static final float COLLISION_STEP = 0.25f;

    private final Vector3f direction;
    private final float damagePerHit;
    private final float spellmarkDuration;
    private final boolean pierce;
    private final float burstDamage;
    private final Set<LivingEntity> hitEntities = new HashSet<>();
    private float distanceTraveled;

    public NullSpikeProjectile(World world, Vector3f position, Vector3f direction,
                               float damagePerHit, float spellmarkDuration,
                               boolean pierce, float burstDamage) {
        super(world, position);
        this.direction = new Vector3f(direction).normalize();
        this.damagePerHit = damagePerHit;
        this.spellmarkDuration = spellmarkDuration;
        this.pierce = pierce;
        this.burstDamage = burstDamage;
        this.width = 0.25f;
        this.height = 0.25f;
        this.length = 0.25f;
        this.rotation.y = (float) Math.toDegrees(Math.atan2(-this.direction.x, -this.direction.z));
        this.scale.set(0.25f, 0.25f, 0.25f);
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;

        // Keep velocity zeroed so EntityManager's physics step is a no-op
        velocity.set(0, 0, 0);
        onGround = false;

        if (age > MAX_AGE) {
            endFlight();
            return;
        }

        // Advance in small sub-steps so collision is checked along the whole travel
        // segment rather than only at the destination point.
        float remaining = NULL_SPIKE_PROJECTILE_SPEED * deltaTime;
        EntityManager em = Game.getEntityManager();
        while (remaining > 0.0f && alive) {
            float stepLen = Math.min(COLLISION_STEP, remaining);
            remaining -= stepLen;

            Vector3f newPos = new Vector3f(direction).mul(stepLen).add(position);

            // Block collision at the leading sample point.
            BlockType block = world.getBlockAt(
                    (int) Math.floor(newPos.x),
                    (int) Math.floor(newPos.y),
                    (int) Math.floor(newPos.z));
            if (block != null && block != BlockType.AIR) {
                endFlight();
                return;
            }

            position.set(newPos);
            distanceTraveled += stepLen;
            if (distanceTraveled >= NULL_SPIKE_MAX_RANGE) {
                endFlight();
                return;
            }

            if (em == null) continue;
            for (Entity e : em.getEntitiesInRange(position, HIT_RADIUS)) {
                if (e == this) continue;
                if (!(e instanceof LivingEntity le) || hitEntities.contains(le)) continue;

                // Damage before marking, so this hit cannot consume its own Spellmark.
                le.damage(damagePerHit, LivingEntity.DamageSource.ARCANE);
                le.applyStatusEffect(StatusEffectType.SPELLMARKED, spellmarkDuration, 0f);
                hitEntities.add(le);

                if (!pierce) {
                    alive = false;
                    return;
                }
            }
        }
    }

    /**
     * Ends the flight at the current position: Overloaded spikes detonate a radial arcane
     * burst, sparing entities the spike already pierced (they took full damage, and the
     * 0.5s i-frame window would swallow the burst anyway).
     */
    private void endFlight() {
        alive = false;
        if (!pierce) return;

        EntityManager em = Game.getEntityManager();
        if (em == null) return;
        for (Entity e : em.getEntitiesInRange(position, NULL_SPIKE_BURST_RADIUS)) {
            if (!(e instanceof LivingEntity le) || hitEntities.contains(le)) continue;
            le.damage(burstDamage, LivingEntity.DamageSource.ARCANE);
        }
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
        return EntityType.NULL_SPIKE;
    }
}
