package com.openmason.engine.rendering.sky.clouds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud coverage grid's three promises: deterministic per seed (hosts on both ends of a
 * connection can agree on a sky), toroidal (the mesh tiles edge-to-edge with no visible seam),
 * and covering roughly the fraction asked for. None of this is visible in a unit smaller than
 * "the sky looks wrong", which is why it gets pinned here.
 */
class CloudPatternTest {

    private static final int SIZE = 32;

    private static float coverageOf(CloudPattern pattern) {
        int occupied = 0;
        for (int x = 0; x < pattern.getSize(); x++) {
            for (int z = 0; z < pattern.getSize(); z++) {
                if (pattern.isCloud(x, z)) {
                    occupied++;
                }
            }
        }
        return occupied / (float) (pattern.getSize() * pattern.getSize());
    }

    @Test
    void theSameSeedAlwaysBuildsTheSameSky() {
        CloudPattern a = new CloudPattern(SIZE, 0.4f, 12345L);
        CloudPattern b = new CloudPattern(SIZE, 0.4f, 12345L);

        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                assertEquals(a.isCloud(x, z), b.isCloud(x, z),
                        "cell (" + x + "," + z + ") differed between identically-seeded patterns");
            }
        }
    }

    @Test
    void differentSeedsBuildDifferentSkies() {
        CloudPattern a = new CloudPattern(SIZE, 0.4f, 1L);
        CloudPattern b = new CloudPattern(SIZE, 0.4f, 2L);

        int differing = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (a.isCloud(x, z) != b.isCloud(x, z)) {
                    differing++;
                }
            }
        }
        assertTrue(differing > 0, "two seeds producing identical skies would mean the seed is ignored");
    }

    @Test
    void queriesWrapLikeATorus() {
        CloudPattern pattern = new CloudPattern(SIZE, 0.4f, 7L);

        for (int i = 0; i < SIZE; i++) {
            assertEquals(pattern.isCloud(0, i), pattern.isCloud(SIZE, i),
                    "the east edge must continue into the west edge");
            assertEquals(pattern.isCloud(i, 0), pattern.isCloud(i, SIZE));
            assertEquals(pattern.isCloud(SIZE - 1, i), pattern.isCloud(-1, i),
                    "negative queries must wrap the same way");
        }
    }

    @Test
    void occupancyRoughlyMatchesTheRequestedCoverage() {
        // Percentile thresholding is approximate on a small grid; a generous band
        // still catches an inverted or ignored coverage parameter outright.
        assertEquals(0.4f, coverageOf(new CloudPattern(SIZE, 0.4f, 3L)), 0.15f);
        assertTrue(coverageOf(new CloudPattern(SIZE, 0.1f, 3L))
                        < coverageOf(new CloudPattern(SIZE, 0.7f, 3L)),
                "more requested coverage must mean more cloud");
    }

    @Test
    void extremeCoverageValuesStaySane() {
        assertTrue(coverageOf(new CloudPattern(SIZE, 0.0f, 5L)) < 0.05f,
                "a clear sky must be essentially empty");
        assertTrue(coverageOf(new CloudPattern(SIZE, 1.0f, 5L)) > 0.95f,
                "an overcast sky must be essentially full");
    }
}
