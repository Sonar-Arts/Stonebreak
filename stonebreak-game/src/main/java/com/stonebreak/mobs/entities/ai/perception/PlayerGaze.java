package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;

/**
 * Perception turned around: not whether a mob sees the player, but whether the player could see
 * the mob — the question a stalker lives by. Pure geometry; whether a wall is in the way is the
 * caller's line-of-sight test, kept separate so this stays testable without a world.
 */
public final class PlayerGaze {

    /**
     * Half-angle of what a player takes in at all: a little wider than half the default field of
     * view, so a mob at the edge of the screen counts as in view.
     */
    public static final float VIEW_HALF_ANGLE_DEG = 60f;

    /** Half-angle of what a player is looking <em>at</em>, as opposed to merely has on screen. */
    public static final float FOCUS_HALF_ANGLE_DEG = 22f;

    private PlayerGaze() {}

    /**
     * Whether {@code point} lies within {@code halfAngleDeg} of the direction a player at
     * {@code eye} is looking ({@code look}, unit length) and no further than {@code maxDistance}.
     */
    public static boolean inCone(Vector3f eye, Vector3f look, Vector3f point,
                                 float halfAngleDeg, float maxDistance) {
        float dx = point.x - eye.x;
        float dy = point.y - eye.y;
        float dz = point.z - eye.z;
        float distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > maxDistance * maxDistance) {
            return false;
        }
        if (distSq < 1.0e-6f) {
            return true;
        }
        float cos = (dx * look.x + dy * look.y + dz * look.z) / (float) Math.sqrt(distSq);
        return cos >= (float) Math.cos(Math.toRadians(halfAngleDeg));
    }

    /**
     * Horizontal angle, in degrees (0..180), between where the player looks and the direction from
     * them to {@code point}: 0 is dead ahead, 180 directly behind them.
     */
    public static float horizontalAngleTo(Vector3f eye, Vector3f look, Vector3f point) {
        float dx = point.x - eye.x;
        float dz = point.z - eye.z;
        float toLen = (float) Math.sqrt(dx * dx + dz * dz);
        float lookLen = (float) Math.sqrt(look.x * look.x + look.z * look.z);
        if (toLen < 1.0e-4f || lookLen < 1.0e-4f) {
            return 0f; // straight above or below them, or they look straight up: call it in front
        }
        float cos = (dx * look.x + dz * look.z) / (toLen * lookLen);
        return (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cos))));
    }
}
