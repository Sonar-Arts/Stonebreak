package com.stonebreak.mobs.entities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dynamic passive-mob cap = density × eligible (loaded, feature-populated) chunks, targeting
 * ~35 mobs across a default-view 17×17 (=289) chunk area and scaling with view distance.
 */
class EntitySpawnerCapTest {

    /** Eligible chunks for a single player's (2r+1)² view square. */
    private static int squareChunks(int viewDistance) {
        int side = 2 * viewDistance + 1;
        return side * side;
    }

    @Test
    void noEligibleChunksMeansNoCap() {
        assertEquals(0, EntitySpawner.computeCap(0));
    }

    @Test
    void defaultViewTargetsAboutThirtyFive() {
        // view distance 8 -> 17x17 = 289 chunks -> the tuned target.
        assertEquals(289, squareChunks(8));
        assertEquals(35, EntitySpawner.computeCap(squareChunks(8)));
    }

    @Test
    void capScalesWithViewDistance() {
        assertEquals(10, EntitySpawner.computeCap(squareChunks(4)));   // 81 chunks
        assertEquals(35, EntitySpawner.computeCap(squareChunks(8)));   // 289 chunks
        assertEquals(76, EntitySpawner.computeCap(squareChunks(12)));  // 625 chunks
        assertEquals(291, EntitySpawner.computeCap(squareChunks(24))); // 2401 chunks (max view)
    }

    @Test
    void capIsMonotonicNonDecreasing() {
        int prev = -1;
        for (int chunks = 0; chunks <= 2401; chunks += 17) {
            int cap = EntitySpawner.computeCap(chunks);
            assertTrue(cap >= prev, "cap must not decrease as eligible chunks grow");
            prev = cap;
        }
    }
}
