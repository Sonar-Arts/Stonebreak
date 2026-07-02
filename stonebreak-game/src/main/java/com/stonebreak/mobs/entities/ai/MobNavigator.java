package com.stonebreak.mobs.entities.ai;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared ground navigation for all walking mobs — the single source of truth
 * for wander-target selection, ground-level probing, steering, rotation and
 * obstacle jumping. Every mob AI drives movement through one of these instead
 * of carrying its own copy of the logic.
 *
 * <p>Rotation uses the entity type's
 * {@link com.stonebreak.mobs.entities.EntityType#getModelYawOffsetDegrees()
 * model yaw offset}, so a mob always faces its travel direction regardless of
 * which way its SBE model was authored (cow/sheep face −Z, chicken faces +Z).
 *
 * <p>The obstacle probe checks a single block column just past the entity's
 * bounding-box leading edge (precise — a whole-box query over-reports by up to
 * a block and triggers early jumps). A one-block hop is taken when the column
 * is solid at body level with two clear blocks above; taller walls are left
 * for the wander state to re-target around. Mobs with a configured hop boost
 * (chickens) get a stronger forward drive while airborne so their footprint
 * fully clears the ledge edge before descending.
 */
public final class MobNavigator {

    private static final float JUMP_COOLDOWN_SECONDS = 1.0f;
    private static final float OBSTACLE_LOOK_AHEAD = 0.15f; // probe gap past the leading edge
    private static final int WANDER_TARGET_ATTEMPTS = 10;
    private static final int MAX_PATH_POINTS = 20;

    private final LivingEntity entity;
    private final float rotationSpeedDegPerSec;
    private final float moveSpeedMultiplier;
    private final float hopBoostSpeed;      // <= 0 disables the airborne hop boost
    private final float hopDurationSeconds; // safety cap; cleared on landing

    private final Vector3f target = new Vector3f();
    private boolean hasTarget;

    private float jumpCooldownTimer;
    private float hopTimer; // > 0 while mid-hop over an obstacle

    // Debug path trail (drawn by the debug overlay).
    private final List<Vector3f> pathPoints = new ArrayList<>();

    public MobNavigator(LivingEntity entity, float rotationSpeedDegPerSec,
                        float moveSpeedMultiplier, float hopBoostSpeed, float hopDurationSeconds) {
        this.entity = entity;
        this.rotationSpeedDegPerSec = rotationSpeedDegPerSec;
        this.moveSpeedMultiplier = moveSpeedMultiplier;
        this.hopBoostSpeed = hopBoostSpeed;
        this.hopDurationSeconds = hopDurationSeconds;
    }

    /** Advances cooldowns; call once per AI tick before issuing movement. */
    public void tick(float deltaTime) {
        jumpCooldownTimer = Math.max(0.0f, jumpCooldownTimer - deltaTime);
        // The hop boost ends as soon as the mob lands (or after the safety cap):
        // airborne entities cannot auto-step, so the boost only matters mid-air.
        if (entity.isOnGround()) {
            hopTimer = 0.0f;
        } else {
            hopTimer = Math.max(0.0f, hopTimer - deltaTime);
        }
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    public boolean hasTarget() { return hasTarget; }

    /** A copy of the current target, or null when none is set. */
    public Vector3f getTarget() { return hasTarget ? new Vector3f(target) : null; }

    public void setTarget(Vector3f newTarget) {
        this.target.set(newTarget);
        this.hasTarget = true;
    }

    public void clearTarget() {
        this.hasTarget = false;
    }

    /** True when the entity is within {@code radius} blocks of its target. */
    public boolean reachedTarget(float radius) {
        return hasTarget && entity.distanceTo(target) < radius;
    }

    /**
     * Picks a random walkable block between {@code minDistance} and
     * {@code maxDistance} away, snapped to the block centre with the ground
     * level resolved. Returns false (clearing the target) when no valid spot
     * is found after several attempts.
     */
    public boolean pickWanderTarget(float minDistance, float maxDistance) {
        Vector3f pos = entity.getPosition();
        for (int attempt = 0; attempt < WANDER_TARGET_ATTEMPTS; attempt++) {
            float angle = (float) (Math.random() * 2 * Math.PI);
            float distance = minDistance + (float) (Math.random() * (maxDistance - minDistance));

            float centerX = (float) Math.floor(pos.x + (float) Math.cos(angle) * distance) + 0.5f;
            float centerZ = (float) Math.floor(pos.z + (float) Math.sin(angle) * distance) + 0.5f;

            float groundY = findGroundLevel(centerX, centerZ, pos.y);
            if (groundY != Float.NEGATIVE_INFINITY) {
                target.set(centerX, groundY, centerZ);
                hasTarget = true;
                return true;
            }
        }
        hasTarget = false;
        return false;
    }

    /**
     * Sets a target {@code distance} blocks directly away from {@code awayFrom}
     * (a flee response). Returns false when no walkable ground exists there.
     */
    public boolean pickFleeTarget(Vector3f awayFrom, float distance) {
        Vector3f pos = entity.getPosition();
        Vector3f fleeDir = new Vector3f(pos).sub(awayFrom);
        fleeDir.y = 0;
        if (fleeDir.length() <= 0.1f) {
            return false;
        }
        fleeDir.normalize();
        float targetX = pos.x + fleeDir.x * distance;
        float targetZ = pos.z + fleeDir.z * distance;
        float groundY = findGroundLevel(targetX, targetZ, pos.y);
        if (groundY == Float.NEGATIVE_INFINITY) {
            return false;
        }
        setTarget(new Vector3f(targetX, groundY, targetZ));
        return true;
    }

    /**
     * The Y of the first standable surface (solid block with air above) in the
     * column at (x, z), scanning from {@code startY + 5} down to
     * {@code startY - 10}; {@link Float#NEGATIVE_INFINITY} when none exists.
     */
    public float findGroundLevel(float x, float z, float startY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int startBlockY = (int) Math.floor(startY);

        for (int y = startBlockY + 5; y >= startBlockY - 10; y--) {
            BlockType block = entity.getWorld().getBlockAt(blockX, y, blockZ);
            BlockType blockAbove = entity.getWorld().getBlockAt(blockX, y + 1, blockZ);
            if (block != null && block.isSolid()
                    && (blockAbove == null || !blockAbove.isSolid())) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    // ── Steering ──────────────────────────────────────────────────────────────

    /**
     * One tick of steering toward the current target: rotate toward the travel
     * direction, hop obstacles, and drive horizontal velocity. No-op without a
     * target or when already essentially on top of it.
     */
    public void moveTowardTarget(float deltaTime) {
        if (!hasTarget) return;

        Vector3f direction = new Vector3f(target).sub(entity.getPosition());
        direction.y = 0;
        if (direction.length() <= 0.1f) return;
        direction.normalize();

        rotateToward(yawFor(direction), deltaTime);

        if (entity.isOnGround() && jumpCooldownTimer <= 0 && shouldJumpObstacle(direction)) {
            entity.jump();
            jumpCooldownTimer = JUMP_COOLDOWN_SECONDS;
            if (hopBoostSpeed > 0) {
                hopTimer = hopDurationSeconds;
            }
        }

        float speed = (hopTimer > 0.0f)
                ? hopBoostSpeed
                : entity.getMoveSpeed() * moveSpeedMultiplier * entity.getMoveSpeedMultiplier();
        Vector3f velocity = entity.getVelocity();
        velocity.x = direction.x * speed;
        velocity.z = direction.z * speed;
        entity.setVelocity(velocity);
    }

    /** Zeroes horizontal velocity (idle/grazing). */
    public void stopMoving() {
        Vector3f velocity = entity.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        entity.setVelocity(velocity);
    }

    /** World yaw (degrees) that points the entity's model along {@code direction}. */
    private float yawFor(Vector3f direction) {
        return (float) Math.toDegrees(Math.atan2(direction.x, direction.z))
                + entity.getType().getModelYawOffsetDegrees();
    }

    /** Smoothly rotates the entity toward {@code targetYaw} along the shortest arc. */
    private void rotateToward(float targetYaw, float deltaTime) {
        Vector3f rotation = entity.getRotation();
        float deltaYaw = targetYaw - rotation.y;
        while (deltaYaw > 180.0f) deltaYaw -= 360.0f;
        while (deltaYaw < -180.0f) deltaYaw += 360.0f;

        float maxRotation = rotationSpeedDegPerSec * deltaTime;
        if (Math.abs(deltaYaw) > maxRotation) {
            deltaYaw = Math.signum(deltaYaw) * maxRotation;
        }
        entity.setRotation(new Vector3f(rotation.x, rotation.y + deltaYaw, rotation.z));
    }

    /**
     * Whether a one-block hop would clear the obstacle directly ahead: a solid
     * block at body level in the column just past the bounding-box leading edge,
     * with two clear blocks above it.
     */
    private boolean shouldJumpObstacle(Vector3f direction) {
        Vector3f pos = entity.getPosition();

        float reach = Math.abs(direction.x) * (entity.getWidth() * 0.5f)
                + Math.abs(direction.z) * (entity.getLength() * 0.5f)
                + OBSTACLE_LOOK_AHEAD;

        int blockX = (int) Math.floor(pos.x + direction.x * reach);
        int blockZ = (int) Math.floor(pos.z + direction.z * reach);
        int blockY = (int) Math.floor(pos.y);

        BlockType ahead = entity.getWorld().getBlockAt(blockX, blockY, blockZ);
        if (ahead == null || !ahead.isSolid()) {
            return false;
        }
        BlockType above1 = entity.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
        BlockType above2 = entity.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
        return (above1 == null || !above1.isSolid())
                && (above2 == null || !above2.isSolid());
    }

    // ── Debug path trail ──────────────────────────────────────────────────────

    /**
     * Records the entity's positions while moving so the debug overlay can draw
     * its trail. Points are only kept while the overlay is visible; the trail
     * clears after {@code idleSeconds} exceeds 3s of standing still.
     */
    public void updatePathTrail(boolean moving, float idleSeconds) {
        if (Game.getDebugOverlay() == null || !Game.getDebugOverlay().isVisible()) {
            if (!pathPoints.isEmpty()) {
                pathPoints.clear();
            }
            return;
        }

        Vector3f currentPos = entity.getPosition();
        if (moving || pathPoints.isEmpty()) {
            if (pathPoints.isEmpty()
                    || pathPoints.get(pathPoints.size() - 1).distance(currentPos) > 0.5f) {
                pathPoints.add(new Vector3f(currentPos));
                if (pathPoints.size() > MAX_PATH_POINTS) {
                    pathPoints.remove(0);
                }
            }
        } else if (idleSeconds > 3.0f) {
            pathPoints.clear();
        }
    }

    /** Copy of the recorded trail for debug drawing. */
    public List<Vector3f> getPathPoints() {
        return new ArrayList<>(pathPoints);
    }

    public void clearDebugPaths() {
        pathPoints.clear();
    }

    /** Releases navigation state when the entity is removed. */
    public void cleanup() {
        hasTarget = false;
        pathPoints.clear();
    }
}
