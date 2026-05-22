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

    private final Vector3f direction;
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

        if (age > MAX_AGE) {
            alive = false;
            return;
        }

        // Move in a straight line
        Vector3f step = new Vector3f(direction).mul(BOLT_SPEED * deltaTime);
        Vector3f newPos = new Vector3f(position).add(step);

        // Block collision — check the block at the leading edge of the bolt
        int bx = (int) Math.floor(newPos.x);
        int by = (int) Math.floor(newPos.y);
        int bz = (int) Math.floor(newPos.z);
        BlockType block = world.getBlockAt(bx, by, bz);
        if (block != null && block != BlockType.AIR) {
            alive = false;
            return;
        }

        position.set(newPos);

        // Entity collision — damage first living entity in range
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            for (Entity e : em.getEntitiesInRange(position, HIT_RADIUS)) {
                if (e == this) continue;
                if (e instanceof LivingEntity le) {
                    le.damage(DAMAGE, LivingEntity.DamageSource.FIRE);
                    alive = false;
                    return;
                }
            }
        }

        // Spawn fire trail particles behind the bolt
        Vector3f backDir = new Vector3f(direction).negate();
        particles.spawn(position, backDir);
        particles.update(deltaTime);

        // Keep velocity zeroed so EntityManager's physics step is a no-op
        velocity.set(0, 0, 0);
        onGround = false;
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
