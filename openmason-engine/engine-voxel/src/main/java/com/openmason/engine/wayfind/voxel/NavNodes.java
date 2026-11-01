package com.openmason.engine.wayfind.voxel;

/**
 * Packs a block coordinate into the {@code long} node key the search core uses.
 *
 * <p>Layout: X in the top 26 bits, Y in the middle 12, Z in the low 26. X and Z are signed and
 * cover ±33 million blocks; Y is unsigned and covers 0–4095, comfortably above any world height.
 * Packing is exact and total — every distinct in-range coordinate gets a distinct key, so the
 * search's identity map can key on it directly.
 */
public final class NavNodes {

    private static final int Y_BITS = 12;
    private static final int Z_BITS = 26;
    private static final int X_BITS = 26;

    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;

    private static final int Y_SHIFT = Z_BITS;
    private static final int X_SHIFT = Z_BITS + Y_BITS;

    /** Inclusive bounds on the horizontal axes. */
    public static final int MIN_HORIZONTAL = -(1 << (X_BITS - 1));
    public static final int MAX_HORIZONTAL = (1 << (X_BITS - 1)) - 1;

    /** Inclusive bounds on the vertical axis. */
    public static final int MAX_Y = (1 << Y_BITS) - 1;

    private NavNodes() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @throws IllegalArgumentException if a coordinate falls outside the packable range — silently
     *                                  wrapping would alias two distinct cells onto one node
     */
    public static long pack(int x, int y, int z) {
        if (x < MIN_HORIZONTAL || x > MAX_HORIZONTAL || z < MIN_HORIZONTAL || z > MAX_HORIZONTAL) {
            throw new IllegalArgumentException("horizontal coordinate out of range: " + x + ", " + z);
        }
        if (y < 0 || y > MAX_Y) {
            throw new IllegalArgumentException("y out of range: " + y);
        }
        return ((long) x << X_SHIFT) | ((long) y << Y_SHIFT) | (z & Z_MASK);
    }

    public static int x(long node) {
        return (int) (node >> X_SHIFT);
    }

    public static int y(long node) {
        return (int) ((node >>> Y_SHIFT) & Y_MASK);
    }

    public static int z(long node) {
        // Sign-extend the low 26 bits: shift them to the top, then shift back arithmetically.
        return (int) ((node << (64 - Z_BITS)) >> (64 - Z_BITS));
    }

    /** Whether a coordinate can be packed without throwing. */
    public static boolean inRange(int x, int y, int z) {
        return x >= MIN_HORIZONTAL && x <= MAX_HORIZONTAL
                && z >= MIN_HORIZONTAL && z <= MAX_HORIZONTAL
                && y >= 0 && y <= MAX_Y;
    }

    /** Debug rendering of a node key, e.g. {@code "(12, 64, -30)"}. */
    public static String toString(long node) {
        return "(" + x(node) + ", " + y(node) + ", " + z(node) + ")";
    }
}
