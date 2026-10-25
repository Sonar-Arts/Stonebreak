package com.stonebreak.mobs.entities.ai.perception;

import com.stonebreak.mobs.entities.EntityType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noise as loudness with falloff (#321): reference ears reproduce the old fixed radii, sharper ears
 * hear everything further off, and the sound bus hands each listener every sound exactly once.
 */
class LoudnessTest {

    private static final float REF = Loudness.REFERENCE_HEARING_RANGE;

    @Test
    void referenceEarsKeepTheOldWalkAndSprintRadii() {
        assertEquals(5f, Loudness.audibleRadius(Loudness.WALK, REF), 1e-4f);
        assertEquals(10f, Loudness.audibleRadius(Loudness.SPRINT, REF), 1e-4f);
    }

    @Test
    void aGoblinHearsASprintAtItsFullHearingRange() {
        float goblinEars = PerceptionProfile.forType(EntityType.GOBLIN).hearingRange();
        assertEquals(goblinEars, Loudness.audibleRadius(Loudness.SPRINT, goblinEars), 1e-4f);
    }

    @Test
    void aKillTheVictimNeverSawComingIsOnlyAsLoudAsAWalk() {
        assertEquals(Loudness.WALK, Loudness.ofDeath(true));
        assertEquals(Loudness.DEATH, Loudness.ofDeath(false));
    }

    @Test
    void eachStealthPointIsWorthTenPercentAndNoneMakeAPlayerSilent() {
        assertEquals(1f, StealthSkill.factor(0f));
        assertEquals(1f / 1.1f, StealthSkill.factor(1f), 1e-5f);
        assertEquals(0.5f, StealthSkill.factor(10f), 1e-5f);
        assertTrue(StealthSkill.factor(1000f) > 0f);
        assertEquals(1f, StealthSkill.factor(-3f), "no negative skill");
    }

    @Test
    void theGoblinProfileIsReadFromItsDataFile() {
        PerceptionProfile goblin = PerceptionProfile.forType(EntityType.GOBLIN);
        assertNotSame(PerceptionProfile.DEFAULT, goblin);
        assertEquals(12f, goblin.visionRange());
        assertEquals(16f, goblin.hearingRange());
        assertEquals(20f, goblin.shoutRange());
        assertEquals(2f, goblin.alertness().alerted());
        assertSame(PerceptionProfile.DEFAULT, PerceptionProfile.forType(EntityType.COW), "unlisted: the default");
    }

    @Test
    void aProfileFileThatIsNotThereGivesEveryTypeTheDefault() {
        assertTrue(PerceptionProfile.load("mobs/no-such-profiles.json").isEmpty());
    }

    @Test
    void aSoundFadesToNothingAtItsEdge() {
        assertEquals(1f, Loudness.strength(Loudness.SPRINT, 0f, REF), 1e-4f);
        assertEquals(0.5f, Loudness.strength(Loudness.SPRINT, 5f, REF), 1e-4f);
        assertEquals(0f, Loudness.strength(Loudness.SPRINT, 10f, REF));
        assertEquals(0f, Loudness.strength(0f, 0f, REF), "silence is silent even point blank");
    }

    @Test
    void movementIsSilentStillQuietStealthedAndADodgeCutsThrough() {
        assertEquals(0f, Loudness.ofMovement(false, 1f, true, 0f, false));
        assertEquals(Loudness.WALK, Loudness.ofMovement(false, 1f, false, 4f, false));
        assertEquals(Loudness.SPRINT, Loudness.ofMovement(false, 1f, true, 6f, false));
        assertEquals(0.2f, Loudness.ofMovement(true, 0.2f, false, 3f, false), 1e-6f);
        assertEquals(Loudness.DODGE, Loudness.ofMovement(true, 0.2f, false, 3f, true));
    }

    @Test
    void theBusHandsEachListenerEverySoundOnceThenForgetsIt() {
        long[] clock = {0L};
        NoiseBus bus = new NoiseBus(() -> clock[0]);
        long heardUpTo = bus.latest();
        bus.emit(new Vector3f(1, 2, 3), Loudness.BREAK_BLOCK);
        bus.emit(new Vector3f(), 0f); // silent: never posted

        List<NoiseBus.NoiseEvent> heard = new ArrayList<>();
        bus.heardSince(heardUpTo, heard::add);
        assertEquals(1, heard.size());
        heardUpTo = bus.latest();

        heard.clear();
        bus.heardSince(heardUpTo, heard::add);
        assertTrue(heard.isEmpty(), "already heard");

        clock[0] += NoiseBus.RETENTION_NANOS + 1;
        heard.clear();
        bus.heardSince(0L, heard::add);
        assertTrue(heard.isEmpty(), "long gone");
    }
}
