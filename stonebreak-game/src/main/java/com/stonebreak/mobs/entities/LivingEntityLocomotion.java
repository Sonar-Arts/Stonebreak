package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.Entity.BoundingBox;

/**
 * Locomotion component of a {@link LivingEntity}: jumping and swim strokes (and the apex
 * heights route planning derives from them), yaw steering honouring the model yaw offset,
 * knockback impulses, the rooted-velocity pin, and the leg-aware block-collision / flower
 * avoidance probes. Operates directly on the owner's velocity/rotation so the integration
 * order in {@code LivingEntity.update} is unchanged. Extracted from {@code LivingEntity}
 * (issue #233); the entity remains the public facade.
 */
final class LivingEntityLocomotion {

    private final LivingEntity owner;

    LivingEntityLocomotion(LivingEntity owner) {
        this.owner = owner;
    }

    /** Whether the owner is currently moving (velocity above the idle threshold). */
    boolean computeIsMoving() {
        return owner.velocity.length() > 0.1f;
    }

    /**
     * Rooted entities are pinned in place — kills residual horizontal drift (knockback slide,
     * water flow) before it integrates into position.
     */
    void pinIfRooted() {
        if (owner.isRooted()) {
            owner.velocity.x = 0f;
            owner.velocity.z = 0f;
        }
    }

    /** Jumps by applying the owner's jump velocity upward; only fires on the ground. */
    void jump() {
        if (owner.isOnGround()) {
            owner.velocity.y = owner.jumpVelocity;
            owner.setOnGround(false);
        }
    }

    /** A swim stroke: pushes the mob up through the water it is in, as strong as its jump. */
    void swimUp() {
        if (owner.isInWater()) {
            owner.velocity.y = Math.max(owner.velocity.y,
                    Math.max(owner.jumpVelocity, EntityWaterPhysics.MIN_ESCAPE_STROKE));
            owner.setOnGround(false);
        }
    }

    /** How high a swim stroke carries this mob, in blocks. */
    float swimStrokeReach() {
        float stroke = Math.max(owner.jumpVelocity, EntityWaterPhysics.MIN_ESCAPE_STROKE);
        return (stroke * stroke) / (2.0f * -Entity.GRAVITY);
    }

    /** How high this mob's jump actually carries it, in blocks. */
    float jumpApexHeight() {
        return (owner.jumpVelocity * owner.jumpVelocity) / (2.0f * -Entity.GRAVITY);
    }

    /**
     * Rotates the owner toward a direction along the shortest arc at its turn speed, honoring
     * the entity type's model yaw offset.
     */
    void faceDirection(Vector3f direction, float deltaTime) {
        if (direction.length() < 0.1f) return;

        float targetYaw = (float) Math.toDegrees(Math.atan2(direction.x, direction.z))
                + owner.getType().getModelYawOffsetDegrees();

        // Smoothly rotate toward target along the shortest arc
        float yawDiff = targetYaw - owner.rotation.y;
        while (yawDiff > 180.0f) yawDiff -= 360.0f;
        while (yawDiff < -180.0f) yawDiff += 360.0f;

        float maxRotation = owner.turnSpeed * deltaTime;
        if (Math.abs(yawDiff) > maxRotation) {
            yawDiff = Math.signum(yawDiff) * maxRotation;
        }

        owner.rotation.y += yawDiff;
    }

    /** The world-space horizontal direction the owner's model front points. */
    Vector3f forwardDirection() {
        float travelYawRad = (float) Math.toRadians(
                owner.rotation.y - owner.getType().getModelYawOffsetDegrees());
        return new Vector3f((float) Math.sin(travelYawRad), 0f, (float) Math.cos(travelYawRad));
    }

    /** A random horizontal unit direction for wandering behavior. */
    static Vector3f randomDirection() {
        float angle = (float) (Math.random() * 2 * Math.PI);
        return new Vector3f(
            (float) Math.sin(angle),
            0,
            (float) Math.cos(angle)
        );
    }

    /**
     * Checks if the owner can move to a specific position. Collision detection starts from the
     * very bottom of the entity's legs.
     */
    boolean canMoveTo(Vector3f targetPosition) {
        // Create bounding box starting from the bottom of the legs
        // targetPosition.y represents the bottom of the body, so subtract legHeight to get leg bottom
        float legBottomY = targetPosition.y - owner.legHeight;
        BoundingBox targetBounds = new BoundingBox(
            targetPosition.x - owner.width / 2.0f,
            legBottomY, // Start from bottom of legs
            targetPosition.z - owner.length / 2.0f,
            targetPosition.x + owner.width / 2.0f,
            targetPosition.y + owner.height, // Extend to full height
            targetPosition.z + owner.length / 2.0f
        );

        // Check for solid blocks in the target area
        int minX = (int) Math.floor(targetBounds.minX);
        int maxX = (int) Math.ceil(targetBounds.maxX);
        int minY = (int) Math.floor(targetBounds.minY);
        int maxY = (int) Math.ceil(targetBounds.maxY);
        int minZ = (int) Math.floor(targetBounds.minZ);
        int maxZ = (int) Math.ceil(targetBounds.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (owner.world.getBlockAt(x, y, z) != null &&
                        owner.world.getBlockAt(x, y, z).isSolid()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Checks if the owner can move to a specific position while avoiding flowers (for cows and
     * other passive mobs that should not trample them).
     */
    boolean canMoveToAvoidingFlowers(Vector3f targetPosition) {
        // First do basic collision check
        if (!owner.canMoveTo(targetPosition)) {
            return false;
        }

        // Check for flowers at ground level to avoid trampling them
        // Use bottom of legs position for ground checking
        float legBottomY = targetPosition.y - owner.legHeight;
        int groundX = (int) Math.floor(targetPosition.x);
        int groundY = (int) Math.floor(legBottomY);
        int groundZ = (int) Math.floor(targetPosition.z);

        // Check current ground block and surrounding area
        for (int x = groundX - 1; x <= groundX + 1; x++) {
            for (int z = groundZ - 1; z <= groundZ + 1; z++) {
                // Check at ground level and one block up (where flowers typically are)
                for (int y = groundY; y <= groundY + 1; y++) {
                    var blockType = owner.world.getBlockAt(x, y, z);
                    if (blockType != null && isFlower(blockType)) {
                        return false; // Avoid trampling flowers
                    }
                }
            }
        }

        return true;
    }

    /** Helper method to identify flower blocks. */
    private static boolean isFlower(BlockType blockType) {
        return blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION ||
               blockType == BlockType.WILDGRASS;
    }

    /**
     * Applies an instantaneous knockback impulse in an arbitrary horizontal direction plus a
     * vertical lift, clamping the resulting horizontal speed.
     */
    void applyKnockback(Vector3f horizontalDirection, float horizontalForce, float verticalForce) {
        Vector3f velocity = owner.velocity;
        Vector3f dir = new Vector3f(horizontalDirection.x, 0f, horizontalDirection.z);
        if (dir.lengthSquared() <= 0.0001f) return;
        dir.normalize();
        velocity.x += dir.x * horizontalForce;
        velocity.z += dir.z * horizontalForce;
        velocity.y += verticalForce;
        if (verticalForce > 0f) {
            owner.setOnGround(false);
        }
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontalSpeed > 8.0f) {
            float scale = 8.0f / horizontalSpeed;
            velocity.x *= scale;
            velocity.z *= scale;
        }
    }
}
