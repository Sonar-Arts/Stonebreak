package com.stonebreak.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeded randomness world generation stands on. The whole contract is in the name: the same
 * seed, position and feature must answer identically forever — across calls, across fresh
 * instances, across the two machines of a multiplayer session — while different features at the
 * same position stay independent streams, so tree placement can never be nudged by ore rolls.
 */
class DeterministicRandomTest {

    private static final long SEED = 424242L;

    @Test
    void theSamePositionAlwaysRollsTheSameValue() {
        DeterministicRandom random = new DeterministicRandom(SEED);

        assertEquals(random.getFloat(10, -7, "tree"), random.getFloat(10, -7, "tree"), 0.0f);
        assertEquals(random.getInt(10, -7, "tree", 100), random.getInt(10, -7, "tree", 100));
    }

    @Test
    void aFreshInstanceWithTheSameSeedAgrees() {
        DeterministicRandom first = new DeterministicRandom(SEED);
        DeterministicRandom second = new DeterministicRandom(SEED);

        assertEquals(first.getFloat(3, 4, "flower"), second.getFloat(3, 4, "flower"), 0.0f,
                "host and client seeding the same world must roll identical features");
        assertEquals(first.getFloat3D(3, 60, 4, "ore"), second.getFloat3D(3, 60, 4, "ore"), 0.0f);
    }

    @Test
    void differentSeedsDisagree() {
        DeterministicRandom a = new DeterministicRandom(1L);
        DeterministicRandom b = new DeterministicRandom(2L);

        int differing = 0;
        for (int x = 0; x < 32; x++) {
            if (a.getFloat(x, 0, "tree") != b.getFloat(x, 0, "tree")) {
                differing++;
            }
        }
        assertTrue(differing > 24, "two seeds should produce almost entirely different rolls");
    }

    @Test
    void featuresAreIndependentStreams() {
        DeterministicRandom random = new DeterministicRandom(SEED);

        assertNotEquals(random.getFloat(5, 5, "tree"), random.getFloat(5, 5, "ore"),
                "a feature name must select its own stream at the same position");
    }

    @Test
    void yMattersOnlyToThe3dStream() {
        DeterministicRandom random = new DeterministicRandom(SEED);

        assertNotEquals(random.getFloat3D(5, 10, 5, "ore"), random.getFloat3D(5, 11, 5, "ore"));
        assertEquals(random.getFloat(5, 5, "ore"), random.getFloat(5, 5, "ore"), 0.0f);
    }

    @Test
    void aReturnedRandomReplaysItsSequence() {
        DeterministicRandom random = new DeterministicRandom(SEED);
        Random first = random.getRandomForPosition(8, 8, "decor");
        Random second = random.getRandomForPosition(8, 8, "decor");

        for (int i = 0; i < 10; i++) {
            assertEquals(first.nextInt(), second.nextInt(),
                    "draw " + i + " diverged — the position seed is not stable");
        }
    }

    @Test
    void probabilityExtremesAreAbsolute() {
        DeterministicRandom random = new DeterministicRandom(SEED);

        for (int x = 0; x < 64; x++) {
            assertFalse(random.shouldGenerate(x, 0, "never", 0.0f),
                    "probability 0 must never generate");
            assertTrue(random.shouldGenerate(x, 0, "always", 1.0f),
                    "probability 1 must always generate");
        }
    }

    @Test
    void boundedIntsStayInBounds() {
        DeterministicRandom random = new DeterministicRandom(SEED);

        for (int x = 0; x < 64; x++) {
            int value = random.getInt(x, -x, "loot", 7);
            assertTrue(value >= 0 && value < 7, "rolled " + value + " outside [0,7)");
        }
    }
}
