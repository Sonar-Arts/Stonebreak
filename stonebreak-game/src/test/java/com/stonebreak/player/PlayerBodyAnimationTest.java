package com.stonebreak.player;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerBodyAnimationTest {
    private final Vector3f velocity = new Vector3f(3, 0, 0);
    private final Vector3f front = new Vector3f(0, 0, -1);

    @Test
    void bodyPunchContinuesWhenGameplayAttackEnds() {
        var body = new PlayerBodyAnimation();
        body.update(0.01f, true, true, velocity, front, WALKING, false, null);
        body.update(0.25f, false, true, velocity, front, WALKING, false, null);
        body.update(0.13f, false, true, velocity, front, WALKING, false, null);
        assertTrue(body.getAttackOverlay().isVisible());
        assertEquals(0.38f, body.getAttackOverlay().time(), 1e-6f);
        assertEquals(1f, body.getAttackOverlay().weight(0.08f, 0.12f));
    }

    @Test
    void audioAndPoseUseAuthoredDurationEvenDuringAttack() {
        var asset = new SbeEntityAsset("test:player", Map.of(), Map.of("sprinting",
                new ParsedAnimClip("Sprint", 30, 2f, true, List.of())));
        var body = new PlayerBodyAnimation();
        body.update(0.25f, false, true, velocity, front, SPRINTING, false, asset);
        assertTrue(body.isFootstepDue());
        body.update(0.5f, true, true, velocity, front, SPRINTING, false, asset);
        assertFalse(body.isFootstepDue());
        assertEquals(0.75f, body.getBodyAnimationTime(), 1e-6f);
        body.update(0.25f, true, true, velocity, front, SPRINTING, false, asset);
        assertTrue(body.isFootstepDue());
        assertEquals(1f, body.getBodyAnimationTime(), 1e-6f);
        assertTrue(body.getAttackOverlay().isVisible());
    }

    @Test
    void idleAirborneAndSwimmingDoNotEmitGroundFootsteps() {
        var body = new PlayerBodyAnimation();
        body.update(0.1f, false, true, velocity, front, IDLE, false, null);
        assertFalse(body.isFootstepDue());
        body.update(0.1f, false, false, velocity, front, JUMPING, false, null);
        assertFalse(body.isFootstepDue());
        body.update(0.1f, false, true, velocity, front, WALKING, true, null);
        assertFalse(body.isFootstepDue());
        body.update(0.1f, false, false, velocity, front, WALKING, true, null);
        assertFalse(body.isFootstepDue());
        body.update(0.1f, false, true, velocity, front, IDLE, false, null);
        body.update(0.1f, false, true, velocity, front, SPRINTING, false, null);
        assertTrue(body.isFootstepDue());
    }
}
