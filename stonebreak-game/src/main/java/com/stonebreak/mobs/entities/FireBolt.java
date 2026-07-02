package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.effects.FireTrailParticles;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Projectile entity fired from the player when right-clicking with a Staff.
 * Travels in a straight line, damages living entities on contact, and
 * despawns on block collision or after its TTL expires.
 */
public class FireBolt extends Entity {

    private static final float BOLT_SPEED = 25.0f;
    private static final float MAX_AGE    = 5.0f;
    private static final float DAMAGE     = 8.0f;
    private static final float HIT_RADIUS = 0.5f;
    // Largest distance the bolt is allowed to advance between collision samples.
    // Sub-stepping the per-frame move at this granularity stops fast bolts from
    // tunnelling through thin blocks or skipping past mobs at low frame rates.
    private static final float COLLISION_STEP = 0.25f;

    private final Vector3f direction;
    // Once true the bolt has hit something and stops moving; it stays alive only
    // long enough for its trail/impact particles to fade out before despawning.
    private boolean impacted = false;
    public final FireTrailParticles particles = new FireTrailParticles();

    public FireBolt(World world, Vector3f position, Vector3f direction) {
        super(world, position);
        this.direction = new Vector3f(direction).normalize();
        this.width  = 0.3f;
        this.height = 0.3f;
        this.length = 0.3f;
        // Face the travel direction
        this.rotation.y = (float) Math.toDegrees(Math.atan2(-this.direction.x, -this.direction.z));
        // Scale the visual cube to match bounding box
        this.scale.set(0.3f, 0.3f, 0.3f);
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;

        // Keep velocity zeroed so EntityManager's physics step is a no-op
        velocity.set(0, 0, 0);
        onGround = false;

        // After impact the bolt no longer moves — just let the particles drain,
        // then despawn once they are all gone (or the TTL expires as a backstop).
        if (impacted) {
            particles.update(deltaTime);
            if (particles.isEmpty() || age > MAX_AGE + 2.0f) {
                alive = false;
            }
            return;
        }

        if (age > MAX_AGE) {
            impact();
            return;
        }

        // Advance the bolt in small sub-steps so collision is checked along the
        // whole travel segment rather than only at the destination point.
        float remaining = BOLT_SPEED * deltaTime;
        EntityManager em = world.getEntityManager(); // server-spawned: scan the OWNING world
        if (em == null) {
            em = Game.getEntityManager();
        }
        while (remaining > 0.0f && !impacted) {
            float stepLen = Math.min(COLLISION_STEP, remaining);
            remaining -= stepLen;

            Vector3f newPos = new Vector3f(direction).mul(stepLen).add(position);

            // Block collision at the leading sample point.
            BlockType block = world.getBlockAt(
                    (int) Math.floor(newPos.x),
                    (int) Math.floor(newPos.y),
                    (int) Math.floor(newPos.z));
            if (block != null && block != BlockType.AIR) {
                impact();
                return;
            }

            position.set(newPos);

            // Entity collision — damage the first living entity in range.
            if (em != null) {
                for (Entity e : em.getEntitiesInRange(position, HIT_RADIUS)) {
                    if (e == this) continue;
                    if (e instanceof LivingEntity le) {
                        ProjectileDamage.deal(this, le, DAMAGE, LivingEntity.DamageSource.FIRE);
                        impact();
                        return;
                    }
                }
            }
        }

        // Spawn fire trail particles behind the bolt
        Vector3f backDir = new Vector3f(direction).negate();
        particles.spawn(position, backDir);
        particles.update(deltaTime);
    }

    /**
     * Stops the bolt and emits an impact burst. The entity remains alive until
     * its particles fade so the trail does not pop out of existence.
     */
    private void impact() {
        if (impacted) return;
        impacted = true;
        particles.burst(position);
    }

    /** Whether the bolt has struck something and is now just fading out. */
    public boolean isImpacted() {
        return impacted;
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
        return EntityType.FIRE_BOLT;
    }
}
