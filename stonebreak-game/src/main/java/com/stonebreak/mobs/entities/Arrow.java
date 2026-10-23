package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Arrow projectile fired by the player when drawing and releasing the bow.
 * Unlike FireBolt, arrows are subject to standard gravity physics applied by EntityManager.
 * Speed (and thus range) scales with how long the bow was drawn.
 */
public class Arrow extends Entity {

    private static final float MAX_AGE    = 10.0f;
    private static final float BASE_DAMAGE = 5.0f;
    private static final float MAX_DAMAGE  = 15.0f;
    private static final float HIT_RADIUS  = 0.4f;

    private final float launchSpeed;

    public Arrow(World world, Vector3f position, Vector3f velocity) {
        super(world, position);
        this.launchSpeed = velocity.length();
        this.velocity.set(velocity);
        this.width  = 0.05f;
        this.height = 0.05f;
        this.length = 0.5f;
        // Face the travel direction (yaw from xz, no pitch stored — visual only)
        this.rotation.y = (float) Math.toDegrees(Math.atan2(-velocity.x, -velocity.z));
        this.scale.set(0.15f, 0.15f, 0.5f);
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;
        if (age > MAX_AGE) {
            alive = false;
            return;
        }

        // Block collision — check leading edge in travel direction
        Vector3f front = new Vector3f(velocity).normalize().mul(length * 0.5f);
        Vector3f tip = new Vector3f(position).add(front);
        int bx = (int) Math.floor(tip.x);
        int by = (int) Math.floor(tip.y);
        int bz = (int) Math.floor(tip.z);
        BlockType block = world.getBlockAt(bx, by, bz);
        if (block != null && block != BlockType.AIR) {
            alive = false;
            return;
        }

        // Entity collision — damage the first living entity struck. Use the OWNING world's
        // manager: server-spawned arrows must scan the server's entities (the Game singleton
        // resolves to the client render manager in the two-world model).
        EntityManager em = OwningEntityManager.of(this);
        if (em != null) {
            for (Entity e : em.getEntitiesInRange(position, HIT_RADIUS)) {
                if (e == this) continue;
                if (e instanceof LivingEntity le) {
                    float speedRatio = launchSpeed > 0 ? Math.min(launchSpeed / 45.0f, 1.0f) : 0.0f;
                    float damage = BASE_DAMAGE + (MAX_DAMAGE - BASE_DAMAGE) * speedRatio;
                    // The hit result goes to the shooter (KillCreditS2C with the target's
                    // network id and ARROW source); their client builds Quarry/Study from it.
                    ProjectileDamage.deal(this, le, damage, LivingEntity.DamageSource.ARROW);
                    alive = false;
                    return;
                }
            }
        }
        // Counter-act most of the engine gravity so arrows arc gently rather than
        // dropping steeply. Entity.GRAVITY = -40; net effective gravity ≈ -10.
        velocity.y += 30.0f * deltaTime;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering is handled externally by EntityRenderer
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public EntityType getType() {
        return EntityType.ARROW;
    }
}
