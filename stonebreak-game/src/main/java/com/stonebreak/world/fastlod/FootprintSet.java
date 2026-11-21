package com.stonebreak.world.fastlod;

/**
 * Allocation-free set of octree LOD FALLBACK footprints {@code (level, fx, fz)}: footprint {@code (k, fx, fz)}
 * covers blocks {@code [fx * 32 << k, (fx + 1) * 32 << k)} in x and z ({@code cv_lod} "SELECTION AND HANDOVER").
 * Open addressing over full int64 coordinates (no packed keys), backward-shift deletion. Single thread.
 */
public final class FootprintSet {

    /** Footprint edge of level 0 in blocks (one 32x32 cube column). */
    public static final int LEVEL0_SHIFT = 5;

    private int[] levels;
    private long[] xs;
    private long[] zs;
    private boolean[] used;
    private int size;
    /** Bit k set while at least one level-k footprint is in the set. */
    private int levelMask;
    private final int[] perLevel = new int[32];
    /** Every mutation of any set draws a fresh stamp: a cached {@link #covers} answer is valid while it matches. */
    private static final java.util.concurrent.atomic.AtomicLong STAMPS = new java.util.concurrent.atomic.AtomicLong();
    private long stamp = STAMPS.incrementAndGet();

    /**
     * Changes whenever the set's content may have changed (and differs between sets): callers that answer
     * {@link #covers} for many columns every frame cache the answer per column and re-ask only on a new stamp
     * (2026-11-20: FastLOD's per-node coverage probes were a third of its render CPU at a 128-chunk ring).
     */
    public long stamp() {
        return stamp;
    }

    public FootprintSet() {
        alloc(256);
    }

    private void alloc(int capacity) {
        levels = new int[capacity];
        xs = new long[capacity];
        zs = new long[capacity];
        used = new boolean[capacity];
        size = 0;
    }

    private static int hash(int level, long x, long z) {
        long h = (x * 0x9E3779B97F4A7C15L + z) * 0xC2B2AE3D27D4EB4FL + level;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (int) h;
    }

    private int slot(int level, long x, long z) {
        int mask = used.length - 1;
        int i = hash(level, x, z) & mask;
        while (used[i]) {
            if (levels[i] == level && xs[i] == x && zs[i] == z) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return i;
    }

    /** Adds a footprint; false when it was already present. */
    public boolean add(int level, long x, long z) {
        if ((size + 1) * 2 > used.length) {
            grow();
        }
        int i = slot(level, x, z);
        if (used[i]) {
            return false;
        }
        used[i] = true;
        levels[i] = level;
        xs[i] = x;
        zs[i] = z;
        size++;
        stamp = STAMPS.incrementAndGet();
        if (perLevel[level]++ == 0) {
            levelMask |= 1 << level;
        }
        return true;
    }

    /** Removes a footprint; false when it was absent. */
    public boolean remove(int level, long x, long z) {
        int i = slot(level, x, z);
        if (!used[i]) {
            return false;
        }
        used[i] = false;
        size--;
        stamp = STAMPS.incrementAndGet();
        if (--perLevel[level] == 0) {
            levelMask &= ~(1 << level);
        }
        // Backward-shift: pull later entries of the probe chain into the hole.
        int mask = used.length - 1;
        int hole = i;
        int j = (i + 1) & mask;
        while (used[j]) {
            int home = hash(levels[j], xs[j], zs[j]) & mask;
            boolean move = hole <= j ? (home <= hole || home > j) : (home <= hole && home > j);
            if (move) {
                used[hole] = true;
                levels[hole] = levels[j];
                xs[hole] = xs[j];
                zs[hole] = zs[j];
                used[j] = false;
                hole = j;
            }
            j = (j + 1) & mask;
        }
        return true;
    }

    public boolean contains(int level, long x, long z) {
        return used[slot(level, x, z)];
    }

    /**
     * True when the block column (bx, bz) lies in any footprint of the set (one probe per level present).
     */
    public boolean covers(long bx, long bz) {
        int m = levelMask;
        while (m != 0) {
            int k = Integer.numberOfTrailingZeros(m);
            m &= m - 1;
            int shift = LEVEL0_SHIFT + k;
            if (contains(k, bx >> shift, bz >> shift)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return size;
    }

    public void clear() {
        java.util.Arrays.fill(used, false);
        java.util.Arrays.fill(perLevel, 0);
        size = 0;
        levelMask = 0;
        stamp = STAMPS.incrementAndGet();
    }

    private void grow() {
        int[] ol = levels;
        long[] ox = xs;
        long[] oz = zs;
        boolean[] ou = used;
        alloc(ou.length * 2);
        java.util.Arrays.fill(perLevel, 0);
        levelMask = 0;
        for (int i = 0; i < ou.length; i++) {
            if (ou[i]) {
                add(ol[i], ox[i], oz[i]);
            }
        }
    }
}
