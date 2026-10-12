package com.stonebreak.world.generation.trees;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;

/**
 * Cypress shape invariants: the tree fits the footprint the chunk scheduler reserves, its
 * wood is one connected piece, its canopy reads as separate layers, and sizes vary.
 * Leaf-decay reach is structural ({@link TreeShapeBuffer#flushAnchored}), so it is not
 * re-proven here.
 */
class CypressTreeTest {

    private static final int[][] ORTHOGONALS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int TREES = 300;

    private record Cell(int x, int y, int z) {}

    private static Map<Cell, BlockType> grow(int x, int y, int z) {
        Map<Cell, BlockType> blocks = new HashMap<>();
        CypressTree.place((bx, by, bz, type) -> blocks.put(new Cell(bx, by, bz), type), x, y, z);
        return blocks;
    }

    private static int[] position(int i) {
        return new int[]{(i * 37) % 101 - 50, 64 + (i % 7), (i * 91) % 103 - 51};
    }

    @Test
    void staysInsideTheScheduledFootprint() {
        for (int i = 0; i < TREES; i++) {
            int[] p = position(i);
            for (Cell c : grow(p[0], p[1], p[2]).keySet()) {
                assertTrue(Math.abs(c.x() - p[0]) <= CypressTree.LEAF_RADIUS
                        && Math.abs(c.z() - p[2]) <= CypressTree.LEAF_RADIUS,
                    () -> "cell " + c + " outside LEAF_RADIUS of " + p[0] + "," + p[2]);
                assertTrue(c.y() >= p[1] && c.y() < p[1] + CypressTree.MAX_HEIGHT,
                    () -> "cell " + c + " outside MAX_HEIGHT above y " + p[1]);
            }
        }
    }

    @Test
    void everyLogIsConnectedToTheTrunkBase() {
        for (int i = 0; i < TREES; i++) {
            int[] p = position(i);
            Map<Cell, BlockType> blocks = grow(p[0], p[1], p[2]);
            Set<Cell> reached = new HashSet<>();
            ArrayDeque<Cell> frontier = new ArrayDeque<>();
            Cell base = new Cell(p[0], p[1], p[2]);
            assertTrue(blocks.get(base) == BlockType.CYPRESS_LOG, "trunk must start at the origin");
            reached.add(base);
            frontier.add(base);
            while (!frontier.isEmpty()) {
                Cell c = frontier.poll();
                for (int[] d : ORTHOGONALS) {
                    Cell n = new Cell(c.x() + d[0], c.y() + d[1], c.z() + d[2]);
                    if (blocks.get(n) == BlockType.CYPRESS_LOG && reached.add(n)) {
                        frontier.add(n);
                    }
                }
            }
            long logs = blocks.values().stream().filter(t -> t == BlockType.CYPRESS_LOG).count();
            assertTrue(reached.size() == logs, "floating branch log in tree " + i);
        }
    }

    @Test
    void canopyIsLayered() {
        for (int i = 0; i < TREES; i++) {
            int[] p = position(i);
            TreeSet<Integer> leafLevels = new TreeSet<>();
            grow(p[0], p[1], p[2]).forEach((c, t) -> {
                if (t == BlockType.CYPRESS_LEAVES) leafLevels.add(c.y());
            });
            // A band is a run of consecutive leafy levels; tiers are separated by a clear level.
            int bands = 0;
            Integer previous = null;
            for (int y : leafLevels) {
                if (previous == null || y > previous + 1) bands++;
                previous = y;
            }
            int finalBands = bands;
            assertTrue(bands >= 2, () -> "tree " + p[0] + "," + p[1] + "," + p[2]
                + " has " + finalBands + " canopy layer(s)");
        }
    }

    @Test
    void sizesVary() {
        Set<Integer> heights = new HashSet<>();
        for (int i = 0; i < TREES; i++) {
            int[] p = position(i);
            Map<Cell, BlockType> blocks = grow(p[0], p[1], p[2]);
            int top = p[1];
            while (blocks.get(new Cell(p[0], top + 1, p[2])) == BlockType.CYPRESS_LOG) top++;
            heights.add(top - p[1] + 1);
        }
        assertTrue(heights.size() >= 10, "trunk heights: " + heights);
        assertTrue(heights.stream().mapToInt(h -> h).min().getAsInt() <= 12
            && heights.stream().mapToInt(h -> h).max().getAsInt() >= 20, "trunk heights: " + heights);
    }
}
