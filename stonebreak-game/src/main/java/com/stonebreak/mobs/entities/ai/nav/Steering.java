package com.stonebreak.mobs.entities.ai.nav;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityWaterPhysics;
import com.stonebreak.mobs.entities.LivingEntity;
import org.joml.Vector3f;

/**
 * Turns an intended heading into actual movement: rotation toward the travel direction, horizontal
 * velocity, and the hop that gets a body over a ledge.
 *
 * <p>Steering is separate from routing on purpose. A route says which way to go; this says how the
 * body gets there, and plenty of behaviour needs one without the other — fleeing straight away from
 * a threat, paddling across water, turning to face something while standing still.
 *
 * <p>Facing always goes through {@code EntityType.getModelYawOffsetDegrees()}, so a mob faces its
 * travel direction whichever way its SBE model was authored (cow and sheep face −Z, chicken and
 * goose face +Z).
 *
 * <p>The obstacle probe survives from the old navigator and still earns its place: it checks a
 * single block column just past the bounding box's leading edge — a whole-box query over-reports by
 * up to a block and triggers early jumps — and it handles the obstacles a route never predicted,
 * such as another mob's shove pushing this one into a wall.
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
    private final float hopBoostSpeed;      // <= 0 disables the airborne hop boost
    private final float hopDurationSeconds; // safety cap; cleared on landing

    private float jumpCooldownTimer;
    private float hopTimer; // > 0 while mid-hop over an obstacle

    public Steering(LivingEntity entity, float rotationSpeedDegPerSec,
                    float hopBoostSpeed, float hopDurationSeconds) {
        this.entity = entity;
        this.rotationSpeedDegPerSec = rotationSpeedDegPerSec;
        this.hopBoostSpeed = hopBoostSpeed;
        this.hopDurationSeconds = hopDurationSeconds;
    }

    /** Advances cooldowns; call once per tick before issuing movement. */
    public void tick(float deltaTime) {
        jumpCooldownTimer = Math.max(0.0f, jumpCooldownTimer - deltaTime);
        // The hop boost ends as soon as the mob lands (or after the safety cap): airborne entities
        // cannot auto-step, so the boost only matters mid-air.
        if (entity.isOnGround()) {
            hopTimer = 0.0f;
        } else {
            hopTimer = Math.max(0.0f, hopTimer - deltaTime);
        }
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
        rotateTowardYaw(yawFor(direction), rotationSpeedDegPerSec * deltaTime);

        if (allowJump && shouldJumpObstacle(direction)) {
            requestJump();
        }

        float speed = (hopTimer > 0.0f)
                ? hopBoostSpeed
                : entity.getMoveSpeed() * speedMultiplier * entity.getMoveSpeedMultiplier();
        // Water resists. Applied here rather than as drag in the physics step because horizontal
        // velocity is set outright every tick — damping it afterwards would just be overwritten.
        if (entity.isInWater()) {
            speed *= EntityWaterPhysics.speedFactor(entity.getSubmersion());
        }
        Vector3f velocity = entity.getVelocity();
        velocity.x = direction.x * speed;
        velocity.z = direction.z * speed;
        entity.setVelocity(velocity);
    }

    /**
     * Gets the mob upward: a jump on land, a swim stroke in water.
     *
     * <p>Routes call this when the next waypoint is a ledge the auto-step cannot reach, and the
     * obstacle probe calls it when something is in the way; sharing one cooldown is what stops the
     * two firing as a double hop.
     *
     * <p>The water case is the one that lets a mob leave a lake. A ground jump needs ground to
     * push off, so a floating mob asking to climb a bank used to get nothing at all and simply
     * pressed against the shore until something else moved it.
     *
     * @return whether the mob actually pushed upward
     */
    public boolean requestJump() {
        if (jumpCooldownTimer > 0.0f) {
            return false;
        }
        if (entity.isInWater()) {
            entity.swimUp();
            jumpCooldownTimer = SWIM_STROKE_COOLDOWN_SECONDS;
            return true;
        }
        if (!entity.isOnGround()) {
            return false;
        }
        entity.jump();
        jumpCooldownTimer = JUMP_COOLDOWN_SECONDS;
        if (hopBoostSpeed > 0) {
            hopTimer = hopDurationSeconds;
        }
        return true;
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
        rotateTowardYaw(yawFor(direction), turnSpeedDegPerSec * deltaTime);
    }

    /** World yaw (degrees) that points the entity's model along {@code direction}. */
    private float yawFor(Vector3f direction) {
        return (float) Math.toDegrees(Math.atan2(direction.x, direction.z))
                + entity.getType().getModelYawOffsetDegrees();
    }

    /** Steps the entity's yaw toward {@code targetYaw} along the shortest arc, capped at {@code maxStep}. */
    private void rotateTowardYaw(float targetYaw, float maxStep) {
        Vector3f rotation = entity.getRotation();
        float deltaYaw = targetYaw - rotation.y;
        while (deltaYaw > 180.0f) deltaYaw -= 360.0f;
        while (deltaYaw < -180.0f) deltaYaw += 360.0f;

        if (Math.abs(deltaYaw) > maxStep) {
            deltaYaw = Math.signum(deltaYaw) * maxStep;
        }
        entity.setRotation(new Vector3f(rotation.x, rotation.y + deltaYaw, rotation.z));
    }

    /**
     * Whether a one-block hop would clear the obstacle directly ahead: a solid block at foot level
     * in the column just past the bounding-box leading edge, with two clear blocks above it.
     *
     * <p>Sampled from the mob's <b>feet</b>, not its origin. A mob's origin sits a leg-length above
     * its feet, so probing there reads a block too high the moment the two fall in different cells
     * — which is exactly what happens to a mob floating at a water's edge, where the bank it is
     * pressed against went undetected and it simply never tried to climb.
     */
    private boolean shouldJumpObstacle(Vector3f direction) {
        // In water the mob is airborne by every other measure, but it can still stroke upward —
        // and the obstacle ahead is usually the bank it is trying to climb out onto.
        if ((!entity.isOnGround() && !entity.isInWater())
                || jumpCooldownTimer > 0.0f || entity.getWorld() == null) {
            return false;
        }
        Vector3f pos = entity.getPosition();

        float reach = Math.abs(direction.x) * (entity.getWidth() * 0.5f)
                + Math.abs(direction.z) * (entity.getLength() * 0.5f)
                + OBSTACLE_LOOK_AHEAD;

        int blockX = (int) Math.floor(pos.x + direction.x * reach);
        int blockZ = (int) Math.floor(pos.z + direction.z * reach);
        float feetY = pos.y - entity.getLegHeight();
        int blockY = (int) Math.floor(feetY + FOOT_PROBE_LIFT);

        if (entity.isInWater()) {
            return shouldStrokeOverShore(blockX, blockY, blockZ, feetY);
        }

        BlockType ahead = entity.getWorld().getBlockAt(blockX, blockY, blockZ);
        if (ahead == null || !ahead.isSolid()) {
            return false;
        }
        BlockType above1 = entity.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
        BlockType above2 = entity.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
        return (above1 == null || !above1.isSolid())
                && (above2 == null || !above2.isSolid());
    }

    /**
     * Whether a swim stroke would put the mob on top of the shore ahead.
     *
     * <p>The land rule — one solid block with two clear above — asks the wrong question of a
     * floating mob. It measures the obstacle from where the mob's feet happen to be dangling, so a
     * shore standing two cells above sunk feet reads as an unclimbable wall even when its top is
     * comfortably within a stroke of the water's surface. That is the bug where a mob simply swam
     * at the bank forever, rising as far as it could and pushing forward, never once trying to
     * jump.
     *
     * <p>So measure the whole shore instead: find the top of the solid stack ahead and ask whether
     * a stroke reaches it with room for the body above.
     */
    private boolean shouldStrokeOverShore(int blockX, int blockY, int blockZ, float feetY) {
        int top = blockY;
        boolean foundSolid = false;
        int limit = blockY + MAX_SHORE_PROBE_CELLS;
        for (int y = blockY; y <= limit; y++) {
            BlockType block = entity.getWorld().getBlockAt(blockX, y, blockZ);
            if (block != null && block.isSolid()) {
                foundSolid = true;
                top = y;
            } else if (foundSolid) {
                break; // cleared the stack
            }
        }
        if (!foundSolid) {
            return false;
        }

        float shoreSurface = top + 1.0f;
        if (shoreSurface - feetY > entity.getSwimStrokeReach()) {
            return false; // genuinely too high to climb out here
        }

        // Room to stand once up there.
        BlockType above1 = entity.getWorld().getBlockAt(blockX, top + 1, blockZ);
        BlockType above2 = entity.getWorld().getBlockAt(blockX, top + 2, blockZ);
        return (above1 == null || !above1.isSolid())
                && (above2 == null || !above2.isSolid());
    }
}
