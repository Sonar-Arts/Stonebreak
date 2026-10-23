package com.stonebreak.network.client;

import com.stonebreak.mobs.entities.Entity;
import org.joml.Vector3f;

/**
 * Smooths jittery network state updates into per-frame position/yaw values.
 *
 * <p>Each time a state packet arrives, the entity's currently-displayed position becomes
 * the {@code prev} sample and the new state becomes the {@code target}.
 * {@link #apply(Entity)} interpolates between them across an expected snapshot interval
 * (default = the server tick period, 50 ms).
 *
 * <p>If snapshots arrive late, interpolation extrapolates lightly past the target
 * (clamped) so motion doesn't visibly stop while waiting.
 *
 * <p>Renders against the game {@link Entity}, so it lives game-side (client package).
 */
public final class NetworkInterpolator {

    /**
     * Allow interp to over-shoot the target by this fraction while the next snapshot is due,
     * then ease back onto the target over the same span: a shadow that stopped (no further
     * moves until the 2 s resync) must rest ON its last authoritative position, not 25 % past
     * it — drops and landing mobs used to sink into the ground and oscillate on each resync.
     */
    private static final float MAX_EXTRAPOLATION = 0.25f;
    /** Default snapshot period (server tick) in nanos. */
    public static final long DEFAULT_PERIOD_NS = 50_000_000L;

    private float prevX, prevY, prevZ, prevYawDeg, prevPitchDeg;
    private float targetX, targetY, targetZ, targetYawDeg, targetPitchDeg;
    private long startNs;
    private long durationNs = DEFAULT_PERIOD_NS;
    private boolean hasSample = false;

    /** Seed both prev and target with the spawn snapshot. */
    public void seed(float x, float y, float z, float yawDeg, float pitchDeg) {
        prevX = targetX = x;
        prevY = targetY = y;
        prevZ = targetZ = z;
        prevYawDeg = targetYawDeg = yawDeg;
        prevPitchDeg = targetPitchDeg = pitchDeg;
        startNs = System.nanoTime();
        hasSample = true;
    }

    /**
     * Receive an authoritative state. The entity's current displayed position (the result
     * of the last {@link #apply(Entity)}) becomes the new prev.
     */
    public void receive(float x, float y, float z, float yawDeg, float pitchDeg, Entity displayed) {
        if (!hasSample) {
            seed(x, y, z, yawDeg, pitchDeg);
            return;
        }
        Vector3f cur = displayed.getPosition();
        prevX = cur.x; prevY = cur.y; prevZ = cur.z;
        prevYawDeg = displayed.getYaw();
        prevPitchDeg = targetPitchDeg; // pitch isn't on Entity rotation; carry last target
        targetX = x; targetY = y; targetZ = z;
        targetYawDeg = yawDeg;
        targetPitchDeg = pitchDeg;
        startNs = System.nanoTime();
    }

    public void receive(float x, float y, float z, float yawDeg, Entity displayed) {
        receive(x, y, z, yawDeg, targetPitchDeg, displayed);
    }

    /** Per-frame: write interpolated position/yaw onto the entity. */
    public void apply(Entity entity) {
        apply(entity, System.nanoTime());
    }

    /** {@link #apply(Entity)} at an explicit clock reading (tests). */
    void apply(Entity entity, long now) {
        if (!hasSample) {
            return;
        }
        float t = (now - startNs) / (float) durationNs;
        if (t > 1f) {
            float over = t - 1f;
            float ext = over <= MAX_EXTRAPOLATION ? over : Math.max(0f, 2f * MAX_EXTRAPOLATION - over);
            t = 1f + ext;
        }
        if (t < 0f) {
            t = 0f;
        }

        float x = prevX + (targetX - prevX) * t;
        float y = prevY + (targetY - prevY) * t;
        float z = prevZ + (targetZ - prevZ) * t;
        float yaw = lerpAngle(prevYawDeg, targetYawDeg, t);

        entity.setPosition(x, y, z);
        entity.setYaw(yaw);
    }

    public float currentTargetYaw() { return targetYawDeg; }
    public float currentTargetPitch() { return targetPitchDeg; }
    public float targetX() { return targetX; }
    public float targetY() { return targetY; }
    public float targetZ() { return targetZ; }

    private static float lerpAngle(float fromDeg, float toDeg, float t) {
        float diff = ((toDeg - fromDeg + 540f) % 360f) - 180f;
        return fromDeg + diff * t;
    }
}
