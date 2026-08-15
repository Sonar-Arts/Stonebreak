package com.stonebreak.audio;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Footsteps fire on ground covered, not on a clock.
 *
 * <p>That is the whole point of the rewrite, and it is what makes cadence correct for free: these
 * tests walk a mob at different speeds and assert the step count follows the distance, never the
 * number of updates. No sound is actually played — the stub has no world — so what is under test is
 * the decision to step.
 */
class MobSoundsTest {

    private static final float STRIDE = 1.0f;

    @Test
    void stepsOncePerStrideOfTravel() {
        StubMob mob = grounded();
        MobSounds sounds = new MobSounds(null, STRIDE, 0.3f);

        int steps = walk(sounds, mob, 0.25f, 13); // 3.25 blocks in thirteen updates

        assertEquals(3, steps, "three-and-a-bit blocks walked is three footsteps");
    }

    @Test
    void speedChangesCadenceWithoutChangingStrideCount() {
        StubMob slow = grounded();
        StubMob fast = grounded();

        int slowSteps = walk(new MobSounds(null, STRIDE, 0.3f), slow, 0.1f, 45); // 4.5 blocks, slowly
        int fastSteps = walk(new MobSounds(null, STRIDE, 0.3f), fast, 0.5f, 9);  // 4.5 blocks, quickly

        assertEquals(slowSteps, fastSteps, "the same distance is the same number of steps...");
        assertEquals(4, slowSteps, "...one per block covered");
    }

    @Test
    void standingStillNeverSteps() {
        StubMob mob = grounded();
        MobSounds sounds = new MobSounds(null, STRIDE, 0.3f);

        assertEquals(0, walk(sounds, mob, 0.0f, 100));
    }

    /**
     * A mob genuinely off the ground — falling, flying — makes no footfalls. The tolerance below
     * covers the flicker of walking, not real airtime, so it has to expire.
     */
    @Test
    void sustainedAirtimeIsSilent() {
        StubMob mob = grounded();
        MobSounds sounds = new MobSounds(null, STRIDE, 0.3f);
        walk(sounds, mob, 0.4f, 2); // part-way to a step

        mob.setOnGround(false);
        walk(sounds, mob, 0.4f, 6); // burns through the grace

        assertEquals(0, walk(sounds, mob, 0.4f, 30), "a flying mob has nothing to step on");
    }

    /**
     * Ground contact flickers constantly as a mob waddles — measurably about half of all updates —
     * so a brief moment off the ground must not stop the footsteps. Treating it as "not walking"
     * silenced every mob in the game.
     */
    @Test
    void aMomentaryLossOfGroundContactDoesNotSilenceAMob() {
        StubMob mob = grounded();
        MobSounds sounds = new MobSounds(null, STRIDE, 0.3f);

        int steps = 0;
        for (int i = 0; i < 40; i++) {
            mob.setOnGround(i % 2 == 0); // airborne every other update, as a real mob is
            Vector3f position = mob.getPosition();
            mob.setPosition(new Vector3f(position.x + 0.25f, position.y, position.z));
            if (sounds.updateSounds(mob)) {
                steps++;
            }
        }

        assertTrue(steps > 0, "a waddling mob must still be heard");
    }

    /** Swimming has no footfalls, however much ground the mob covers crossing a pond. */
    @Test
    void aSwimmingMobIsSilent() {
        StubMob mob = grounded();
        mob.setSubmersion(0.8f);

        assertEquals(0, walk(new MobSounds(null, STRIDE, 0.3f), mob, 0.4f, 30));
    }

    /** Wading is walking: shallow water still has ground underfoot. */
    @Test
    void aWadingMobStillSteps() {
        StubMob mob = grounded();
        mob.setSubmersion(0.2f);

        assertTrue(walk(new MobSounds(null, STRIDE, 0.3f), mob, 0.4f, 30) > 0);
    }

    @Test
    void teleportsDoNotFireABurstOfSteps() {
        StubMob mob = grounded();
        MobSounds sounds = new MobSounds(null, STRIDE, 0.3f);
        sounds.updateSounds(mob); // establish a previous position

        mob.setPosition(new Vector3f(500, 64, 500));

        assertFalse(sounds.updateSounds(mob), "a network correction is not fifty footsteps");
    }

    @Test
    void theFirstUpdateOnlyEstablishesAPosition() {
        StubMob mob = grounded();
        mob.setPosition(new Vector3f(100, 64, 100));

        assertFalse(new MobSounds(null, STRIDE, 0.3f).updateSounds(mob),
                "a mob that has just spawned has not walked anywhere yet");
    }

    @Test
    void resetForgetsWhereTheMobWas() {
        StubMob mob = grounded();
        MobSounds sounds = new MobSounds(null, STRIDE, 0.3f);
        walk(sounds, mob, 0.4f, 2);

        sounds.reset();
        mob.setPosition(new Vector3f(50, 64, 50));

        assertFalse(sounds.updateSounds(mob), "the jump to the new position must not count as travel");
    }

    /** Stride and loudness come from the mob's own build, so no mob needs audio tuning. */
    @Test
    void footstepsAreSizedFromTheMobItself() {
        StubMob cow = new StubMob(EntityType.COW);
        StubMob chicken = new StubMob(EntityType.CHICKEN);

        int cowSteps = walk(MobSounds.forEntity(null, cow), grounded(cow), 0.2f, 30);
        int chickenSteps = walk(MobSounds.forEntity(null, chicken), grounded(chicken), 0.2f, 30);

        assertTrue(chickenSteps > cowSteps,
                "a chicken takes more, shorter steps over the same ground: "
                        + chickenSteps + " vs " + cowSteps);
    }

    private static StubMob grounded() {
        return grounded(new StubMob(EntityType.COW));
    }

    private static StubMob grounded(StubMob mob) {
        mob.setOnGround(true);
        return mob;
    }

    /** Walks the mob {@code updates} times, {@code perUpdate} blocks each, counting footsteps. */
    private static int walk(MobSounds sounds, StubMob mob, float perUpdate, int updates) {
        // A mob is updated where it stands before it starts moving; this update contributes no
        // travel and just gives the accumulator something to measure from.
        sounds.updateSounds(mob);

        int steps = 0;
        for (int i = 0; i < updates; i++) {
            Vector3f position = mob.getPosition();
            mob.setPosition(new Vector3f(position.x + perUpdate, position.y, position.z));
            if (sounds.updateSounds(mob)) {
                steps++;
            }
        }
        return steps;
    }
}
