package com.openmason.engine.vram;

/**
 * How one pool's GPU arenas size, grow, and shrink. Sizes are bytes — the
 * consumer converts to its element units (a vertex arena divides by its
 * stride, an index arena by 2). Produced by a compiled CEARL plan or the
 * builtin defaults in {@link VramPlans}.
 *
 * @param vertexInitialBytes initial vertex-arena reservation
 * @param indexInitialBytes  initial index-arena reservation
 * @param growthFactor       geometric growth (new capacity ≥ old × factor)
 * @param growthReserve      slack over the immediate need when growing
 *                           (fraction, e.g. 0.25 = 25% headroom)
 * @param alignElements      element alignment of grown capacities (power of two)
 * @param trimFraction       shrink the arena back toward its live bytes when
 *                           usage falls under this share of capacity
 *                           (fraction; 0 disables trimming — arenas then only
 *                           ever grow, the pre-CEARL behavior)
 * @param sparseGrowth       the plan's {@code grow sparse}: back arenas with
 *                           ARB_sparse_buffer virtual storage and grow/shrink
 *                           by page commitment instead of allocate-and-copy —
 *                           no transient 2x spike, no VAO re-point, trim
 *                           becomes a free page decommit (falls back to copy
 *                           growth when the driver lacks the extension)
 */
public record VramArenaPolicy(long vertexInitialBytes, long indexInitialBytes,
                              double growthFactor, double growthReserve, int alignElements,
                              double trimFraction, boolean sparseGrowth) {

    public VramArenaPolicy {
        if (vertexInitialBytes < 1 || indexInitialBytes < 1) {
            throw new IllegalArgumentException("arena sizes must be positive");
        }
        if (growthFactor <= 1.0 || growthReserve < 0
                || alignElements < 1 || Integer.bitCount(alignElements) != 1) {
            throw new IllegalArgumentException("invalid arena growth policy");
        }
        if (trimFraction < 0 || trimFraction > 0.9) {
            throw new IllegalArgumentException("trim fraction must be within [0, 0.9]");
        }
    }
}
