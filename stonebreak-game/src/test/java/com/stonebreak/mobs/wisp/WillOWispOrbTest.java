package com.stonebreak.mobs.wisp;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The orb bends toward its victim, but only so fast — a curve a player can step out of. */
class WillOWispOrbTest {

    @Test
    void itTurnsNoFasterThanItsTurnRate() {
        Vector3f dir = new Vector3f(1f, 0f, 0f);
        Vector3f goal = new Vector3f(0f, 0f, 1f);
        float step = (float) Math.toRadians(10f);
        WillOWispOrb.turnToward(dir, goal, step);
        assertEquals(10f, (float) Math.toDegrees(new Vector3f(1f, 0f, 0f).angle(dir)), 1e-3f);
        assertEquals(1f, dir.length(), 1e-5f);
    }

    @Test
    void aSmallGapClosesAtOnce() {
        Vector3f dir = new Vector3f(1f, 0f, 0f);
        Vector3f goal = new Vector3f(1f, 0f, 0.05f).normalize();
        WillOWispOrb.turnToward(dir, goal, (float) Math.toRadians(10f));
        assertEquals(0f, dir.angle(goal), 1e-5f);
    }
}
