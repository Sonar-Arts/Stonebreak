package com.stonebreak.player;

import org.joml.Vector3f;

/**
 * Owns the third-person body's facing and derives the head look angles.
 *
 * <p>This is deliberately decoupled from the first-person {@link Camera}: the
 * camera only contributes a {@code lookYaw}/{@code lookPitch} aim, and this class
 * decides how the body turns in response. The body faces its movement direction
 * while walking, and — when stationary — rotates to face the look direction so the
 * model visibly turns to where the player is looking. The head leads the turn via
 * a clamped residual offset, then the body settles facing the look direction.
 *
 * <p>All yaw values are in the player model's facing space (degrees). Use
 * {@link #modelYawFromDirection} to convert a world XZ direction (camera front,
 * velocity, …) into this space.
 */
public final class PlayerBodyOrientation {

    /** How fast the lower body can turn, in degrees per second. */
    private static final float BODY_TURN_DEG_PER_SEC = 600f;
    /** Horizontal speed (blocks/frame) above which the body faces movement. */
    private static final float WALK_SPEED_THRESHOLD = 0.5f;
    /** Maximum head swivel away from the body facing before the body follows. */
    private static final float MAX_HEAD_YAW = 70f;
    /** Maximum head pitch up/down. */
    private static final float MAX_HEAD_PITCH = 45f;

    /** Lower-body facing in model space (degrees). */
    private float bodyYaw = 0f;
    private boolean bodyYawInitialized = false;

    /**
     * Recomputes the body facing for this frame.
     *
     * @param dt       frame delta in seconds
     * @param velocity current velocity (only the horizontal X/Z components are used)
     * @param lookYaw  where the player is looking, in model space (see
     *                 {@link #modelYawFromDirection} applied to the camera front)
     */
    public void update(float dt, Vector3f velocity, float lookYaw) {
        if (!bodyYawInitialized) {
            bodyYaw = lookYaw;
            bodyYawInitialized = true;
        }

        float horizSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float targetYaw;
        if (horizSpeed > WALK_SPEED_THRESHOLD) {
            // Moving: face the horizontal movement direction.
            targetYaw = modelYawFromDirection(velocity.x, velocity.z);
        } else {
            // Idle: face the look direction so the body turns to where the player
            // looks. The head leads via the clamped residual in getHeadYaw().
            targetYaw = lookYaw;
        }
        turnToward(targetYaw, dt);
    }

    /**
     * Converts a world XZ direction into the player model's facing yaw (degrees).
     *
     * <p>This uses {@code atan2(dir.x, dir.z)} with the rotation sense of the SBE
     * renderer and the same {@code +180} offset as {@code CowAI}/{@code SheepAI}:
     * the {@code SB_Player.sbe} model is authored facing {@code -Z} (like the
     * cow/sheep models), so the half-turn flip aligns its forward axis with the
     * travel/look direction.
     */
    public static float modelYawFromDirection(float dirX, float dirZ) {
        return (float) Math.toDegrees(Math.atan2(dirX, dirZ)) + 180.0f;
    }

    /** Lower-body facing in model space (degrees); base yaw for the body model. */
    public float getBodyYaw() {
        return bodyYaw;
    }

    /** Head yaw relative to the body, clamped to the comfortable swivel range. */
    public float getHeadYaw(float lookYaw) {
        return clamp(wrapDegrees(lookYaw - bodyYaw), -MAX_HEAD_YAW, MAX_HEAD_YAW);
    }

    /** Head pitch, clamped so the head never over-rotates up/down. */
    public float getHeadPitch(float lookPitch) {
        return clamp(lookPitch, -MAX_HEAD_PITCH, MAX_HEAD_PITCH);
    }

    /** Smoothly rotates {@link #bodyYaw} toward {@code targetYaw} at the turn rate. */
    private void turnToward(float targetYaw, float dt) {
        float delta = wrapDegrees(targetYaw - bodyYaw);
        float maxStep = BODY_TURN_DEG_PER_SEC * dt;
        if (Math.abs(delta) <= maxStep) {
            bodyYaw = targetYaw;
        } else {
            bodyYaw += Math.signum(delta) * maxStep;
        }
    }

    /** Normalizes an angle in degrees to the range (-180, 180]. */
    private static float wrapDegrees(float deg) {
        deg %= 360f;
        if (deg > 180f) deg -= 360f;
        else if (deg <= -180f) deg += 360f;
        return deg;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
