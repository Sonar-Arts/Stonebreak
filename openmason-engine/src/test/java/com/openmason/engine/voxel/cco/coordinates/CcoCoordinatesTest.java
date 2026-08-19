package com.openmason.engine.voxel.cco.coordinates;

import com.openmason.engine.voxel.VoxelWorldConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordinate arithmetic every chunk read goes through. The classic failure lives on the
 * negative side of the origin: integer division rounds toward zero, so a naive conversion puts
 * world X = -1 in chunk 0 instead of chunk -1 and every block west of spawn reads from the wrong
 * chunk. Both converters and the flat-index round trip are pinned against a configured world.
 * {@link CcoBounds} shares the same global config, so its edge tests live here too.
 */
class CcoCoordinatesTest {

    private static final int CHUNK = 16;
    private static final int HEIGHT = 256;

    @BeforeAll
    static void configureWorld() {
        CcoBounds.configure(new VoxelWorldConfig(CHUNK, HEIGHT, 64));
    }

    @Test
    void localToWorldAndBackIsExactInEveryChunk() {
        for (int chunk : new int[] {-3, -1, 0, 2}) {
            for (int local : new int[] {0, 7, CHUNK - 1}) {
                int world = CcoCoordinates.localToWorldX(chunk, local);
                assertEquals(chunk, CcoCoordinates.worldToChunkX(world));
                assertEquals(local, CcoCoordinates.worldToLocalX(world));
                assertEquals(chunk, CcoCoordinates.worldToChunkZ(
                        CcoCoordinates.localToWorldZ(chunk, local)));
            }
        }
    }

    @Test
    void theBlockWestOfSpawnLivesInChunkMinusOne() {
        assertEquals(-1, CcoCoordinates.worldToChunkX(-1),
                "truncating division would answer 0 and read the wrong chunk");
        assertEquals(CHUNK - 1, CcoCoordinates.worldToLocalX(-1));
        assertEquals(-1, CcoCoordinates.worldToChunkX(-CHUNK));
        assertEquals(-2, CcoCoordinates.worldToChunkX(-CHUNK - 1));
        assertEquals(0, CcoCoordinates.worldToLocalX(-CHUNK));
    }

    @Test
    void flatIndexRoundTripsAndNeverCollides() {
        boolean[] seen = new boolean[CHUNK * HEIGHT * CHUNK];
        for (int x : new int[] {0, 5, CHUNK - 1}) {
            for (int y : new int[] {0, 100, HEIGHT - 1}) {
                for (int z : new int[] {0, 9, CHUNK - 1}) {
                    int index = CcoCoordinates.toIndex(x, y, z);
                    assertTrue(index >= 0 && index < seen.length);
                    assertFalse(seen[index], "two cells mapped to index " + index);
                    seen[index] = true;
                    assertEquals(x, CcoCoordinates.indexToX(index));
                    assertEquals(y, CcoCoordinates.indexToY(index));
                    assertEquals(z, CcoCoordinates.indexToZ(index));
                    assertArrayEquals(new int[] {x, y, z},
                            CcoCoordinates.indexToCoordinate(index));
                }
            }
        }
    }

    @Test
    void customDimensionIndexingMatchesTheLayoutRule() {
        assertEquals(2 * (8 * 4) + 3 * 4 + 1, CcoCoordinates.toIndex(2, 3, 1, 8, 4));
    }

    @Test
    void chunkDistancesAreWhatTheyClaim() {
        assertEquals(7, CcoCoordinates.manhattanDistance(0, 0, 3, 4));
        assertEquals(25, CcoCoordinates.distanceSquared(0, 0, 3, 4));
        assertEquals(7, CcoCoordinates.manhattanDistance(-1, -2, 2, -6),
                "distances must survive negative chunk coordinates");
    }

    // ── CcoBounds edges (same configured world) ──────────────────────────────

    @Test
    void boundsAcceptTheCellsAndRejectTheFenceposts() {
        assertTrue(CcoBounds.isInBounds(0, 0, 0));
        assertTrue(CcoBounds.isInBounds(CHUNK - 1, HEIGHT - 1, CHUNK - 1));
        assertFalse(CcoBounds.isInBounds(CHUNK, 0, 0), "chunkSize itself is the first invalid cell");
        assertFalse(CcoBounds.isInBounds(0, HEIGHT, 0));
        assertFalse(CcoBounds.isInBounds(-1, 0, 0));
    }

    @Test
    void edgeDetectionFlagsExactlyTheBorderRing() {
        assertTrue(CcoBounds.isOnChunkEdge(0, 5));
        assertTrue(CcoBounds.isOnChunkEdge(5, CHUNK - 1));
        assertFalse(CcoBounds.isOnChunkEdge(5, 5));
    }

    @Test
    void clampingPullsStraysBackInside() {
        assertEquals(0, CcoBounds.clampX(-10));
        assertEquals(CHUNK - 1, CcoBounds.clampX(CHUNK + 3));
        assertEquals(HEIGHT - 1, CcoBounds.clampY(HEIGHT * 2));
        assertEquals(5, CcoBounds.clampZ(5));
    }
}
