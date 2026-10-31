package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerGazeTest {

    private static final Vector3f EYE = new Vector3f(0f, 65f, 0f);
    private static final Vector3f LOOK_EAST = new Vector3f(1f, 0f, 0f);

    @Test
    void whatIsAheadIsInViewAndWhatIsBesideOrBehindIsNot() {
        assertTrue(PlayerGaze.inCone(EYE, LOOK_EAST, new Vector3f(10f, 65f, 2f), 22f, 48f));
        assertFalse(PlayerGaze.inCone(EYE, LOOK_EAST, new Vector3f(10f, 65f, 10f), 22f, 48f),
                "45 degrees off: on screen, not looked at");
        assertTrue(PlayerGaze.inCone(EYE, LOOK_EAST, new Vector3f(10f, 65f, 10f), 60f, 48f));
        assertFalse(PlayerGaze.inCone(EYE, LOOK_EAST, new Vector3f(-10f, 65f, 0f), 60f, 48f));
    }

    @Test
    void beyondRangeIsNotSeen() {
        assertFalse(PlayerGaze.inCone(EYE, LOOK_EAST, new Vector3f(60f, 65f, 0f), 60f, 48f));
    }

    @Test
    void horizontalAngleRunsFromAheadToBehind() {
        assertEquals(0f, PlayerGaze.horizontalAngleTo(EYE, LOOK_EAST, new Vector3f(5f, 70f, 0f)), 0.01f);
        assertEquals(90f, PlayerGaze.horizontalAngleTo(EYE, LOOK_EAST, new Vector3f(0f, 65f, 5f)), 0.01f);
        assertEquals(180f, PlayerGaze.horizontalAngleTo(EYE, LOOK_EAST, new Vector3f(-5f, 60f, 0f)), 0.01f);
    }
}
