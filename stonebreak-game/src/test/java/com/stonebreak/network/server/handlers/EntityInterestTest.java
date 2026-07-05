package com.stonebreak.network.server.handlers;

import org.junit.jupiter.api.Test;

import static com.stonebreak.network.server.handlers.ServerEntityHandler.withinChunkRadius;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interest math for per-player entity replication: Chebyshev chunk-radius membership,
 * matching the view shape chunk streaming uses. The enter radius is the player's view
 * distance; the exit radius adds {@code INTEREST_EXIT_MARGIN_CHUNKS} of hysteresis.
 */
class EntityInterestTest {

    @Test
    void sameChunkIsAlwaysWithinAnyRadius() {
        assertTrue(withinChunkRadius(5f, 5f, 12f, 12f, 0));
    }

    @Test
    void chebyshevShapeUsesMaxAxisNotEuclidean() {
        // Player chunk (0,0); entity chunk (3,3): Chebyshev distance 3 (Euclidean ~4.2).
        assertTrue(withinChunkRadius(3 * 16f, 3 * 16f, 0f, 0f, 3));
        assertFalse(withinChunkRadius(3 * 16f, 3 * 16f, 0f, 0f, 2));
    }

    @Test
    void boundaryIsInclusive() {
        // Entity exactly `radius` chunks away is IN interest.
        assertTrue(withinChunkRadius(8 * 16f, 0f, 0f, 0f, 8));
        assertFalse(withinChunkRadius(9 * 16f, 0f, 0f, 0f, 8));
    }

    @Test
    void negativeCoordinatesFloorToTheCorrectChunk() {
        // x = -0.5 is chunk -1, not chunk 0 (floorDiv semantics).
        assertTrue(withinChunkRadius(-0.5f, -0.5f, -8f, -8f, 0));
        // Entity at chunk -1, player at chunk 0: distance 1.
        assertFalse(withinChunkRadius(-0.5f, 0f, 0.5f, 0f, 0));
        assertTrue(withinChunkRadius(-0.5f, 0f, 0.5f, 0f, 1));
    }

    @Test
    void positionsWithinTheSameChunkDoNotChangeDistance() {
        // Anywhere inside chunk 2 vs anywhere inside chunk 0 is always distance 2.
        assertTrue(withinChunkRadius(32.1f, 0f, 15.9f, 0f, 2));
        assertFalse(withinChunkRadius(47.9f, 0f, 0.1f, 0f, 1));
    }

    @Test
    void exitHysteresisRadiusIsWiderThanEnter() {
        int view = 8;
        int exit = view + ServerEntityHandler.INTEREST_EXIT_MARGIN_CHUNKS;
        float ex = (view + 1) * 16f; // one chunk past enter: outside enter, inside exit
        assertFalse(withinChunkRadius(ex, 0f, 0f, 0f, view));
        assertTrue(withinChunkRadius(ex, 0f, 0f, 0f, exit));
    }
}
