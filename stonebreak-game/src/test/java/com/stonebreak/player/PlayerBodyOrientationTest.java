package com.stonebreak.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PlayerBodyOrientation} — the third-person body facing /
 * head-angle logic, decoupled from the camera.
 */
class PlayerBodyOrientationTest {

    private static final float DT = 1f / 60f;
    private static final Vector3f STILL = new Vector3f(0f, 0f, 0f);

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
        // little (rate-limited, 600 deg/s), so the head leads by the rest of the turn.
        o.update(DT, STILL, 120f);
        float headYaw = o.getHeadYaw(120f);
        assertEquals(110f, headYaw, 1e-3f, "head should lead the lagging body by the remaining turn");
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

    /** The look limits are the animation graph's (#460): the orientation hands the look on unclamped. */
    @Test
    void headPitchIsTheLookPitch() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        o.update(DT, STILL, 0f);
        assertEquals(80f, o.getHeadPitch(80f), 1e-3f);
        assertEquals(-80f, o.getHeadPitch(-80f), 1e-3f);
        assertEquals(20f, o.getHeadPitch(20f), 1e-3f);
    }

    @Test
    void headYawIsTheWrappedLookOffset() {
        PlayerBodyOrientation o = new PlayerBodyOrientation();
        o.update(DT, STILL, 0f); // bodyYaw -> 0
        assertEquals(120f, o.getHeadYaw(120f), 1e-3f);
        assertEquals(-150f, o.getHeadYaw(210f), 1e-3f);
        assertEquals(180f, o.getHeadYaw(-180f), 1e-3f);
    }

    /** Normalizes to (-180, 180] for stable comparison. */
    private static float normalize(float deg) {
        deg %= 360f;
        if (deg > 180f) deg -= 360f;
        else if (deg <= -180f) deg += 360f;
        return deg;
    }
}
