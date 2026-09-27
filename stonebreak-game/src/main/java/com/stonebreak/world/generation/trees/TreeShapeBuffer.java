package com.stonebreak.world.generation.trees;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.leaves.LeafDecaySystem;

/**
 * Collects a tree shape in memory so it can be validated before it touches the world.
 *
 * <p>{@link #flushAnchored} writes the shape through to a real sink, dropping every leaf
 * that is not within {@link LeafDecaySystem#DECAY_RADIUS} orthogonal leaf/log steps of a
 * log — the exact reachability the decay system enforces on a chunk-load rescan. Random
 * canopy thinning (asymmetric/windswept elms thin one side at 55–65%) otherwise leaves
 * isolated leaf islands that the player watches vanish the moment the chunk loads; pruning
 * them at generation time makes the tree's anchoring structural instead of hopeful.
 *
 * <p>Later writes to the same cell win, matching the overwrite semantics of
 * {@link TreeBlockPlacer}.
 */
public final class TreeShapeBuffer implements TreeBlockSink {

    private static final int[][] ORTHOGONALS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final Map<Long, BlockType> blocks = new HashMap<>();

    @Override
    public void placeBlock(int worldX, int worldY, int worldZ, BlockType blockType) {
        blocks.put(key(worldX, worldY, worldZ), blockType);
    }

    /** Number of buffered cells (logs + leaves + anything else the shape wrote). */
    public int size() {
        return blocks.size();
    }

    /**
     * Writes every buffered block to {@code out}, except leaves that cannot reach a log
     * within the decay radius. Returns how many leaves were pruned.
     */
    public int flushAnchored(TreeBlockSink out) {
        Set<Long> anchored = anchoredFoliage();
        int pruned = 0;
        for (Map.Entry<Long, BlockType> e : blocks.entrySet()) {
            long k = e.getKey();
            BlockType type = e.getValue();
            if (type.isLeaves() && !anchored.contains(k)) {
                pruned++;
                continue;
            }
            out.placeBlock(x(k), y(k), z(k), type);
        }
        return pruned;
    }

    /** Multi-source BFS from every log through logs and leaves, depth-capped at the decay radius. */
    private Set<Long> anchoredFoliage() {
        Set<Long> reached = new HashSet<>();
        ArrayDeque<Long> frontier = new ArrayDeque<>();
        for (Map.Entry<Long, BlockType> e : blocks.entrySet()) {
            if (e.getValue().isLog()) {
                reached.add(e.getKey());
                frontier.add(e.getKey());
            }
        }
        for (int depth = 0; depth < LeafDecaySystem.DECAY_RADIUS && !frontier.isEmpty(); depth++) {
            int layer = frontier.size();
            for (int i = 0; i < layer; i++) {
                long node = frontier.poll();
                int x = x(node);
                int y = y(node);
                int z = z(node);
                for (int[] d : ORTHOGONALS) {
                    long n = key(x + d[0], y + d[1], z + d[2]);
                    BlockType t = blocks.get(n);
                    if (t != null && (t.isLeaves() || t.isLog()) && reached.add(n)) {
                        frontier.add(n);
                    }
                }
            }
        }
        return reached;
    }

    // 21-bit signed fields — plenty for world coordinates and never collides in-tree.
    private static final int BIAS = 1 << 20;
    private static final long MASK = (1L << 21) - 1;

    private static long key(int x, int y, int z) {
        return ((long) (x + BIAS) << 42) | ((long) (y + BIAS) << 21) | (z + BIAS);
    }

    private static int x(long k) { return (int) ((k >>> 42) & MASK) - BIAS; }
    private static int y(long k) { return (int) ((k >>> 21) & MASK) - BIAS; }
    private static int z(long k) { return (int) (k & MASK) - BIAS; }
}
