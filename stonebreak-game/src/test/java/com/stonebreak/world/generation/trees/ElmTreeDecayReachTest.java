package com.stonebreak.world.generation.trees;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.leaves.LeafDecaySystem;

/**
 * Regression for elm canopies decaying: every leaf of every generated elm must be
 * within {@link LeafDecaySystem#DECAY_RADIUS} orthogonal leaf/log steps of a log —
 * the exact reachability {@code LeafDecaySystem} applies on a chunk-load rescan.
 * Elms are the widest tree (seven canopy layers above the trunk top, lower radius 4),
 * and before the hidden canopy core their upper canopy and top cap sat 5–7 steps
 * from the nearest log and vanished on load.
 */
class ElmTreeDecayReachTest {

    private static final int[][] ORTHOGONALS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** In-memory sink recording what the shape generator wrote. */
    private static final class GridSink implements TreeBlockSink {
        final Map<Long, BlockType> blocks = new HashMap<>();

        @Override
        public void placeBlock(int x, int y, int z, BlockType type) {
            blocks.put(key(x, y, z), type);
        }
    }

    private static int kx(long k) { return (int) ((k >> 40) & 0xFFFFF) - 512; }
    private static int ky(long k) { return (int) ((k >> 20) & 0xFFFFF) - 512; }
    private static int kz(long k) { return (int) (k & 0xFFFFF) - 512; }

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    @Test
    void everyLeafOfEveryElmIsWithinDecayReachOfALog() {
        List<String> failures = new ArrayList<>();
        // Many positions so all four variants and all random branch/thinning rolls are hit.
        for (int i = 0; i < 400; i++) {
            int x = (i * 37) % 101 - 50;
            int z = (i * 91) % 103 - 51;
            int y = 60 + (i % 7);
            GridSink sink = new GridSink();
            ElmTree.place(sink, x, y, z);

            Set<Long> reached = floodFromLogs(sink.blocks);
            for (Map.Entry<Long, BlockType> e : sink.blocks.entrySet()) {
                if (e.getValue().isLeaves() && !reached.contains(e.getKey())) {
                    long k = e.getKey();
                    failures.add("tree@(" + x + "," + y + "," + z + ") leaf offset ("
                        + (kx(k) - x) + "," + (ky(k) - y) + "," + (kz(k) - z) + ")");
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> failures.size() + " unanchored elm leaves, e.g. "
            + String.join("; ", failures.subList(0, Math.min(12, failures.size()))));
    }

    /** Multi-source BFS from every log through logs/leaves, depth-capped at DECAY_RADIUS. */
    private static Set<Long> floodFromLogs(Map<Long, BlockType> blocks) {
        Set<Long> reached = new HashSet<>();
        ArrayDeque<long[]> frontier = new ArrayDeque<>();
        for (Map.Entry<Long, BlockType> e : blocks.entrySet()) {
            if (e.getValue().isLog()) {
                reached.add(e.getKey());
                frontier.add(new long[]{e.getKey(), 0});
            }
        }
        while (!frontier.isEmpty()) {
            long[] node = frontier.poll();
            if (node[1] >= LeafDecaySystem.DECAY_RADIUS) {
                continue;
            }
            int x = (int) ((node[0] >> 40) & 0xFFFFF) - 512;
            int y = (int) ((node[0] >> 20) & 0xFFFFF) - 512;
            int z = (int) (node[0] & 0xFFFFF) - 512;
            for (int[] d : ORTHOGONALS) {
                long n = key(x + d[0], y + d[1], z + d[2]);
                BlockType t = blocks.get(n);
                if (t != null && (t.isLeaves() || t.isLog()) && reached.add(n)) {
                    frontier.add(new long[]{n, node[1] + 1});
                }
            }
        }
        return reached;
    }
}
