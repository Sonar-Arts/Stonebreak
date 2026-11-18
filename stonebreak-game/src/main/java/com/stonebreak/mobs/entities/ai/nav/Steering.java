package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.AgentApply;
import com.openmason.engine.nav.AgentBody;
import com.openmason.engine.nav.NavSteering;
import com.openmason.engine.nav.Steer;
import com.openmason.engine.nav.SteeringDesc;
import com.openmason.engine.physics.PhysicsWorld;
import com.stonebreak.mobs.entities.EntityWaterPhysics;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;
import com.stonebreak.mobs.entities.PhysicsFrame;
import org.joml.Vector3f;

/**
 * Turns an intended heading into actual movement: rotation toward the travel direction, horizontal
 * velocity, and the hop that gets a body over a ledge.
 *
 * <p>Steering is separate from routing on purpose. A route says which way to go; this says how the
 * body gets there, and plenty of behaviour needs one without the other — fleeing straight away from
 * a threat, paddling across water, turning to face something while standing still.
 *
 * <p>The rules are the engine's ({@link NavSteering}, Open Mason {@code cn_steering}, OpenMason #116):
 * the yaw turn, the obstacle probe (one cell column just past the bounding box's leading edge, a full
 * cell with two clear above, read from the physics cache), the shore probe from water, and the one
 * jump/stroke cooldown. This class is the game's side: Stonebreak's numbers, the mob's body as the
 * engine sees it each call, and applying what the engine decided.
 *
 * <p>Facing always goes through {@code EntityType.getModelYawOffsetDegrees()}, so a mob faces its
 * travel direction whichever way its SBE model was authored (cow and sheep face −Z, chicken and
 * goose face +Z).
 */
public final class Steering {

    private static final float JUMP_COOLDOWN_SECONDS = 1.0f;

    /** Swim strokes repeat far faster than jumps — climbing a bank takes several. */
    private static final float SWIM_STROKE_COOLDOWN_SECONDS = 0.35f;

    /** Probe gap past the leading edge. */
    private static final float OBSTACLE_LOOK_AHEAD = 0.15f;

    /**
     * Nudge above the feet for the obstacle probe, so a mob resting exactly on a block boundary
     * reads the cell it is standing in rather than the one it is standing on.
     */
    private static final float FOOT_PROBE_LIFT = 0.1f;

    /** How far up a shore is measured before giving up on climbing out over it. */
    private static final int MAX_SHORE_PROBE_CELLS = 3;

    private final LivingEntity entity;
    private final float rotationSpeedDegPerSec;
    private final float hopBoostSpeed;
    private final float hopDurationSeconds;
    private final float modelYawOffset;
    private final NavSteering engine;

    private final AgentBody body = new AgentBody();
    private final AgentApply apply = new AgentApply();

    public Steering(LivingEntity entity, float rotationSpeedDegPerSec,
                    float hopBoostSpeed, float hopDurationSeconds) {
        this.entity = entity;
        this.rotationSpeedDegPerSec = rotationSpeedDegPerSec;
        this.hopBoostSpeed = hopBoostSpeed;
        this.hopDurationSeconds = hopDurationSeconds;
        this.modelYawOffset = entity.getType().getModelYawOffsetDegrees();
        this.engine = NavSteering.create(new SteeringDesc(rotationSpeedDegPerSec, hopBoostSpeed, hopDurationSeconds,
                JUMP_COOLDOWN_SECONDS, SWIM_STROKE_COOLDOWN_SECONDS, OBSTACLE_LOOK_AHEAD, FOOT_PROBE_LIFT,
                MAX_SHORE_PROBE_CELLS, modelYawOffset, WorldPhysics.MOB_MASK));
    }

    /** The engine steering this wraps; its cooldowns are shared with the mob's {@link PathAgent}. */
    NavSteering engine() {
        return engine;
    }

    /** Advances cooldowns; call once per tick before issuing movement. */
    public void tick(float deltaTime) {
        engine.tick(entity.isOnGround(), deltaTime);
    }

    /**
     * One tick of steering along a heading: rotate toward it, optionally hop an obstacle in the
     * way, and drive horizontal velocity.
     *
     * @param direction       normalized horizontal heading
     * @param speedMultiplier scales the entity's base move speed for this behaviour
     * @param allowJump       false to suppress the obstacle hop (swimming, for one)
     */
    public void steerAlong(Vector3f direction, float speedMultiplier, float deltaTime, boolean allowJump) {
        PhysicsWorld physics = physicsOf(entity);
        engine.steerAlong(physics, bodyOf(entity, physics, body), direction.x, direction.z, speedMultiplier,
                deltaTime, allowJump, apply);
        applyTo(entity, apply);
    }

    /**
     * Gets the mob upward: a jump on land, a swim stroke in water. Routes and the obstacle probe
     * share its one cooldown, which is what stops the two firing as a double hop; the water case is
     * what lets a floating mob climb out over a bank.
     *
     * @return whether the mob actually pushed upward
     */
    public boolean requestJump() {
        boolean pushed = engine.requestJump(bodyOf(entity, physicsOf(entity), body), apply);
        applyTo(entity, apply);
        return pushed;
    }

    /** Zeroes horizontal velocity (idling, grazing, standing to look at something). */
    public void stopMoving() {
        Vector3f velocity = entity.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        entity.setVelocity(velocity);
    }

    /**
     * Turns the entity toward {@code direction} at an explicit rate without touching velocity, so
     * behaviours that move outside the ground path (flight) still take their facing from here and
     * the model-yaw-offset rule stays in exactly one place.
     */
    public void faceDirection(Vector3f direction, float turnSpeedDegPerSec, float deltaTime) {
        Vector3f rotation = entity.getRotation();
        float target = Steer.yawFor(direction.x, direction.z, modelYawOffset);
        float yaw = Steer.turn(rotation.y, target, turnSpeedDegPerSec * deltaTime);
        entity.setRotation(new Vector3f(rotation.x, yaw, rotation.z));
    }

    /**
     * How far the entity still has to turn to face {@code direction}, in degrees along the shortest
     * arc. Always non-negative — so a behaviour can wait until a mob is actually pointing somewhere
     * before acting, without re-deriving the target yaw (and its model offset) itself.
     */
    public float yawErrorTo(Vector3f direction) {
        return Steer.yawError(entity.getRotation().y, Steer.yawFor(direction.x, direction.z, modelYawOffset));
    }

    // ---- the body, both ways ---------------------------------------------------------------------

    /** The mob as the engine sees it this instant. */
    /** The entity as the engine's agent body, its position in {@code physics}'s frame. */
    static AgentBody bodyOf(LivingEntity entity, PhysicsWorld physics, AgentBody out) {
        out.x = entity.getX() - PhysicsFrame.originX(physics);
        out.y = entity.getY() - PhysicsFrame.originY(physics);
        out.z = entity.getZ() - PhysicsFrame.originZ(physics);
        out.yaw = entity.getRotation().y;
        out.legHeight = entity.getLegHeight();
        out.halfWidth = entity.getWidth() * 0.5f;
        out.halfLength = entity.getLength() * 0.5f;
        out.moveSpeed = entity.getMoveSpeed();
        out.speedMultiplier = entity.getMoveSpeedMultiplier();
        out.onGround = entity.isOnGround();
        out.inWater = entity.isInWater();
        // Water resists: applied to the steered speed (horizontal velocity is set outright each tick,
        // so drag in the physics step would just be overwritten).
        out.waterSpeedFactor = out.inWater ? EntityWaterPhysics.speedFactor(entity.getSubmersion()) : 1.0f;
        out.swimStrokeReach = entity.getSwimStrokeReach();
        return out;
    }

    /** Applies what the engine decided, in its order: yaw, jump or stroke, horizontal velocity. */
    static void applyTo(LivingEntity entity, AgentApply apply) {
        if (apply.turn()) {
            Vector3f rotation = entity.getRotation();
            entity.setRotation(new Vector3f(rotation.x, apply.yaw(), rotation.z));
        }
        if (apply.swimUp()) {
            entity.swimUp();
        }
        if (apply.jump()) {
            entity.jump();
        }
        if (apply.setVelocity()) {
            Vector3f velocity = entity.getVelocity();
            velocity.x = apply.vx();
            velocity.z = apply.vz();
            entity.setVelocity(velocity);
        }
    }

    /** Where the obstacle probe looks: the mob's world's physics, or nowhere without a world. */
    static PhysicsWorld physicsOf(LivingEntity entity) {
        World world = entity.getWorld();
        return world == null ? null : WorldPhysics.of(world).engine();
    }
}
