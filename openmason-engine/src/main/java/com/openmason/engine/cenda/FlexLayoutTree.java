package com.openmason.engine.cenda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;

/**
 * A retained Yoga node tree owned by one UI instance (#287). Styles are pushed in batches of
 * {@link FlexRecord} records; the native side skips unchanged records and Yoga only dirties
 * what really changed, so a relayout costs what the edit touched.
 *
 * <p>Not thread-safe: one UI thread owns a tree. {@link #close()} frees every native node; a
 * tree dropped without closing is freed by a cleaner (a safety net, not a substitute).
 *
 * <p>Scratch buffers for batched calls grow geometrically and each lives in its own arena, freed
 * when it is outgrown, so a list that grows one row at a time keeps O(n) native memory.
 */
public final class FlexLayoutTree implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Everything native the tree owns; the cleaner's action, so it must not reach the tree. */
    private static final class Native implements Runnable {
        MemorySegment tree;
        Arena idsArena;
        Arena floatsArena;

        @Override
        public void run() {
            try {
                if (tree != null) {
                    CendaFlex.TREE_FREE.invokeExact(tree);
                }
            } catch (Throwable t) {
                throw rethrow(t);
            } finally {
                tree = null;
                if (idsArena != null) {
                    idsArena.close();
                    idsArena = null;
                }
                if (floatsArena != null) {
                    floatsArena.close();
                    floatsArena = null;
                }
            }
        }
    }

    private final Native nat = new Native();
    private final Cleaner.Cleanable cleanable;
    private MemorySegment tree;
    private MemorySegment ids = MemorySegment.NULL;
    private MemorySegment floats = MemorySegment.NULL;

    FlexLayoutTree(float pointScale) {
        try {
            tree = (MemorySegment) CendaFlex.TREE_NEW.invokeExact(pointScale, CendaFlex.MEASURE_STUB,
                CendaFlex.BASELINE_STUB);
        } catch (Throwable t) {
            throw new IllegalStateException("cf_tree_new failed", t);
        }
        if (tree.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("cf_tree_new returned null");
        }
        nat.tree = tree;
        cleanable = CLEANER.register(this, nat);
    }

    /** @return a new detached node handle with default style */
    public int newNode() {
        int handle;
        try {
            handle = (int) CendaFlex.NODE_NEW.invokeExact(live());
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (handle < 0) {
            throw new IllegalStateException("cf_node_new failed");
        }
        return handle;
    }

    /** Detaches the node and its children (which stay alive) and frees it. */
    public void freeNode(int node) {
        try {
            check((int) CendaFlex.NODE_FREE.invokeExact(live(), node), "free", node);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Inserts a parentless {@code child} at {@code index} ({@code -1} appends). */
    public void insert(int parent, int child, int index) {
        try {
            check((int) CendaFlex.NODE_INSERT.invokeExact(live(), parent, child, index), "insert", child);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void detach(int node) {
        try {
            check((int) CendaFlex.NODE_DETACH.invokeExact(live(), node), "detach", node);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Applies {@code records[i * STRIDE ..]} to {@code nodes[i]} for {@code i < count}. */
    public void setStyles(int[] nodes, float[] records, int count) {
        if (count == 0) {
            return;
        }
        MemorySegment idSeg = ids(count);
        MemorySegment recSeg = floats((long) count * FlexRecord.STRIDE);
        MemorySegment.copy(nodes, 0, idSeg, ValueLayout.JAVA_INT, 0, count);
        MemorySegment.copy(records, 0, recSeg, ValueLayout.JAVA_FLOAT, 0, count * FlexRecord.STRIDE);
        try {
            check((int) CendaFlex.NODES_SET_STYLE.invokeExact(live(), idSeg, recSeg, count), "set style", -1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A measured leaf's content changed; it re-measures on the next layout. */
    public void markDirty(int node) {
        try {
            check((int) CendaFlex.NODE_MARK_DIRTY.invokeExact(live(), node), "mark dirty", node);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Lays out {@code root}'s subtree in {@code width x height} points (NaN = unbounded).
     *
     * @return how many nodes' parent-relative rects changed; 0 means nothing moved
     */
    public int layout(int root, float width, float height, FlexMeasure measure) {
        MemorySegment t = live();
        int changed = CendaFlex.withMeasure(measure, () -> {
            try {
                return (int) CendaFlex.TREE_LAYOUT.invokeExact(t, root, width, height);
            } catch (Throwable e) {
                throw rethrow(e);
            }
        });
        if (changed < 0) {
            check(-changed, "layout", root);
        }
        return changed;
    }

    /** Writes x, y, w, h (relative to {@code root}'s origin) for each of {@code nodes[0..count)}. */
    public void read(int root, int[] nodes, int count, float[] out) {
        if (count == 0) {
            return;
        }
        MemorySegment idSeg = ids(count);
        MemorySegment rects = floats((long) count * 4);
        MemorySegment.copy(nodes, 0, idSeg, ValueLayout.JAVA_INT, 0, count);
        try {
            check((int) CendaFlex.NODES_READ.invokeExact(live(), root, idSeg, count, rects), "read", root);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        MemorySegment.copy(rects, ValueLayout.JAVA_FLOAT, 0, out, 0, count * 4);
    }

    public int nodeCount() {
        try {
            return (int) CendaFlex.TREE_NODE_COUNT.invokeExact(live());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean isClosed() {
        return tree == null;
    }

    @Override
    public void close() {
        if (tree == null) {
            return;
        }
        tree = null;
        ids = MemorySegment.NULL;
        floats = MemorySegment.NULL;
        cleanable.clean(); // runs once: frees the tree and the scratch arenas
    }

    private MemorySegment live() {
        if (tree == null) {
            throw new IllegalStateException("flex tree is closed");
        }
        return tree;
    }

    private MemorySegment ids(int count) {
        long bytes = (long) count * Integer.BYTES;
        if (ids.byteSize() < bytes) {
            Arena next = Arena.ofShared();
            ids = next.allocate(grow(ids.byteSize(), bytes, 256), 8);
            if (nat.idsArena != null) {
                nat.idsArena.close();
            }
            nat.idsArena = next;
        }
        return ids;
    }

    private MemorySegment floats(long count) {
        long bytes = count * Float.BYTES;
        if (floats.byteSize() < bytes) {
            Arena next = Arena.ofShared();
            floats = next.allocate(grow(floats.byteSize(), bytes, 4096), 8);
            if (nat.floatsArena != null) {
                nat.floatsArena.close();
            }
            nat.floatsArena = next;
        }
        return floats;
    }

    /** Next scratch size: at least {@code needed}, at least double the current, at least {@code min}. */
    static long grow(long current, long needed, long min) {
        return Math.max(needed, Math.max(min, current * 2));
    }

    /** Bytes of scratch currently held (tests). */
    long scratchBytes() {
        return ids.byteSize() + floats.byteSize();
    }

    private static void check(int status, String op, int node) {
        if (status != FlexRecord.OK) {
            String why = switch (status) {
                case FlexRecord.ERR_ARG -> "bad argument";
                case FlexRecord.ERR_NODE -> "unknown or freed node";
                case FlexRecord.ERR_TREE -> "edit would break the tree";
                default -> "status " + status;
            };
            throw new IllegalStateException("flex " + op + " failed" + (node >= 0 ? " for node " + node : "")
                + ": " + why);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
