package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.waterSystem.WaterSubmersion;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * What water does to any entity in it: it holds them up, slows them down, and carries them along.
 *
 * <p>Applies to every entity generically rather than to particular mobs. Whether a creature is a
 * strong swimmer is a matter of how fast it moves and where it chooses to go — both handled
 * elsewhere — not of whether the water pushes back on it.
 *
 * <p>The rule that matters is <b>buoyancy</b>. Reduced gravity is not buoyancy: it still points
 * down, so anything that fell in kept sinking, only slower, and ended up walking the bottom of the
 * lake with no way back. Here a submerged body accelerates <em>upward</em> toward a gentle drift
 * until it is floating at the surface, which is also what puts it at the height where it can climb
 * out onto a bank.
 *
 * <p>Only vertical motion is damped here. Horizontal speed in water is a steering concern
 * ({@code Steering} scales it by submersion), because steering re-sets horizontal velocity outright
 * every tick — damping it here as well would either be silently undone or double-applied depending
 * on which ran last.
 */
public final class EntityWaterPhysics {

    /** The speed a body drifts upward under buoyancy alone. A rise, not a launch. */
    static final float BUOYANCY_DRIFT_SPEED = 1.2f;

    /**
     * A body floats with roughly this fraction of itself under. Below it the water stops pushing
     * up, which is what makes a floating mob settle instead of bobbing out of the water entirely.
     */
    static final float FLOAT_SUBMERSION = 0.55f;

    /** Vertical damping in water, per second. Frame-rate independent via exp(). */
    static final float VERTICAL_DRAG = 2.2f;

    /** How much of its land speed a fully submerged body keeps. */
    private static final float SUBMERGED_SPEED_FACTOR = 0.55f;

    /**
     * Least upward speed a swim stroke may have, whatever the mob's own jump.
     *
     * <p>Sized so that any mob floating at the water line can reach a shore one block above it —
     * the commonest way out of a lake. That climb is the block itself plus the water's missing top
     * eighth plus however deep the body floats: about 1.7 blocks for the largest mob here, and
     * {@code v = sqrt(2·g·h)} with g = 40 gives ~11.6. Pushing off water beats a standing jump,
     * which is why this can exceed a mob's own jump velocity — without it, a cow (jump 10.5, apex
     * 1.38) is permanently trapped by a shore a chicken (jump 12) hops out over.
     */
    public static final float MIN_ESCAPE_STROKE = 12.0f;

    private EntityWaterPhysics() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * How much of {@code entity} is under water, 0..1.
     *
     * <p>Measured from the entity's feet, which for a living entity sit a leg-length below its
     * origin — the reason the old single-point check kept sampling the wrong block for tall mobs.
     */
    public static float submersion(World world, Entity entity) {
        Vector3f position = entity.getPosition();
        float feetY = position.y;
        float height = entity.getHeight();
        if (entity instanceof LivingEntity living) {
            feetY -= living.getLegHeight();
            height += living.getLegHeight();
        }
        return WaterSubmersion.fractionAt(world, position.x, feetY, position.z, height);
    }

    /**
     * One tick of vertical water forces, as a pure function of the current vertical speed and how
     * deep the body is: weight, buoyancy and drag.
     *
     * <p>Separated from the world so the rule that matters can be stated and checked directly. The
     * property to preserve is that a submerged body ends up moving <em>upward</em> — the previous
     * version merely weakened gravity, which is still gravity, and everything that fell in a lake
     * walked its floor forever.
     */
    public static float verticalVelocityAfter(float velocityY, float submersion, float deltaTime) {
        // The rule lives in the engine (cp_buoyancy_vy, OpenMason #117) with these constants as
        // its parameters: weight and buoyancy as one term, Entity.GRAVITY * (1 - submersion /
        // FLOAT_SUBMERSION), so the equilibrium is exactly FLOAT_SUBMERSION by construction and the
        // expression reduces to real gravity as the body leaves the water (modelling it as weakened
        // gravity plus a lift force made mobs float 87% under instead of 55%); buoyancy alone tops
        // out at the drift speed (a rise, not a launch — a deliberate stroke is already faster and is
        // never damped by the cap); then vertical drag scaled by submersion.
        return com.openmason.engine.physics.PhysicsWorld.buoyancyVy(velocityY, submersion, deltaTime,
                EntityCollision.INTEGRATOR);
    }

    /**
     * How much of its normal speed a body moves at, given how deep it is. Used by steering so the
     * penalty is applied once, where horizontal velocity is decided.
     */
    public static float speedFactor(float submersion) {
        return 1.0f - (1.0f - SUBMERGED_SPEED_FACTOR) * Math.min(1.0f, Math.max(0.0f, submersion));
    }
}
