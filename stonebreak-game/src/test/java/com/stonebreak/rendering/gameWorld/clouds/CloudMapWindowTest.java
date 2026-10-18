package com.stonebreak.rendering.gameWorld.clouds;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud map is only ever patched, never rebuilt, so a window move must
 * expose exactly the cells that are new — a missed cell shows the cloud from
 * one map-width away, a duplicate wastes noise — and wrapped uploads must land
 * each cell on its own texel.
 */
class CloudMapWindowTest {

    private static final int SIZE = 16;

    @Test
    void exposedCellsAreExactlyTheNewOnes() {
        int[][] moves = {{0, 0, 3, 0}, {0, 0, -2, 0}, {0, 0, 0, 5}, {0, 0, 0, -4},
                {0, 0, 3, -7}, {5, -9, -1, 2}, {0, 0, 15, 15}, {0, 0, 40, 1}};
        for (int[] m : moves) {
            Set<Long> expected = window(m[2], m[3]);
            if (Math.abs(m[2] - m[0]) < SIZE && Math.abs(m[3] - m[1]) < SIZE) {
                expected.removeAll(window(m[0], m[1]));
            }
            Set<Long> got = new HashSet<>();
            int count = 0;
            for (CloudMapWindow.Rect r : CloudMapWindow.exposed(m[0], m[1], m[2], m[3], SIZE)) {
                for (int x = r.x(); x < r.x() + r.w(); x++)
                    for (int z = r.z(); z < r.z() + r.h(); z++) {
                        got.add(key(x, z));
                        count++;
                    }
            }
            assertEquals(expected, got, "move " + java.util.Arrays.toString(m));
            assertEquals(got.size(), count, "no cell twice, move " + java.util.Arrays.toString(m));
        }
    }

    @Test
    void wrappedPiecesLandOnTheirOwnTexels() {
        CloudMapWindow.Rect cells = new CloudMapWindow.Rect(-5, 27, SIZE, 9);
        List<CloudMapWindow.Rect[]> pieces = CloudMapWindow.wrap(cells, SIZE);
        Set<Long> texels = new HashSet<>();
        int area = 0;
        for (CloudMapWindow.Rect[] p : pieces) {
            assertEquals(p[0].w(), p[1].w());
            assertEquals(p[0].h(), p[1].h());
            assertTrue(p[1].x() >= 0 && p[1].x() + p[1].w() <= SIZE);
            assertTrue(p[1].z() >= 0 && p[1].z() + p[1].h() <= SIZE);
            assertEquals(Math.floorMod(p[0].x(), SIZE), p[1].x());
            assertEquals(Math.floorMod(p[0].z(), SIZE), p[1].z());
            for (int x = 0; x < p[1].w(); x++)
                for (int z = 0; z < p[1].h(); z++) texels.add(key(p[1].x() + x, p[1].z() + z));
            area += p[0].area();
        }
        assertEquals(cells.area(), area);
        assertEquals(cells.area(), texels.size());
    }

    private static Set<Long> window(int x0, int z0) {
        Set<Long> s = new HashSet<>();
        for (int x = x0; x < x0 + SIZE; x++)
            for (int z = z0; z < z0 + SIZE; z++) s.add(key(x, z));
        return s;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
