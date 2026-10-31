package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookTrackerTest {

    private static final float TICK = 0.05f;
    private static final Vector3f WATCHER_WEST = new Vector3f(-10f, 64f, 0f);

    private static Vector3f lookAt(float yawDeg) {
        double r = Math.toRadians(yawDeg);
        return new Vector3f((float) Math.cos(r), 0f, (float) Math.sin(r));
    }

    @Test
    void aSteadyGazeIsCalmAndASpinIsFrantic() {
        LookTracker steady = new LookTracker();
        LookTracker spinning = new LookTracker();
        Vector3f player = new Vector3f(0f, 64f, 0f);
        for (int i = 0; i < 120; i++) {
            steady.update(lookAt(0f), player, WATCHER_WEST, TICK);
            spinning.update(lookAt(i * 360f * TICK), player, WATCHER_WEST, TICK); // 360 deg/s
        }
        assertEquals(0f, steady.franticness(), 1e-3f);
        assertTrue(spinning.franticness() > 200f, "franticness " + spinning.franticness());
    }

    @Test
    void theBackTurnedClockRunsOnlyWhileFacingAway() {
        LookTracker tracker = new LookTracker();
        Vector3f player = new Vector3f(0f, 64f, 0f);
        for (int i = 0; i < 40; i++) {
            tracker.update(lookAt(0f), player, WATCHER_WEST, TICK); // facing east, watcher west
        }
        assertEquals(2f, tracker.backTurnedSeconds(), 1e-3f);
        tracker.update(lookAt(180f), player, WATCHER_WEST, TICK); // turns to face it
        assertEquals(0f, tracker.backTurnedSeconds());
    }

    @Test
    void walkingCountsAsMovingAndStandingDoesNot() {
        LookTracker walker = new LookTracker();
        LookTracker stander = new LookTracker();
        for (int i = 0; i < 120; i++) {
            walker.update(lookAt(0f), new Vector3f(i * 4f * TICK, 64f, 0f), WATCHER_WEST, TICK);
            stander.update(lookAt(0f), new Vector3f(0f, 64f, 0f), WATCHER_WEST, TICK);
        }
        assertTrue(walker.isMoving());
        assertFalse(stander.isMoving());
    }

    @Test
    void aWalkerIsPredictedAlongTheirWayAndAStanderWhereTheyAre() {
        LookTracker walker = new LookTracker();
        LookTracker stander = new LookTracker();
        Vector3f standing = new Vector3f(0f, 64f, 0f);
        Vector3f walking = new Vector3f();
        for (int i = 0; i < 200; i++) {
            walking.set(0f, 64f, -i * 4f * TICK); // 4 b/s north, looking east
            walker.update(lookAt(0f), walking, WATCHER_WEST, TICK);
            stander.update(lookAt(0f), standing, WATCHER_WEST, TICK);
        }
        Vector3f ahead = walker.predict(walking, 3f, new Vector3f());
        assertEquals(walking.z - 12f, ahead.z, 0.5f, "where they walk, not where they look: " + ahead);
        assertEquals(walking.x, ahead.x, 1e-3f);
        assertEquals(standing, stander.predict(standing, 3f, new Vector3f()));
    }
}
