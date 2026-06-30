package com.stonebreak.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PlayerBodyOrientation} — the third-person body facing /
 * head-angle logic, decoupled from the camera.
 */
class PlayerBodyOrientationTest {

    private static final float DT = 1f / 60f;
    private static final Vector3f STILL = new Vector3f(0f, 0f, 0f);
    /** Mirrors PlayerBodyOrientation.MAX_HEAD_YAW (private). */
    private static final float MAX_HEAD_YAW = 70f;

    /** Advances the orientation many ticks so any smoothed turn converges. */
    private static void settle(PlayerBodyOrientation o, Vector3f velocity, float lookYaw) {
        for (int i = 0; i < 600; i++) {
            o.update(DT, velocity, lookYaw);
        }
    }

    @Test
    void idleBodyTurnsToFaceLookDirection() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        // First update seeds bodyYaw to the look direction.
        o.update(DT, STILL, 0f);

        // Look to a new direction and let the idle body settle: it should rotate to
        // face the look direction, so the residual head yaw returns to ~0.
        float lookYaw = 120f;
        settle(o, STILL, lookYaw);
        assertEquals(normalize(lookYaw), normalize(o.getBodyYaw()), 0.5f,
                "idle body should turn to face the look direction");
        assertEquals(0f, o.getHeadYaw(lookYaw), 0.5f);
    }

    @Test
    void headLeadsTheBodyDuringATurn() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        o.update(DT, STILL, 0f); // bodyYaw -> 0

        // A single tick after a large sudden look change: the body has only turned a
        // little (rate-limited), so the head leads, clamped to its swivel range.
        o.update(DT, STILL, 120f);
        float headYaw = o.getHeadYaw(120f);
        assertTrue(headYaw > 0f, "head should lead the lagging body");
        assertTrue(headYaw <= MAX_HEAD_YAW + 1e-3f, "head lead is clamped");
    }

    @Test
    void movingBodyFacesMovementDirection() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        // Velocity along +X → model yaw via the shared SBE convention.
        Vector3f velocity = new Vector3f(1f, 0f, 0f);
        float expected = normalize(PlayerBodyOrientation.modelYawFromDirection(1f, 0f));

        settle(o, velocity, /*lookYaw*/ 0f);
        assertEquals(expected, normalize(o.getBodyYaw()), 0.5f,
                "moving body should face the horizontal movement direction");
    }

    @Test
    void headPitchIsClamped() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        o.update(DT, STILL, 0f);
        assertEquals(45f, o.getHeadPitch(80f), 1e-3f);
        assertEquals(-45f, o.getHeadPitch(-80f), 1e-3f);
        assertEquals(20f, o.getHeadPitch(20f), 1e-3f);
    }

    @Test
    void headYawIsClampedToSwivelRange() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        o.update(DT, STILL, 0f); // bodyYaw -> 0
        // Without ticking further, a sudden 120-degree look offset clamps the head.
        assertTrue(Math.abs(o.getHeadYaw(120f)) <= MAX_HEAD_YAW + 1e-3f);
        assertEquals(MAX_HEAD_YAW, o.getHeadYaw(120f), 1e-3f);
    }

    /** Normalizes to (-180, 180] for stable comparison. */
    private static float normalize(float deg) {
        deg %= 360f;
        if (deg > 180f) deg -= 360f;
        else if (deg <= -180f) deg += 360f;
        return deg;
    }
}
