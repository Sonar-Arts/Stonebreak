package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTrackingTest {

    private static final float TICK = 0.05f;
    private static final Vector3f EYE = new Vector3f(0f, 65.6f, 0f);
    private static final Vector3f EAST = new Vector3f(1f, 0f, 0f);
    private static final Vector3f WEST = new Vector3f(-1f, 0f, 0f);
    private static final Vector3f STALKER = new Vector3f(12f, 64f, 0f);

    private static void run(PlayerTracking tracking, float seconds, boolean inView, boolean watched, Vector3f look) {
        for (int i = 0; i < Math.round(seconds / TICK); i++) {
            tracking.update(inView, watched, STALKER, EYE, look, TICK);
        }
    }

    @Test
    void aStareTracksItWithinASecondAndAGlimpseTakesLonger() {
        PlayerTracking stared = new PlayerTracking();
        PlayerTracking glimpsed = new PlayerTracking();
        run(stared, 0.6f, true, true, EAST);
        run(glimpsed, 0.6f, true, false, EAST);
        assertTrue(stared.isTracking(), "level " + stared.level());
        assertFalse(glimpsed.isTracking(), "level " + glimpsed.level());
        run(glimpsed, 1.2f, true, false, EAST);
        assertTrue(glimpsed.isTracking(), "a long enough glimpse is tracking too");
    }

    @Test
    void itRemembersWhereItWasLastSeen() {
        PlayerTracking tracking = new PlayerTracking();
        assertNull(tracking.lastSeen());
        run(tracking, 0.5f, true, true, EAST);
        Vector3f elsewhere = new Vector3f(-30f, 64f, 5f);
        tracking.update(false, false, elsewhere, EYE, EAST, TICK);
        assertEquals(STALKER, tracking.lastSeen(), "out of sight, it is not seen to move");
    }

    @Test
    void aPlayerStillLookingWhereItVanishedForgetsMoreSlowly() {
        PlayerTracking searching = new PlayerTracking();
        PlayerTracking gaveUp = new PlayerTracking();
        run(searching, 2f, true, true, EAST);
        run(gaveUp, 2f, true, true, EAST);
        run(searching, 6f, false, false, EAST);
        run(gaveUp, 6f, false, false, WEST);
        assertTrue(searching.isTracking(), "still staring at the spot: level " + searching.level());
        assertFalse(gaveUp.isTracking(), "looked away: level " + gaveUp.level());
    }

    @Test
    void resetForgetsTheVictim() {
        PlayerTracking tracking = new PlayerTracking();
        run(tracking, 1f, true, true, EAST);
        tracking.reset();
        assertEquals(0f, tracking.level());
        assertNull(tracking.lastSeen());
    }
}
