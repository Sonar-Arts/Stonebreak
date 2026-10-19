package com.stonebreak.world.generation.biomes;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Desert gravel: rare, solid, clean-edged patches, decided per column (no chunk-order effects). */
class DesertGravelTest {

    private static final long SEED = 0x5EED_1234L;
    private static final int SPAN = 1024;   // 16 x 16 cells

    private static boolean[][] field(long seed) {
        boolean[][] g = new boolean[SPAN][SPAN];
        for (int x = 0; x < SPAN; x++) {
            for (int z = 0; z < SPAN; z++) {
                g[x][z] = DesertGravel.at(x - SPAN / 2, z - SPAN / 2, seed);
            }
        }
        return g;
    }

    @Test
    void patchesAreRareAndFewPerArea() {
        boolean[][] g = field(SEED);
        int gravel = 0;
        for (boolean[] row : g) for (boolean b : row) if (b) gravel++;
        double share = gravel / (double) (SPAN * SPAN);
        int patches = components(g);
        int cells = (SPAN / DesertGravel.CELL) * (SPAN / DesertGravel.CELL);
        System.out.printf(Locale.ROOT, "[desert-gravel] %.2f%% of sand, %d patches in %d cells%n",
            share * 100, patches, cells);
        // Was ~12% of desert sand, a patch every ~2.6 chunks.
        assertTrue(share > 0.003 && share < 0.025, "gravel share " + share);
        assertTrue(patches <= cells && patches > cells / 4, "patches " + patches + " of " + cells + " cells");
    }

    @Test
    void patchesAreSolidBlobsNotSpeckle() {
        boolean[][] g = field(SEED);
        int gravel = 0, interior = 0, lonely = 0;
        for (int x = 1; x < SPAN - 1; x++) {
            for (int z = 1; z < SPAN - 1; z++) {
                if (!g[x][z]) continue;
                gravel++;
                int n = (g[x + 1][z] ? 1 : 0) + (g[x - 1][z] ? 1 : 0) + (g[x][z + 1] ? 1 : 0) + (g[x][z - 1] ? 1 : 0);
                if (n == 4) interior++;
                if (n <= 1) lonely++;
            }
        }
        // Speckle (each column its own roll) puts most gravel on an edge; a blob's edge is its
        // perimeter only.
        assertTrue(interior > gravel * 0.5, "interior " + interior + " of " + gravel);
        assertTrue(lonely < gravel * 0.02, "stragglers " + lonely + " of " + gravel);
        // No pinholes: sand fully enclosed by gravel.
        for (int x = 1; x < SPAN - 1; x++) {
            for (int z = 1; z < SPAN - 1; z++) {
                if (!g[x][z] && g[x + 1][z] && g[x - 1][z] && g[x][z + 1] && g[x][z - 1]) {
                    throw new AssertionError("pinhole at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void aPatchNeverLeavesItsCell() {
        boolean[][] g = field(SEED);
        int c = DesertGravel.CELL;
        for (int x = 0; x < SPAN; x++) {
            for (int z = 0; z < SPAN; z++) {
                int wx = x - SPAN / 2, wz = z - SPAN / 2;
                if (g[x][z]) {
                    int fx = Math.floorMod(wx, c), fz = Math.floorMod(wz, c);
                    assertTrue(fx > 0 && fx < c - 1 && fz > 0 && fz < c - 1, "gravel on a cell edge at " + wx + "," + wz);
                }
            }
        }
    }

    @Test
    void theSeedMovesThePatches() {
        boolean[][] a = field(SEED), b = field(SEED + 1);
        int differ = 0;
        for (int x = 0; x < SPAN; x++) for (int z = 0; z < SPAN; z++) if (a[x][z] != b[x][z]) differ++;
        assertTrue(differ > 1000, "differ " + differ);
        assertEquals(DesertGravel.at(37, -91, SEED), DesertGravel.at(37, -91, SEED), "deterministic");
    }

    private static int components(boolean[][] g) {
        boolean[][] seen = new boolean[SPAN][SPAN];
        int count = 0;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < SPAN; x++) {
            for (int z = 0; z < SPAN; z++) {
                if (!g[x][z] || seen[x][z]) continue;
                count++;
                seen[x][z] = true;
                queue.add(new int[]{x, z});
                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    int[][] next = {{p[0] + 1, p[1]}, {p[0] - 1, p[1]}, {p[0], p[1] + 1}, {p[0], p[1] - 1}};
                    for (int[] q : next) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= SPAN || q[1] >= SPAN) continue;
                        if (g[q[0]][q[1]] && !seen[q[0]][q[1]]) {
                            seen[q[0]][q[1]] = true;
                            queue.add(q);
                        }
                    }
                }
            }
        }
        return count;
    }
}
