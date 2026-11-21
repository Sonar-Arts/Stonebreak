package com.stonebreak.world.chunk;

/**
 * The game's water-flow vocabulary. Since the cubic voxel runtime (#447) the state itself lives in the
 * native world's WATER layer ({@link com.stonebreak.world.voxel.VoxelLayers#WATER}, cv_fluid encoding);
 * {@code World.getWaterValueAt/setWaterValueAt} speak the values below and convert at the layer
 * ({@link com.stonebreak.world.voxel.VoxelLayers#waterToGame}/{@code waterToRaw}). This class is only the
 * constants and helpers now (the per-chunk map is gone). The invariant, relied on by the sim, physics,
 * saves and network alike:
 *
 * <pre>
 *   block == WATER, no entry  =&gt;  source (level 0)
 *   entry 1..7                =&gt;  flowing, that level
 *   entry FALLING (8)         =&gt;  falling column (renders full height)
 *   entry RIVER+d (9..16)     =&gt;  a river surface running in octant d
 *   block != WATER            =&gt;  no entry (enforced by Chunk.setBlock)
 * </pre>
 *
 * <p>A RIVER cell is a SOURCE that happens to know which way it runs. Worldgen
 * stamps rivers as source blocks like everything else — the kernel's whole
 * containment design rests on that — so nothing about how this water behaves
 * changes: {@link #level} reports it as a source and the sim, the physics and
 * the save codec all see one. What the marker buys is that the renderer can
 * tell a reach from a pond, which a source block alone cannot say. Use
 * {@link #level} for anything that asks "how much water", and
 * {@link #isRiver}/{@link #octant} for anything that asks "which way".
 *
 * <p>Ocean cubes therefore cost zero bytes (the layer is 0 everywhere: source),
 * and worldgen water is a source by definition with no seeding pass.
 */
public final class ChunkWaterLayer {

    /** Layer value for a falling water cell. Values 1..7 are flowing levels. */
    public static final int FALLING = 8;

    /** Layer value meaning "no entry": source if the block is WATER. */
    public static final int SOURCE = 0;

    public static final int MAX_FLOW_LEVEL = 7;

    /**
     * Base of the river-surface values: {@code RIVER + octant}, the octant
     * being 0..7 of {@code (dx, dz)} with 0 = +X, counter-clockwise in eighths
     * of a turn — the kernel's {@code out_river_flow} encoding, unchanged.
     */
    public static final int RIVER = 9;

    /** Largest value this layer stores. */
    public static final int MAX_VALUE = RIVER + 7;

    /** Whether a layer value marks a river surface. */
    public static boolean isRiver(int value) {
        return value >= RIVER;
    }

    /** The flow octant of a river value; meaningless for any other. */
    public static int octant(int value) {
        return value - RIVER;
    }

    /** The layer value for a river surface running in {@code octant}. */
    public static int river(int octant) {
        return RIVER + octant;
    }

    /**
     * How much water a layer value means, in the 0..8 vocabulary everything
     * that is not the renderer speaks: a river surface is a source.
     */
    public static int level(int value) {
        return isRiver(value) ? SOURCE : value;
    }

    private ChunkWaterLayer() {
    }
}
