package com.stonebreak.mobs.entities;

import com.openmason.engine.physics.FluidSample;
import com.openmason.engine.physics.PhysicsBody;
import com.openmason.engine.physics.PhysicsIntegrator;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;
import org.joml.Vector3f;

/**
 * Entities in the engine physics ({@link WorldPhysics}, OpenMason #117): world collision, gravity,
 * water and ground friction for every non-drop entity, plus entity-entity separation.
 *
 * <p>An entity's body is its box: {@code width} x {@code length} around its position, from its feet
 * ({@code position.y - legHeight} for a living entity) to {@code position.y + height}. It slides
 * along walls (horizontal velocity is kept; steering re-sets it every tick), climbs anything up to
 * half a block while standing, lands, and leaves the ground after walking off an edge — one swept
 * move, so nothing tunnels at any frame time. Door cells are blocks to mobs.
 *
 * <p>In water the body floats (buoyancy toward {@link EntityWaterPhysics#FLOAT_SUBMERSION}, capped
 * drift, vertical drag) and drifts with the current; dry and airborne it falls under
 * {@link Entity#GRAVITY}; standing it slows with ground friction.
 */
public class EntityCollision {

    /** Mobs climb anything up to half a block (cows step lower than players). */
    private static final float MOB_STEP = 0.5f;
    private static final float GROUND_FRICTION = 5.0f;

    /** Gravity, ground friction and water for every simulated entity. */
    public static final PhysicsIntegrator INTEGRATOR = new PhysicsIntegrator(Entity.GRAVITY, GROUND_FRICTION,
            EntityWaterPhysics.FLOAT_SUBMERSION, EntityWaterPhysics.BUOYANCY_DRIFT_SPEED,
            EntityWaterPhysics.VERTICAL_DRAG, WorldPhysics.WATER);

    private final World world;
    private final PhysicsBody body = new PhysicsBody();
    private final FluidSample sample = new FluidSample();

    /**
     * Creates a new collision handler for the specified world.
     */
    public EntityCollision(World world) {
        this.world = world;
    }

    private static float legHeight(Entity entity) {
        return entity instanceof LivingEntity living ? living.getLegHeight() : 0.0f;
    }

    /** Loads the entity into the scratch body. */
    private PhysicsBody bodyOf(Entity entity, Vector3f position) {
        body.box(entity.getWidth() / 2.0f, entity.getLength() / 2.0f, legHeight(entity), entity.getHeight())
            .steps(MOB_STEP, MOB_STEP)
            .position(position.x, position.y, position.z);
        Vector3f v = entity.getVelocity();
        body.velocity(v.x, v.y, v.z);
        body.mask = WorldPhysics.MOB_MASK;
        body.flags = PhysicsBody.KEEP_VELOCITY | (entity.isOnGround() ? PhysicsBody.ON_GROUND : 0);
        return body;
    }

    private void storeBody(Entity entity) {
        Vector3f position = entity.getPosition();
        position.set(body.x, body.y, body.z);
        entity.setPosition(position);
        Vector3f velocity = entity.getVelocity();
        velocity.set(body.vx, body.vy, body.vz);
        entity.setVelocity(velocity);
        entity.setOnGround(body.onGround());
    }

    /**
     * Checks if an entity would overlap anything it collides with at a new position.
     */
    public boolean checkWorldCollision(Entity entity, Vector3f newPosition) {
        if (entity == null || newPosition == null || world == null) {
            return false;
        }
        float halfW = entity.getWidth() / 2.0f;
        float halfL = entity.getLength() / 2.0f;
        return com.stonebreak.world.physics.WorldPhysics.of(world).overlaps(newPosition.x - halfW, newPosition.y - legHeight(entity), newPosition.z - halfL,
                newPosition.x + halfW, newPosition.y + entity.getHeight(), newPosition.z + halfL,
                WorldPhysics.MOB_MASK);
    }

    /**
     * Checks if two entities are colliding.
     */
    public boolean checkEntityCollision(Entity entity1, Entity entity2) {
        if (entity1 == entity2 || !entity1.isAlive() || !entity2.isAlive()) {
            return false;
        }
        return entity1.getBoundingBox().intersects(entity2.getBoundingBox());
    }

    /**
     * Resolves collision between two entities by pushing them apart.
     */
    public void resolveEntityCollision(Entity entity1, Entity entity2) {
        Vector3f pos1 = entity1.getPosition();
        Vector3f pos2 = entity2.getPosition();

        // Calculate separation vector
        Vector3f separation = new Vector3f(pos1).sub(pos2);
        separation.y = 0; // Only separate horizontally

        float distance = separation.length();
        if (distance < 0.1f) {
            // Entities are too close, create arbitrary separation
            separation.set((float) (Math.random() - 0.5), 0, (float) (Math.random() - 0.5));
            distance = separation.length();
        }

        // Normalize and scale separation
        separation.normalize();
        float minSeparation = (entity1.getWidth() + entity2.getWidth()) / 2.0f + 0.1f;
        separation.mul(minSeparation - distance);

        // Apply separation (each entity moves half the distance)
        Vector3f pos1New = new Vector3f(pos1).add(new Vector3f(separation).mul(0.5f));
        Vector3f pos2New = new Vector3f(pos2).sub(new Vector3f(separation).mul(0.5f));

        // Only update positions if the new positions are valid
        if (!checkWorldCollision(entity1, pos1New)) {
            entity1.setPosition(pos1New);
        }
        if (!checkWorldCollision(entity2, pos2New)) {
            entity2.setPosition(pos2New);
        }
    }

    /**
     * Checks if an entity is in water.
     */
    public boolean isInWater(Entity entity) {
        return EntityWaterPhysics.submersion(world, entity) > 0.0f;
    }

    /**
     * One physics tick for an entity: water (buoyancy and the current) or gravity, the swept move,
     * ground friction. Records the entity's submersion (measured before the move).
     */
    public void applyEntityPhysics(Entity entity, float deltaTime) {
        if (world == null) {
            return;
        }
        PhysicsBody b = bodyOf(entity, entity.getPosition());
        com.stonebreak.world.physics.WorldPhysics.of(world).step(b, deltaTime, INTEGRATOR, sample);
        entity.setSubmersion(sample.submersion);
        storeBody(entity);
    }

    /**
     * Specialized physics application for living entities.
     */
    public void applyLivingEntityPhysics(LivingEntity entity, float deltaTime) {
        applyEntityPhysics(entity, deltaTime);
    }

    /**
     * Moves an airborne, self-propelled entity (e.g. a flying goose) for one tick with the same
     * collision as {@link #applyEntityPhysics}, but with NO gravity, friction or water — the caller's
     * AI owns all velocity. Walls slide (horizontal velocity is preserved); a vertical hit zeroes the
     * vertical velocity and a landing sets onGround.
     *
     * @return {@code {blockedHorizontal, blockedVertical}} so the AI can react to a wall/ceiling.
     */
    public boolean[] moveAirborneWithCollision(LivingEntity entity, float deltaTime) {
        if (world == null) {
            return new boolean[] {false, false};
        }
        PhysicsBody b = bodyOf(entity, entity.getPosition());
        com.stonebreak.world.physics.WorldPhysics.of(world).move(b, deltaTime);
        storeBody(entity);
        boolean blockedH = b.hasContact(PhysicsBody.CONTACT_HORIZONTAL);
        boolean blockedV = b.hasContact(PhysicsBody.CONTACT_GROUND | PhysicsBody.CONTACT_CEILING);
        return new boolean[] {blockedH, blockedV};
    }
}
