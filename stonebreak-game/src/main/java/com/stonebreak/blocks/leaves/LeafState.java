package com.stonebreak.blocks.leaves;

/**
 * Per-block state for leaves. The only state is the "placed by a player" marker:
 * the server writes it when it accepts a player's leaf placement, and leaf decay
 * never removes a marked leaf. Worldgen and sapling-grown leaves carry no state, so
 * they decay as before.
 *
 * <p>The marker lives in the chunk's ordinary block-state map, so it is saved with
 * the chunk and cleared automatically when the cell changes type (breaking the leaf
 * forgets it). It has a {@code family:} prefix but no {@code state=} key, so
 * {@link com.stonebreak.blocks.BlockRenderState} treats the whole string as its mesh
 * key. It is written in the same tick as the block itself, so that costs no extra
 * remesh.
 */
public final class LeafState {

    /** Marks a leaf the player placed: it never decays. */
    public static final String PLACED = "leaf:placed";

    private LeafState() {
    }

    /** Whether a cell's state string marks a player-placed leaf. */
    public static boolean isPlaced(String state) {
        return PLACED.equals(state);
    }
}
