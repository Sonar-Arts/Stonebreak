package com.stonebreak.world.fastlod;

/**
 * Player-facing LOD fidelity preset. Picks how far each detail level reaches,
 * independently of the LOD distance slider (which only sets how far the ring
 * goes): level {@code Lk} (cells of {@code 2^k} blocks) is allowed once a node
 * is at least {@code 2^k × blocksPerCell} blocks away, so a cell never spans
 * more than a fixed angle on screen — about {@link #worstCellPixels()} pixels
 * at 1080p with a 70° vertical FOV.
 *
 * <p>Measured with the FastLOD ring lab (2026-10-03, render distance 8, LOD
 * distance 48): estimated LOD VRAM in use goes 5.4 / 7.8 / 13.8 / 20.6 MiB from LOW to
 * ULTRA, against 16.8 MiB for the old equal-width bands, whose worst cell was
 * 16–27 px depending on the slider. Finer presets also keep more of the ring
 * at cave-carved levels (see {@link #carves}), which is where generation CPU
 * and the carve-profile cache's RAM go.
 */
public enum FastLodQuality {
    LOW("Low", 48, 0),
    MEDIUM("Medium", 64, 1),
    HIGH("High", 96, 2),
    ULTRA("Ultra", 128, 3);

    public static final FastLodQuality DEFAULT = MEDIUM;

    private final String label;
    private final int blocksPerCell;
    private final int treeLevel;

    FastLodQuality(String label, int blocksPerCell, int treeLevel) {
        this.label = label;
        this.blocksPerCell = blocksPerCell;
        this.treeLevel = treeLevel;
    }

    public String label() {
        return label;
    }

    /** Distance (blocks) per block of cell size: level k starts at {@code 2^k × blocksPerCell}. */
    public int blocksPerCell() {
        return blocksPerCell;
    }

    /**
     * Whether nodes at {@code level} sample the cave-carved surface (ravines,
     * sinkholes, cave-mouth notches). The carve profile is a full carve-mask
     * build per chunk (~3.5–7 ms) and was measured at >99% of a node's cost —
     * an L4 node paid for nine chunks of carving to place nine height samples.
     * Below ULTRA the two coarsest levels (8- and 16-block cells) read the raw
     * terrain height instead (~0.05 ms per node); they start 384–768 blocks
     * out, so the carved, CPU-heavy part of the ring stops growing with the
     * LOD distance. ULTRA keeps every level carved: cave mouths at any range.
     */
    public boolean carves(FastLodLevel level) {
        return this == ULTRA || level.cellSize() <= 4;
    }

    /**
     * Whether nodes at {@code level} draw tree silhouettes. Trees reach through
     * L0 (LOW), L1 (MEDIUM, ~256 blocks), L2 (HIGH, ~768 blocks) and L3 (ULTRA,
     * ~2048 — the whole ring at the maximum LOD distance). Coarse levels place
     * every tree the real generator plants in the node's footprint, so forests
     * keep their density; they are sparse (~1.3 trees per chunk on average,
     * ~150 B of quads each — trunk always included), which is why this is affordable.
     */
    public boolean drawsTrees(FastLodLevel level) {
        return level.index() <= treeLevel;
    }

    /** Worst on-screen cell height in pixels at 1080p / 70° vertical FOV. */
    public double worstCellPixels() {
        return 540.0 / Math.tan(Math.toRadians(35)) / blocksPerCell;
    }

    /** Parses a persisted name; unknown or null values give {@link #DEFAULT}. */
    public static FastLodQuality parse(String name) {
        if (name != null) {
            for (FastLodQuality q : values()) {
                if (q.name().equalsIgnoreCase(name)) {
                    return q;
                }
            }
        }
        return DEFAULT;
    }
}
