package com.stonebreak.network.client;

import com.stonebreak.mobs.entities.Entity;
import org.joml.Vector3f;

/**
 * Smooths jittery network state updates into per-frame position/yaw values.
 *
 * <p>Each snapshot plays as one segment from the previous position to the new one across an
 * expected snapshot interval (default = the server tick period, 50 ms). A snapshot that
 * arrives while a segment is still playing waits in a small buffer and plays next, so a burst
 * — a server catching up several ticks at once, or packets bunched by the network — plays out
 * evenly instead of covering a quarter second of motion in one segment and then freezing. A
 * buffer that grows plays faster to catch up.
 *
 * <p>If snapshots stop, interpolation extrapolates briefly past the target, then eases back
 * onto it (a stopped entity rests on its last authoritative position).
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
    /** Snapshots waiting behind the playing segment; past this the oldest is dropped. */
    private static final int MAX_BUFFERED = 8;
    /** With this many waiting, segments play at half length until the buffer drains. */
    private static final int CATCH_UP_BUFFERED = 2;

    private float prevX, prevY, prevZ, prevYawDeg, prevPitchDeg;
    private float targetX, targetY, targetZ, targetYawDeg, targetPitchDeg;
    private long startNs;
    private long durationNs = DEFAULT_PERIOD_NS;
    private boolean hasSample = false;

    /** Ring buffer of waiting snapshots: x, y, z, yaw, pitch per entry. */
    private final float[] buffered = new float[MAX_BUFFERED * 5];
    private int bufferHead;
    private int bufferCount;
    /** The newest snapshot received (buffered or playing) — the server's broadcast base. */
    private float latestX, latestY, latestZ, latestYawDeg, latestPitchDeg;

    /** Seed both prev and target with the spawn snapshot (and drop anything buffered). */
    public void seed(float x, float y, float z, float yawDeg, float pitchDeg) {
        prevX = targetX = latestX = x;
        prevY = targetY = latestY = y;
        prevZ = targetZ = latestZ = z;
        prevYawDeg = targetYawDeg = latestYawDeg = yawDeg;
        prevPitchDeg = targetPitchDeg = latestPitchDeg = pitchDeg;
        startNs = System.nanoTime();
        durationNs = DEFAULT_PERIOD_NS;
        bufferHead = 0;
        bufferCount = 0;
        hasSample = true;
    }

    /**
     * Receive an authoritative state. With no segment playing, it starts one from the entity's
     * currently displayed position; otherwise it waits its turn in the buffer.
     */
    public void receive(float x, float y, float z, float yawDeg, float pitchDeg, Entity displayed) {
        if (!hasSample) {
            seed(x, y, z, yawDeg, pitchDeg);
            return;
        }
        latestX = x; latestY = y; latestZ = z; latestYawDeg = yawDeg; latestPitchDeg = pitchDeg;
        long now = System.nanoTime();
        if (bufferCount == 0 && now - startNs >= durationNs) {
            Vector3f cur = displayed.getPosition();
            prevX = cur.x; prevY = cur.y; prevZ = cur.z;
            prevYawDeg = displayed.getYaw();
            prevPitchDeg = targetPitchDeg; // pitch isn't on Entity rotation; carry last target
            targetX = x; targetY = y; targetZ = z;
            targetYawDeg = yawDeg;
            targetPitchDeg = pitchDeg;
            startNs = now;
            durationNs = DEFAULT_PERIOD_NS;
            return;
        }
        if (bufferCount == MAX_BUFFERED) {
            bufferHead = (bufferHead + 1) % MAX_BUFFERED; // too far behind: drop the oldest
            bufferCount--;
        }
        int slot = ((bufferHead + bufferCount) % MAX_BUFFERED) * 5;
        buffered[slot] = x;
        buffered[slot + 1] = y;
        buffered[slot + 2] = z;
        buffered[slot + 3] = yawDeg;
        buffered[slot + 4] = pitchDeg;
        bufferCount++;
    }

    public void receive(float x, float y, float z, float yawDeg, Entity displayed) {
        receive(x, y, z, yawDeg, latestPitchDeg, displayed);
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
        // Segments that have finished hand over to the next buffered snapshot, continuing
        // from where the last one ended (no restart from "now": the timeline stays even).
        while (bufferCount > 0 && now - startNs >= durationNs) {
            int slot = bufferHead * 5;
            bufferHead = (bufferHead + 1) % MAX_BUFFERED;
            bufferCount--;
            prevX = targetX; prevY = targetY; prevZ = targetZ;
            prevYawDeg = targetYawDeg; prevPitchDeg = targetPitchDeg;
            targetX = buffered[slot];
            targetY = buffered[slot + 1];
            targetZ = buffered[slot + 2];
            targetYawDeg = buffered[slot + 3];
            targetPitchDeg = buffered[slot + 4];
            startNs += durationNs;
            durationNs = bufferCount >= CATCH_UP_BUFFERED ? DEFAULT_PERIOD_NS / 2 : DEFAULT_PERIOD_NS;
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

    /** Newest received yaw/pitch/position — what the server last broadcast (delta base). */
    public float currentTargetYaw() { return latestYawDeg; }
    public float currentTargetPitch() { return latestPitchDeg; }
    public float targetX() { return latestX; }
    public float targetY() { return latestY; }
    public float targetZ() { return latestZ; }

    private static float lerpAngle(float fromDeg, float toDeg, float t) {
        float diff = ((toDeg - fromDeg + 540f) % 360f) - 180f;
        return fromDeg + diff * t;
    }
}
