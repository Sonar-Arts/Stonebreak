package com.stonebreak.ui.mainMenu.showcase;

import com.openmason.engine.util.easing.EasingFunctions;
import com.openmason.engine.util.easing.EasingType;
import com.stonebreak.battle.camera.CameraFrame;
import org.joml.Vector3f;

/**
 * One drone shot of the main menu's live backdrop: where the camera flies over {@link #duration}
 * seconds and what it looks at. Absolute world coordinates of the showcase seed.
 *
 * <ul>
 *   <li>{@link Motion#LINEAR}: the eye and the target each glide in a straight line (dolly, crane,
 *       push-in, fly-over: whichever the two ends describe).</li>
 *   <li>{@link Motion#ORBIT}: the eye circles the target, bearing, radius and height interpolating
 *       from {@code eyeFrom} to {@code eyeTo} (both relative to their end's target), sweeping
 *       {@code sweepDeg} (NaN: the short way between the two ends).</li>
 * </ul>
 *
 * Pure math, GL-free: the same evaluation as the battle camera's {@code ShotEvaluator}, minus its
 * combatant anchors.
 *
 * @param caption shown in the menu's corner while the shot plays (may be empty)
 * @param timeTicks the time of day set while the screen is black before the shot (null: the clock runs on)
 * @param weather the weather mode set before the shot ({@code WeatherMode} name; null: unchanged)
 * @param loop    the shot never ends: its clock wraps at {@link #duration} with no cut (an
 *                {@link Motion#ORBIT} sweeping 360° with equal ends and {@code Linear} easing loops
 *                seamlessly — the main menu's one continuous drone orbit)
 */
public record FlyoverShot(String caption, Motion motion,
                          Vector3f eyeFrom, Vector3f eyeTo, Vector3f targetFrom, Vector3f targetTo,
                          float sweepDeg, float fovFrom, float fovTo, float rollFrom, float rollTo,
                          float duration, EasingType easing, Long timeTicks, String weather, boolean loop) {

    public enum Motion { LINEAR, ORBIT }

    public static final float MIN_FOV = 20f;
    public static final float MAX_FOV = 100f;
    /** lookAt degenerates on a vertical view; keep at least this much horizontal run. */
    private static final float MIN_HORIZONTAL_RUN = 0.05f;

    public FlyoverShot {
        caption = caption == null ? "" : caption;
        motion = motion == null ? Motion.LINEAR : motion;
        eyeFrom = new Vector3f(eyeFrom);
        eyeTo = new Vector3f(eyeTo == null ? eyeFrom : eyeTo);
        targetFrom = new Vector3f(targetFrom);
        targetTo = new Vector3f(targetTo == null ? targetFrom : targetTo);
        easing = easing == null ? EasingType.EaseInOutQuad : easing;
        if (!(duration > 0f) || !Float.isFinite(duration)) {
            throw new IllegalArgumentException("shot '" + caption + "': duration must be > 0, was " + duration);
        }
    }

    /** The camera at {@code time} seconds into the shot (clamped to the shot), raised by {@code lift} blocks. */
    public CameraFrame evaluate(float time, float lift) {
        if (loop && Float.isFinite(time)) {
            time = time % duration;
            if (time < 0f) {
                time += duration;
            }
        }
        float p = Float.isFinite(time) ? Math.max(0f, Math.min(1f, time / duration)) : 1f;
        float e = EasingFunctions.apply(p, easing);
        if (!Float.isFinite(e)) {
            e = p;
        }
        Vector3f target = new Vector3f(targetFrom).lerp(targetTo, e);
        Vector3f eye;
        if (motion == Motion.ORBIT) {
            Vector3f rel0 = new Vector3f(eyeFrom).sub(targetFrom);
            Vector3f rel1 = new Vector3f(eyeTo).sub(targetTo);
            float radius0 = (float) Math.hypot(rel0.x, rel0.z);
            float radius1 = (float) Math.hypot(rel1.x, rel1.z);
            float bearing0 = (float) Math.atan2(rel0.x, rel0.z);
            float sweep = Float.isNaN(sweepDeg)
                    ? wrapPi((float) Math.atan2(rel1.x, rel1.z) - bearing0)
                    : (float) Math.toRadians(sweepDeg);
            float bearing = bearing0 + sweep * e;
            float radius = radius0 + (radius1 - radius0) * e;
            float height = rel0.y + (rel1.y - rel0.y) * e;
            eye = new Vector3f(target).add((float) Math.sin(bearing) * radius, height, (float) Math.cos(bearing) * radius);
        } else {
            eye = new Vector3f(eyeFrom).lerp(eyeTo, e);
        }
        if (lift != 0f && Float.isFinite(lift)) {
            eye.y += lift;
            target.y += lift;
        }
        float dx = target.x - eye.x;
        float dz = target.z - eye.z;
        if (dx * dx + dz * dz < MIN_HORIZONTAL_RUN * MIN_HORIZONTAL_RUN) {
            target.add(MIN_HORIZONTAL_RUN * 2f, 0f, 0f);
        }
        float fov = fovFrom + (fovTo - fovFrom) * e;
        fov = Float.isFinite(fov) ? Math.max(MIN_FOV, Math.min(MAX_FOV, fov)) : 70f;
        float roll = rollFrom + (rollTo - rollFrom) * e;
        return new CameraFrame(eye, target, Float.isFinite(roll) ? roll : 0f, fov);
    }

    /**
     * How far (blocks) the whole shot must rise so its eye never comes within {@code clearance}
     * blocks of the ground or {@code waterClearance} of the water anywhere along its path: shots are
     * authored for one noise backend, and a world generated by the other must not put the camera
     * inside a hill. Samples the path; 0 when it already clears.
     */
    public float liftAbove(GroundProbe ground, float clearance, float waterClearance) {
        float lift = 0f;
        int samples = 24;
        for (int i = 0; i <= samples; i++) {
            Vector3f eye = evaluate(duration * i / samples, 0f).eye();
            int x = (int) Math.floor(eye.x);
            int z = (int) Math.floor(eye.z);
            float floor = ground.groundHeight(x, z) + clearance;
            int water = ground.waterLevel(x, z);
            if (water > 0) {
                floor = Math.max(floor, water + waterClearance);
            }
            lift = Math.max(lift, floor - eye.y);
        }
        return lift;
    }

    /** Where the ground and water are, for {@link #liftAbove}. */
    public interface GroundProbe {
        /** The top terrain block's height at the column. */
        int groundHeight(int x, int z);

        /** The water surface height at the column, or a value {@code <= 0} for none. */
        int waterLevel(int x, int z);
    }

    private static float wrapPi(float a) {
        double twoPi = Math.PI * 2;
        double w = ((a + Math.PI) % twoPi + twoPi) % twoPi - Math.PI;
        return (float) w;
    }
}
