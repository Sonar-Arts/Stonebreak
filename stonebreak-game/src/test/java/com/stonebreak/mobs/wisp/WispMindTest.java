package com.stonebreak.mobs.wisp;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fright meter, the mode it drives, and the pacing that keeps the Wisp hard to track. */
class WispMindTest {

    private static final WispTuning T = WispTuning.DEFAULT;
    private static final float TICK = 0.05f;

    private static WispMind mind() {
        WispMind mind = new WispMind(T);
        mind.tick(0f, 0f);
        return mind;
    }

    @Test
    void aPlayerHitAddsThirtyFiveAndOwesAnAnswerWhileStalking() {
        WispMind mind = mind();
        mind.onHitByPlayer();
        assertEquals(35f, mind.meter(), 1e-4f);
        assertEquals(WispMind.Mode.STALK, mind.mode());
        assertTrue(mind.takeHitPending());
        assertFalse(mind.takeHitPending(), "the answer is owed once");
    }

    @Test
    void aHitThatFillsTheMeterStartsTheAttackInsteadOfAnAnswer() {
        WispMind mind = mind();
        mind.frighten(70f);
        mind.onHitByPlayer();
        assertEquals(WispMind.Mode.ATTACK, mind.mode());
        assertFalse(mind.hitPending());
    }

    @Test
    void twoHitsDoNotAlwaysForceTheAttack() {
        WispMind mind = mind();
        mind.onHitByPlayer();
        mind.onHitByPlayer();
        assertEquals(WispMind.Mode.STALK, mind.mode(), "70 of 100: still only stalking");
    }

    @Test
    void fearFadesOnlyWhileStalking() {
        WispMind mind = mind();
        mind.frighten(50f);
        mind.tick(10f, 10f);
        assertEquals(50f - 10f * T.decayPerSec(), mind.meter(), 1e-4f);

        mind.frighten(100f);
        float full = mind.meter();
        mind.tick(20f, 10f);
        assertEquals(full, mind.meter(), 1e-4f, "an attacking Wisp does not calm itself");
    }

    @Test
    void anAttackThatEndsFallsBackToStalkingOnEdge() {
        WispMind mind = mind();
        mind.frighten(100f);
        mind.calmDown();
        assertEquals(WispMind.Mode.STALK, mind.mode());
        assertEquals(T.calmMeter(), mind.meter(), 1e-4f);
    }

    @Test
    void peekAndWooshShareAQuietGapButInvisibilityDoesNot() {
        WispMind mind = mind();
        Random random = new Random(1);
        mind.usedPeek(random);
        assertFalse(mind.wooshReady(), "a Woosh right after a Peek-a-boo is too easy to follow");
        assertTrue(mind.veilReady());

        mind.tick(T.sharedGapMin() - 0.1f, 0f);
        assertFalse(mind.wooshReady());
        mind.tick(T.sharedGapMax() + 0.1f, 0f);
        assertTrue(mind.wooshReady());
        assertFalse(mind.peekReady(), "Peek-a-boo is still on its own cooldown");
        mind.tick(T.peekCooldown() + 0.1f, 0f);
        assertTrue(mind.peekReady());
    }

    @Test
    void aWooshWaitsForATurnedBackAndACalmCamera() {
        WispMind mind = mind();
        Vector3f player = new Vector3f();
        Vector3f behind = new Vector3f(-10f, 0f, 0f);
        Vector3f lookingAway = new Vector3f(1f, 0f, 0f);
        for (int i = 0; i < 10; i++) {
            mind.look().update(lookingAway, player, behind, TICK);
        }
        assertFalse(mind.wooshReadIsSafe(), "half a second of back is not enough");
        for (int i = 0; i < 30; i++) {
            mind.look().update(lookingAway, player, behind, TICK);
        }
        assertTrue(mind.wooshReadIsSafe());

        mind.spotted();
        assertFalse(mind.wooshReadIsSafe(), "just caught at it: it waits");
    }

    @Test
    void aVictimHuntingForItIsNotWooshed() {
        WispMind mind = mind();
        Vector3f player = new Vector3f();
        Vector3f eye = new Vector3f(0f, 1.6f, 0f);
        Vector3f behind = new Vector3f(-10f, 0f, 0f);
        Vector3f lookingAway = new Vector3f(1f, 0f, 0f);
        for (int i = 0; i < 20; i++) {
            mind.tracking().update(true, true, behind, eye, lookingAway, TICK);
        }
        for (int i = 0; i < 40; i++) {
            mind.look().update(lookingAway, player, behind, TICK);
            mind.tracking().update(false, false, behind, eye, lookingAway, TICK);
        }
        assertTrue(mind.look().backTurnedSeconds() > T.wooshBackTurned());
        assertTrue(mind.tracking().isTracking());
        assertFalse(mind.wooshReadIsSafe(), "they have just been tracking it: they would turn");
    }

    @Test
    void aFranticPlayerIsNotWooshed() {
        WispMind mind = mind();
        Vector3f player = new Vector3f();
        Vector3f behind = new Vector3f(-10f, 0f, 0f);
        // Snapping between two headings that both keep the back to the Wisp, fast.
        for (int i = 0; i < 80; i++) {
            float yaw = (float) Math.toRadians(i % 2 == 0 ? -40 : 40);
            mind.look().update(new Vector3f((float) Math.cos(yaw), 0f, (float) Math.sin(yaw)),
                    player, behind, TICK);
        }
        assertTrue(mind.look().backTurnedSeconds() > T.wooshBackTurned());
        assertFalse(mind.wooshReadIsSafe());
    }

    /**
     * Nobody fights back and the Wisp keeps to its routine — a trick every 25 seconds, round the
     * cycle of Peek-a-boo (noticed), Invisibility, Woosh. It should snap after three to four
     * minutes: long enough for the hint to be taken.
     */
    @Test
    void anUnansweredRoutineSnapsAfterThreeToFourMinutes() {
        WispMind mind = mind();
        float[] cycle = {T.peekGain(), T.veilGain(), T.wooshGain()};
        float time = 0f;
        float nextTrick = 25f;
        int trick = 0;
        while (mind.mode() == WispMind.Mode.STALK && time < 600f) {
            time += TICK;
            mind.tick(time, TICK);
            if (time >= nextTrick) {
                mind.frighten(cycle[trick++ % cycle.length]);
                nextTrick += 25f;
            }
        }
        assertTrue(time >= 180f && time <= 240f, "snapped after " + time + " s");
    }
}
