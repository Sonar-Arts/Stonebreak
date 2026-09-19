package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import org.joml.Vector3f;

/** Evaluates a {@link CameraShot} at a time into a finite {@link CameraFrame}. Stateless. */
public final class ShotEvaluator {
    private ShotEvaluator() {}

    /** lookAt degenerates when the view direction is vertical or zero; keep at least this much horizontal run. */
    private static final float MIN_HORIZONTAL_RUN = 0.05f;

    public static CameraFrame evaluate(CameraShot shot, float time, BattleStageLayout layout, StagePoses poses) {
        float e = EasingFunctions.apply(phase(shot, time), shot.easing());
        if (!Float.isFinite(e)) e = 1f;

        Vector3f target0 = shot.targetFrom().resolve(layout, poses);
        Vector3f target1 = shot.targetTo().resolve(layout, poses);
        Vector3f eye0 = shot.eyeFrom().resolve(layout, poses);
        Vector3f eye1 = shot.eyeTo().resolve(layout, poses);
        Vector3f target = new Vector3f(target0).lerp(target1, e);

        Vector3f eye;
        if (shot.motion().cylindrical()) {
            Vector3f rel0 = eye0.sub(target0);
            Vector3f rel1 = eye1.sub(target1);
            float radius0 = (float) Math.hypot(rel0.x, rel0.z);
            float radius1 = (float) Math.hypot(rel1.x, rel1.z);
            float bearing0 = (float) Math.atan2(rel0.x, rel0.z);
            float sweep = Float.isNaN(shot.sweepDeg())
                    ? wrapPi((float) Math.atan2(rel1.x, rel1.z) - bearing0)
                    : (float) Math.toRadians(shot.sweepDeg());
            float bearing = bearing0 + sweep * e;
            float radius = radius0 + (radius1 - radius0) * e;
            float height = rel0.y + (rel1.y - rel0.y) * e;
            eye = new Vector3f(target).add((float) Math.sin(bearing) * radius, height, (float) Math.cos(bearing) * radius);
        } else {
            eye = eye0.lerp(eye1, e);
        }

        if (shot.panDeg() != 0f) {
            target = new Vector3f(target).sub(eye).rotateY((float) Math.toRadians(shot.panDeg())).add(eye);
        }

        float fov = shot.fovFrom() + (shot.fovTo() - shot.fovFrom()) * e;
        float roll = shot.rollFrom() + (shot.rollTo() - shot.rollFrom()) * e;
        return sanitize(eye, target, roll, fov, layout);
    }

    /** Normalised 0..1 position inside the shot for a time in seconds, honouring its end behaviour. */
    static float phase(CameraShot shot, float time) {
        if (shot.duration() <= 0f || !Float.isFinite(time)) return 1f;
        float p = Math.max(0f, time / shot.duration());
        return switch (shot.end()) {
            case HOLD -> Math.min(1f, p);
            case LOOP -> p - (float) Math.floor(p);
            case PING_PONG -> {
                float m = p % 2f;
                yield m <= 1f ? m : 2f - m;
            }
        };
    }

    /** Clamps FOV, replaces non-finite values and keeps eye and target apart so lookAt never degenerates. */
    static CameraFrame sanitize(Vector3f eye, Vector3f target, float roll, float fov, BattleStageLayout layout) {
        if (!eye.isFinite()) eye = layout.midpoint().add(0f, 6f, 0f);
        if (!target.isFinite()) target = layout.midpoint();
        float dx = target.x - eye.x;
        float dz = target.z - eye.z;
        if (dx * dx + dz * dz < MIN_HORIZONTAL_RUN * MIN_HORIZONTAL_RUN) {
            target = new Vector3f(target).add(layout.facing(CombatantId.MONK).mul(MIN_HORIZONTAL_RUN * 2f));
        }
        if (!Float.isFinite(roll)) roll = 0f;
        if (!Float.isFinite(fov)) fov = CameraShot.BASE_FOV;
        fov = Math.max(CameraShot.MIN_FOV, Math.min(CameraShot.MAX_FOV, fov));
        return new CameraFrame(eye, target, roll, fov);
    }

    private static float wrapPi(float a) {
        if (!Float.isFinite(a)) return 0f; // the loops below would never end on an infinity
        while (a > Math.PI) a -= (float) (2 * Math.PI);
        while (a < -Math.PI) a += (float) (2 * Math.PI);
        return a;
    }
}
