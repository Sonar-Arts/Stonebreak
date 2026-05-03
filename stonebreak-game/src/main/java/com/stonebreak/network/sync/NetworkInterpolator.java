package com.stonebreak.network.sync;

import com.stonebreak.mobs.entities.Entity;
import org.joml.Vector3f;

/**
 * Smooths jittery network state updates into per-frame position/yaw values.
 *
 * <p>Each time a state packet arrives, the entity's currently-displayed
 * position becomes the {@code prev} sample and the new state becomes the
 * {@code target}. {@link #apply(Entity)} interpolates between them across
 * an expected snapshot interval (default = the server tick period, 50 ms).
 *
 * <p>If snapshots arrive late, interpolation extrapolates lightly past the
 * target (clamped) so motion doesn't visibly stop while waiting.
 */
public final class NetworkInterpolator {

    /** Allow interp to over-shoot the target by this fraction before clamping. */
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
     * Receive an authoritative state. The entity's current displayed position
     * (the result of the last {@link #apply(Entity)}) becomes the new prev.
     */
    public void receive(float x, float y, float z, float yawDeg, float pitchDeg, Entity displayed) {
        if (!hasSample) {
            seed(x, y, z, yawDeg, pitchDeg);
            return;
        }
        Vector3f cur = displayed.getPosition();
        prevX = cur.x; prevY = cur.y; prevZ = cur.z;
        prevYawDeg = displayed.getRotation().y;
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
        if (!hasSample) return;
        long now = System.nanoTime();
        float t = (now - startNs) / (float) durationNs;
        if (t > 1f + MAX_EXTRAPOLATION) t = 1f + MAX_EXTRAPOLATION;
        if (t < 0f) t = 0f;

        float x = prevX + (targetX - prevX) * t;
        float y = prevY + (targetY - prevY) * t;
        float z = prevZ + (targetZ - prevZ) * t;
        float yaw = lerpAngle(prevYawDeg, targetYawDeg, t);

        entity.setPosition(new Vector3f(x, y, z));
        Vector3f rot = entity.getRotation();
        entity.setRotation(new Vector3f(rot.x, yaw, rot.z));
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
