package com.stonebreak.ui.mainMenu.showcase;

import com.stonebreak.battle.camera.CameraFrame;
import org.joml.Vector3f;

/**
 * The main menu's Resume move: from the preview's orbit shot into the player's own eye, while the
 * menu slides off ahead of it. The eye glides in a straight line; the view direction turns by its
 * yaw and pitch (the short way round), never through a lerped look-at point that could pass
 * through the eye when the orbit faced the player. Smoothstep-eased like the battle camera's
 * blends ({@code CameraRig}). Pure math, GL-free.
 */
public final class ResumeTransition {

    /** How long the whole move takes, seconds. */
    public static final float DEFAULT_SECONDS = 2.6f;
    /** The menu is fully out by this share of the move, before the camera arrives. */
    static final float MENU_EXIT_SHARE = 0.55f;
    /** The look-at point sits this far along the view (any distance well clear of the eye). */
    private static final float LOOK_DISTANCE = 8f;

    private final CameraFrame from;
    private final CameraFrame to;
    private final float duration;
    private final float yawFrom;
    private final float pitchFrom;
    private final float yawSweep;
    private final float pitchTo;
    private float time;

    public ResumeTransition(CameraFrame from, CameraFrame to, float duration) {
        this.from = from;
        this.to = to;
        this.duration = duration > 0f && Float.isFinite(duration) ? duration : DEFAULT_SECONDS;
        Vector3f d0 = new Vector3f(from.target()).sub(from.eye());
        Vector3f d1 = new Vector3f(to.target()).sub(to.eye());
        this.yawFrom = yawOf(d0);
        this.pitchFrom = pitchOf(d0);
        this.yawSweep = wrapPi(yawOf(d1) - yawFrom);
        this.pitchTo = pitchOf(d1);
    }

    /**
     * The first-person view of a player standing at {@code feet}: the eye at {@code eyeHeight}
     * above the feet, looking along the camera's yaw/pitch (degrees, {@code Camera}'s convention).
     */
    public static CameraFrame firstPerson(Vector3f feet, float eyeHeight, float yawDeg, float pitchDeg, float fovDeg) {
        Vector3f eye = new Vector3f(feet).add(0f, eyeHeight, 0f);
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        Vector3f front = new Vector3f(
                (float) (Math.cos(yaw) * Math.cos(pitch)),
                (float) Math.sin(pitch),
                (float) (Math.sin(yaw) * Math.cos(pitch))).normalize();
        return new CameraFrame(eye, new Vector3f(eye).fma(LOOK_DISTANCE, front), 0f, fovDeg);
    }

    /** Advances {@code dt} seconds (a hitch is clamped, so the move is never skipped). */
    public void tick(float dt) {
        if (!(dt > 0f) || !Float.isFinite(dt)) {
            return;
        }
        time = Math.min(duration, time + Math.min(dt, 0.1f));
    }

    public boolean finished() {
        return time >= duration;
    }

    /** 0..1 through the move. */
    public float progress() {
        return Math.min(1f, time / duration);
    }

    /** The camera now. */
    public CameraFrame frame() {
        float p = progress();
        float w = p * p * (3f - 2f * p);
        Vector3f eye = new Vector3f(from.eye()).lerp(to.eye(), w);
        float yaw = yawFrom + yawSweep * w;
        float pitch = pitchFrom + (pitchTo - pitchFrom) * w;
        Vector3f front = new Vector3f(
                (float) (Math.cos(yaw) * Math.cos(pitch)),
                (float) Math.sin(pitch),
                (float) (Math.sin(yaw) * Math.cos(pitch)));
        Vector3f target = new Vector3f(eye).fma(LOOK_DISTANCE, front);
        float roll = from.rollDeg() + (to.rollDeg() - from.rollDeg()) * w;
        float fov = from.fovDeg() + (to.fovDeg() - from.fovDeg()) * w;
        return new CameraFrame(eye, target, roll, fov);
    }

    /**
     * How far the menu has slid out, 0..1: eased in (it starts gently and leaves fast) and done
     * by {@link #MENU_EXIT_SHARE} of the move.
     */
    public float menuExit() {
        float p = Math.min(1f, progress() / MENU_EXIT_SHARE);
        return p * p;
    }

    /** Radians, {@code Camera}'s convention: front.x = cos(yaw)·cos(pitch), front.z = sin(yaw)·cos(pitch). */
    private static float yawOf(Vector3f d) {
        return (float) Math.atan2(d.z, d.x);
    }

    private static float pitchOf(Vector3f d) {
        float horizontal = (float) Math.hypot(d.x, d.z);
        return (float) Math.atan2(d.y, horizontal);
    }

    private static float wrapPi(float a) {
        double twoPi = Math.PI * 2;
        return (float) (((a + Math.PI) % twoPi + twoPi) % twoPi - Math.PI);
    }
}
