package com.stonebreak.world.generation.water;

import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Window assembly, offline. The HTTP path needs a live bridge, but the part
 * that can silently misplace a river — stitching chunks into a window and
 * agreeing with the caller about where each cell sits in the world — is pure
 * arithmetic and is what these cover.
 *
 * <p>Chunks are pre-populated with a value that encodes each cell's absolute
 * world position, so a misplaced blit shows up as a wrong number rather than
 * as plausible terrain.
 */
class CoarseDemTest {

    private static final int CHUNK = 512;
    private static final int CELL = 16;
    private static final int CELLS_PER_CHUNK = CHUNK / CELL;

    private static DiffusionBridgeConfig config() {
        DiffusionBridgeConfig base = DiffusionBridgeConfig.fromSystemProperties();
        return new DiffusionBridgeConfig(
                base.baseUrl(), base.tileSizeBlocks(), base.connectTimeoutMs(),
                base.requestTimeoutMs(), base.maxRetries(), base.initialBackoffMs(),
                base.maxBackoffMs(), base.maxCachedTiles(), base.unreachableGraceMs(),
                base.hydrologySolveGraceMs(), base.solvePollIntervalMs(),
                CHUNK, CELL, 64);
    }

    /** Height encoding the cell's own world origin, so placement is checkable. */
    private static float expected(long worldX, long worldZ) {
        return worldX * 0.5f + worldZ * 0.001f;
    }

    private static CoarseDem demWithChunks(long cx0, long cx1, long cz0, long cz1) {
        CoarseDem dem = new CoarseDem(config(), 1234L);
        for (long cx = cx0; cx <= cx1; cx++) {
            for (long cz = cz0; cz <= cz1; cz++) {
                float[] cells = new float[CELLS_PER_CHUNK * CELLS_PER_CHUNK];
                for (int i = 0; i < CELLS_PER_CHUNK; i++) {
                    for (int j = 0; j < CELLS_PER_CHUNK; j++) {
                        cells[i * CELLS_PER_CHUNK + j] =
                                expected(cx * CHUNK + (long) i * CELL, cz * CHUNK + (long) j * CELL);
                    }
                }
                dem.putChunkForTesting(cx, cz, cells);
            }
        }
        return dem;
    }

    @Test
    void windowStitchesChunksAtTheRightWorldPositions() {
        CoarseDem dem = demWithChunks(-2, 2, -2, 2);
        CoarseDem.Window w = dem.window(0, 0, 1024);

        assertEquals(CELL, w.cellBlocks());
        assertTrue((long) w.cellCount() * CELL >= 1024, "window covers the requested span");
        assertEquals(w.cellCount() * w.cellCount(), w.cells().length);

        for (int i = 0; i < w.cellCount(); i++) {
            for (int j = 0; j < w.cellCount(); j++) {
                long worldX = w.originX() + (long) i * CELL;
                long worldZ = w.originZ() + (long) j * CELL;
                assertEquals(expected(worldX, worldZ), w.cells()[i * w.cellCount() + j], 1e-3f,
                        "cell (" + i + "," + j + ") came from the wrong chunk");
            }
        }
    }

    @Test
    void windowsSpanningAChunkBorderAreContinuous() {
        // Centred on a chunk corner, so every quadrant comes from a different
        // chunk — the case where a blit off-by-one would show up as a step.
        CoarseDem dem = demWithChunks(-2, 2, -2, 2);
        CoarseDem.Window w = dem.window(CHUNK, CHUNK, 1024);
        for (int i = 0; i < w.cellCount(); i++) {
            for (int j = 0; j < w.cellCount(); j++) {
                long worldX = w.originX() + (long) i * CELL;
                long worldZ = w.originZ() + (long) j * CELL;
                assertEquals(expected(worldX, worldZ), w.cells()[i * w.cellCount() + j], 1e-3f);
            }
        }
    }

    @Test
    void negativeCoordinatesTileContiguously() {
        CoarseDem dem = demWithChunks(-3, 1, -3, 1);
        CoarseDem.Window w = dem.window(-CHUNK, -CHUNK, 1024);
        for (int i = 0; i < w.cellCount(); i++) {
            for (int j = 0; j < w.cellCount(); j++) {
                long worldX = w.originX() + (long) i * CELL;
                long worldZ = w.originZ() + (long) j * CELL;
                assertEquals(expected(worldX, worldZ), w.cells()[i * w.cellCount() + j], 1e-3f,
                        "negative-side cell (" + i + "," + j + ") is misplaced");
            }
        }
    }

    @Test
    void overlappingWindowsAgreeOnSharedGround() {
        // The seam rule, at this layer: the same world cell must carry the same
        // value whichever window contains it, or two tiles would route the same
        // river differently.
        CoarseDem dem = demWithChunks(-3, 3, -3, 3);
        CoarseDem.Window a = dem.window(0, 0, 1024);
        CoarseDem.Window b = dem.window(320, 176, 1024);

        int shared = 0;
        for (int i = 0; i < a.cellCount(); i++) {
            for (int j = 0; j < a.cellCount(); j++) {
                long worldX = a.originX() + (long) i * CELL;
                long worldZ = a.originZ() + (long) j * CELL;
                int bi = (int) ((worldX - b.originX()) / CELL);
                int bj = (int) ((worldZ - b.originZ()) / CELL);
                if (bi < 0 || bi >= b.cellCount() || bj < 0 || bj >= b.cellCount()) {
                    continue;
                }
                shared++;
                assertEquals(a.cells()[i * a.cellCount() + j],
                        b.cells()[bi * b.cellCount() + bj], 0.0f,
                        "windows disagree at world (" + worldX + "," + worldZ + ")");
            }
        }
        assertTrue(shared > 1000, "the two windows actually overlap (" + shared + " cells)");
    }

    @Test
    void originSnapsToTheAbsoluteCellLattice() {
        // Requested centres that are not cell-aligned must still produce a
        // lattice-aligned origin, otherwise the same ground would be sampled at
        // different offsets by different tiles.
        CoarseDem dem = demWithChunks(-2, 2, -2, 2);
        for (long centre : new long[] {0, 1, 7, 15, -1, -9, 333}) {
            CoarseDem.Window w = dem.window(centre, centre, 1024);
            assertEquals(0, Math.floorMod(w.originX(), CELL), "origin X off-lattice");
            assertEquals(0, Math.floorMod(w.originZ(), CELL), "origin Z off-lattice");
        }
    }

    @Test
    void reusesCachedChunksAcrossNearbyWindows() {
        CoarseDem dem = demWithChunks(-2, 2, -2, 2);
        dem.window(0, 0, 1024);
        int afterFirst = dem.cachedChunkCount();
        dem.window(CELL, CELL, 1024);
        assertEquals(afterFirst, dem.cachedChunkCount(),
                "a nearby window must not add chunks — it would mean re-fetching");
    }

    @Test
    void rejectsChunkAndCellSizesThatDoNotDivide() {
        DiffusionBridgeConfig base = config();
        DiffusionBridgeConfig bad = new DiffusionBridgeConfig(
                base.baseUrl(), base.tileSizeBlocks(), base.connectTimeoutMs(),
                base.requestTimeoutMs(), base.maxRetries(), base.initialBackoffMs(),
                base.maxBackoffMs(), base.maxCachedTiles(), base.unreachableGraceMs(),
                base.hydrologySolveGraceMs(), base.solvePollIntervalMs(),
                100, 16, 64);
        assertThrows(IllegalArgumentException.class, () -> new CoarseDem(bad, 1L));
    }

    @Test
    void distinctGroundProducesDistinctValues() {
        // Guards the guard: if `expected` collapsed to a constant the placement
        // assertions above would pass on any arrangement of chunks.
        assertNotEquals(expected(0, 0), expected(CELL, 0));
        assertNotEquals(expected(0, 0), expected(0, CELL));
    }
}
