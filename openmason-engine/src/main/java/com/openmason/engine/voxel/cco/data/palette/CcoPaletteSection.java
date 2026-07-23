package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;

import java.util.Arrays;

/**
 * One paletted 16-block-tall section of a chunk column.
 *
 * <p>Storage tiers, promoted on demand and never demoted:
 * <ul>
 *   <li><b>Uniform</b> — every cell is the same block; only a 1-entry palette
 *       is held (~32 bytes instead of a 16 KB reference array).</li>
 *   <li><b>Byte-indexed</b> — palette of up to 256 entries plus one byte per cell.</li>
 *   <li><b>Short-indexed</b> — overflow tier for &gt;256 distinct entries
 *       (practically unreachable in normal gameplay).</li>
 * </ul>
 *
 * <p>Concurrency: reads are lock-free — a single volatile read of the
 * immutable-structure {@link State} followed by plain array reads. All
 * writes synchronize on this section. Structural transitions (uniform
 * inflation, palette growth, index widening) build fresh arrays and publish
 * a new state through the volatile field, so a racing reader holding the
 * previous state can never observe an index outside its palette. In-place
 * index writes are per-element atomic; racing readers see the old or new
 * block — the same semantics the previous dense array provided.
 */
public final class CcoPaletteSection {

    private static final int MAX_BYTE_PALETTE = 256;

    /**
     * Immutable-structure snapshot: palette plus exactly one active index tier.
     * {@code indices == null && wideIndices == null} means uniform (palette[0]).
     * Index values written into an array are always less than its own state's
     * palette length.
     */
    private static final class State {
        final IBlockType[] palette;
        final byte[] indices;
        final short[] wideIndices;

        State(IBlockType[] palette, byte[] indices, short[] wideIndices) {
            this.palette = palette;
            this.indices = indices;
            this.wideIndices = wideIndices;
        }
    }

    private final int cellsPerLayer;
    private final int volume;
    private volatile State state;
    /** Advisory non-air cell count, maintained under the write lock. */
    private volatile int nonAirCount;

    /** Creates a uniform section filled with the given block (typically air). */
    public CcoPaletteSection(int cellsPerLayer, IBlockType fillBlock) {
        if (cellsPerLayer <= 0) {
            throw new IllegalArgumentException("cellsPerLayer must be positive");
        }
        this.cellsPerLayer = cellsPerLayer;
        this.volume = cellsPerLayer * CcoSectionIndexing.SECTION_HEIGHT;
        this.state = new State(new IBlockType[]{fillBlock}, null, null);
        this.nonAirCount = isAir(fillBlock) ? 0 : volume;
    }

    /**
     * Builds a byte-indexed section directly from decoded palette data —
     * the bulk-decode twin of {@link #writeBlockIdsInto}. {@code cellIndices}
     * (section cell order, values masked {@code & 0xFF}) is taken by reference
     * (caller hands over ownership); the palette is defensively copied.
     */
    public static CcoPaletteSection fromPaletteData(int cellsPerLayer, IBlockType[] palette,
                                                    byte[] cellIndices) {
        if (palette.length == 0 || palette.length > MAX_BYTE_PALETTE) {
            throw new IllegalArgumentException("Palette size out of range: " + palette.length);
        }
        int volume = cellsPerLayer * CcoSectionIndexing.SECTION_HEIGHT;
        if (cellIndices.length != volume) {
            throw new IllegalArgumentException("Index array length " + cellIndices.length
                + " != section volume " + volume);
        }
        boolean[] airEntry = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            airEntry[i] = isAir(palette[i]);
        }
        int nonAir = 0;
        for (byte cellIndex : cellIndices) {
            int idx = cellIndex & 0xFF;
            if (idx >= palette.length) {
                throw new IllegalArgumentException("Cell index " + idx + " out of palette range");
            }
            if (!airEntry[idx]) {
                nonAir++;
            }
        }
        return new CcoPaletteSection(cellsPerLayer,
            new State(palette.clone(), cellIndices, null), nonAir);
    }

    private CcoPaletteSection(int cellsPerLayer, State state, int nonAirCount) {
        this.cellsPerLayer = cellsPerLayer;
        this.volume = cellsPerLayer * CcoSectionIndexing.SECTION_HEIGHT;
        this.state = state;
        this.nonAirCount = nonAirCount;
    }

    /** Lock-free read of the block at a section-local cell index. */
    public IBlockType get(int cellIndex) {
        State s = state;
        if (s.indices != null) {
            return s.palette[s.indices[cellIndex] & 0xFF];
        }
        if (s.wideIndices != null) {
            return s.palette[s.wideIndices[cellIndex]];
        }
        return s.palette[0];
    }

    /**
     * Sets the block at a section-local cell index.
     *
     * @return true if the cell changed
     */
    public synchronized boolean set(int cellIndex, IBlockType block) {
        State s = state;
        IBlockType current = readFrom(s, cellIndex);
        if (current == block) {
            return false;
        }

        int paletteIndex = indexOf(s.palette, block);
        if (s.indices != null) {
            if (paletteIndex >= 0) {
                s.indices[cellIndex] = (byte) paletteIndex;
            } else if (s.palette.length < MAX_BYTE_PALETTE) {
                IBlockType[] palette = Arrays.copyOf(s.palette, s.palette.length + 1);
                palette[s.palette.length] = block;
                byte[] indices = s.indices.clone();
                indices[cellIndex] = (byte) s.palette.length;
                state = new State(palette, indices, null);
            } else {
                widenAndSet(s, cellIndex, block);
            }
        } else if (s.wideIndices != null) {
            if (paletteIndex >= 0) {
                s.wideIndices[cellIndex] = (short) paletteIndex;
            } else {
                IBlockType[] palette = Arrays.copyOf(s.palette, s.palette.length + 1);
                palette[s.palette.length] = block;
                short[] wide = s.wideIndices.clone();
                wide[cellIndex] = (short) s.palette.length;
                state = new State(palette, null, wide);
            }
        } else {
            // Uniform section: inflate to byte-indexed. The new block can't
            // equal palette[0] (the current == block check above caught that).
            byte[] indices = new byte[volume]; // zero-filled = uniform block
            indices[cellIndex] = 1;
            state = new State(new IBlockType[]{s.palette[0], block}, indices, null);
        }

        boolean wasAir = isAir(current);
        boolean nowAir = isAir(block);
        if (wasAir != nowAir) {
            nonAirCount += nowAir ? -1 : 1;
        }
        return true;
    }

    private void widenAndSet(State s, int cellIndex, IBlockType block) {
        IBlockType[] palette = Arrays.copyOf(s.palette, s.palette.length + 1);
        palette[s.palette.length] = block;
        short[] wide = new short[volume];
        byte[] old = s.indices;
        for (int i = 0; i < volume; i++) {
            wide[i] = (short) (old[i] & 0xFF);
        }
        wide[cellIndex] = (short) s.palette.length;
        state = new State(palette, null, wide);
    }

    /**
     * Bulk-writes this section's block ids into {@code dst} starting at
     * {@code dstOffset}, one short per cell in section cell order. This is the
     * zero-virtual-call bulk accessor native kernels and codecs snapshot
     * through: uniform sections become one fill, indexed sections one palette
     * id table + a tight index loop.
     */
    public void writeBlockIdsInto(short[] dst, int dstOffset) {
        State s = state;
        if (s.indices == null && s.wideIndices == null) {
            java.util.Arrays.fill(dst, dstOffset, dstOffset + volume, (short) s.palette[0].getId());
            return;
        }
        short[] paletteIds = new short[s.palette.length];
        for (int i = 0; i < s.palette.length; i++) {
            paletteIds[i] = (short) s.palette[i].getId();
        }
        if (s.indices != null) {
            byte[] indices = s.indices;
            for (int i = 0; i < volume; i++) {
                dst[dstOffset + i] = paletteIds[indices[i] & 0xFF];
            }
        } else {
            short[] wide = s.wideIndices;
            for (int i = 0; i < volume; i++) {
                dst[dstOffset + i] = paletteIds[wide[i]];
            }
        }
    }

    /** True if every cell holds the same block. */
    public boolean isUniform() {
        State s = state;
        return s.indices == null && s.wideIndices == null;
    }

    /** The fill block of a uniform section; meaningless if not uniform. */
    public IBlockType uniformBlock() {
        return state.palette[0];
    }

    /** Advisory count of non-air cells (exact under quiescence). */
    public int nonAirCount() {
        return nonAirCount;
    }

    /**
     * Highest section-local Y (0..15) containing a non-air block,
     * or -1 if the section is entirely air.
     */
    public int highestNonAirLocalY() {
        State s = state;
        if (s.indices == null && s.wideIndices == null) {
            return isAir(s.palette[0]) ? -1 : CcoSectionIndexing.SECTION_HEIGHT - 1;
        }
        for (int ly = CcoSectionIndexing.SECTION_HEIGHT - 1; ly >= 0; ly--) {
            int base = ly * cellsPerLayer;
            for (int i = 0; i < cellsPerLayer; i++) {
                if (!isAir(readFrom(s, base + i))) {
                    return ly;
                }
            }
        }
        return -1;
    }

    /** Creates an independent copy of this section. */
    public CcoPaletteSection copy() {
        State snap;
        int count;
        synchronized (this) {
            snap = cloneState(state);
            count = nonAirCount;
        }
        return new CcoPaletteSection(cellsPerLayer, snap, count);
    }

    /** Replaces this section's contents with a copy of another section's. */
    public void copyFrom(CcoPaletteSection other) {
        if (other.cellsPerLayer != this.cellsPerLayer) {
            throw new IllegalArgumentException("Section dimensions differ");
        }
        State snap;
        int count;
        synchronized (other) {
            snap = cloneState(other.state);
            count = other.nonAirCount;
        }
        synchronized (this) {
            this.state = snap;
            this.nonAirCount = count;
        }
    }

    private static State cloneState(State s) {
        return new State(
                s.palette.clone(),
                s.indices != null ? s.indices.clone() : null,
                s.wideIndices != null ? s.wideIndices.clone() : null);
    }

    private static IBlockType readFrom(State s, int cellIndex) {
        if (s.indices != null) {
            return s.palette[s.indices[cellIndex] & 0xFF];
        }
        if (s.wideIndices != null) {
            return s.palette[s.wideIndices[cellIndex]];
        }
        return s.palette[0];
    }

    private static int indexOf(IBlockType[] palette, IBlockType block) {
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == block) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAir(IBlockType block) {
        return block == null || block.isAir();
    }

    @Override
    public String toString() {
        State s = state;
        String tier = s.indices != null ? "byte" : (s.wideIndices != null ? "short" : "uniform");
        return String.format("CcoPaletteSection{tier=%s, palette=%d, nonAir=%d}",
                tier, s.palette.length, nonAirCount);
    }
}
