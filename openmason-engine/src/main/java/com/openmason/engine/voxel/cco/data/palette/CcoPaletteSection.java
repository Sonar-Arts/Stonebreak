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
 *
 * <p>Copies are copy-on-write: {@link #copy()} and {@link #copyFrom} share
 * the current {@link State} by reference and mark it {@code shared}; the
 * first in-place index write on either side clones the index array into a
 * fresh private state before mutating. Snapshot copies for saves therefore
 * cost O(1) per section instead of cloning every index array. Palette
 * arrays are never mutated in place after publication (growth always copies),
 * so they are shared freely across states.
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
        /** Byte-indexed tier (palette ≤ 256); null otherwise. */
        final byte[] indices;
        /** Wide tier (palette > 256); null otherwise. */
        final short[] wideIndices;
        /**
         * Nibble tier (palette ≤ 16): two cells per byte, cell {@code i} in
         * the low nibble of byte {@code i >> 1} when even, high when odd. Half
         * the bytes of the byte tier for the sections terrain produces
         * (≤ 13 distinct ids). Null otherwise.
         */
        final byte[] nibbles;
        /**
         * Copy-on-write marker: set (under the owning section's lock) when this
         * state becomes visible from more than one section via {@link #copy()}/
         * {@link #copyFrom}. A writer that finds it set clones the index array
         * into a fresh unshared state instead of mutating in place. Never
         * cleared — a stale {@code true} only costs one extra clone.
         */
        volatile boolean shared;

        State(IBlockType[] palette, byte[] indices, short[] wideIndices) {
            this(palette, indices, wideIndices, null);
        }

        State(IBlockType[] palette, byte[] indices, short[] wideIndices, byte[] nibbles) {
            this.palette = palette;
            this.indices = indices;
            this.wideIndices = wideIndices;
            this.nibbles = nibbles;
        }

        boolean uniform() {
            return indices == null && wideIndices == null && nibbles == null;
        }
    }

    /** Palette size up to which a section stays on the nibble tier. */
    private static final int MAX_NIBBLE_PALETTE = 16;

    /**
     * True when {@link #fromPaletteData} will pack a palette of this size into
     * the nibble tier and therefore NOT keep the caller's index array — the
     * caller may pass a reusable scratch buffer instead of a fresh one.
     */
    public static boolean packsToNibbles(int paletteLength) {
        return NIBBLE_TIER && paletteLength <= MAX_NIBBLE_PALETTE;
    }
    /** Kill switch: {@code -Dstonebreak.palette.nibble=off} keeps the byte tier for ≤16 palettes. */
    private static final boolean NIBBLE_TIER =
        !"off".equalsIgnoreCase(System.getProperty("stonebreak.palette.nibble", "on"));

    private static int nibbleAt(byte[] nibbles, int cellIndex) {
        int b = nibbles[cellIndex >> 1];
        return (cellIndex & 1) == 0 ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    private static void nibblePut(byte[] nibbles, int cellIndex, int value) {
        int i = cellIndex >> 1;
        int b = nibbles[i];
        nibbles[i] = (byte) ((cellIndex & 1) == 0 ? ((b & 0xF0) | value) : ((b & 0x0F) | (value << 4)));
    }

    /** Packs byte indices (all < 16) into nibbles. */
    private static byte[] packNibbles(byte[] indices, int volume) {
        byte[] out = new byte[(volume + 1) >> 1];
        for (int i = 0; i < volume; i++) {
            nibblePut(out, i, indices[i] & 0xF);
        }
        return out;
    }

    private static byte[] unpackNibbles(byte[] nibbles, int volume) {
        byte[] out = new byte[volume];
        for (int i = 0; i < volume; i++) {
            out[i] = (byte) nibbleAt(nibbles, i);
        }
        return out;
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
        return fromPaletteData(cellsPerLayer, palette, palette.length, cellIndices);
    }

    /**
     * As {@link #fromPaletteData(int, IBlockType[], byte[])}, but reads only
     * the first {@code paletteLength} entries of {@code palette} — so callers
     * can pass a reusable oversized scratch array without allocating an
     * exact-size palette per section.
     */
    public static CcoPaletteSection fromPaletteData(int cellsPerLayer, IBlockType[] palette,
                                                    int paletteLength, byte[] cellIndices) {
        if (paletteLength <= 0 || paletteLength > MAX_BYTE_PALETTE
                || paletteLength > palette.length) {
            throw new IllegalArgumentException("Palette size out of range: " + paletteLength);
        }
        int volume = cellsPerLayer * CcoSectionIndexing.SECTION_HEIGHT;
        if (cellIndices.length != volume) {
            throw new IllegalArgumentException("Index array length " + cellIndices.length
                + " != section volume " + volume);
        }
        boolean[] airEntry = new boolean[paletteLength];
        for (int i = 0; i < paletteLength; i++) {
            airEntry[i] = isAir(palette[i]);
        }
        int nonAir = 0;
        for (byte cellIndex : cellIndices) {
            int idx = cellIndex & 0xFF;
            if (idx >= paletteLength) {
                throw new IllegalArgumentException("Cell index " + idx + " out of palette range");
            }
            if (!airEntry[idx]) {
                nonAir++;
            }
        }
        IBlockType[] copy = Arrays.copyOf(palette, paletteLength);
        State state = NIBBLE_TIER && paletteLength <= MAX_NIBBLE_PALETTE
            ? new State(copy, null, null, packNibbles(cellIndices, volume))
            : new State(copy, cellIndices, null);
        return new CcoPaletteSection(cellsPerLayer, state, nonAir);
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
        if (s.nibbles != null) {
            return s.palette[nibbleAt(s.nibbles, cellIndex)];
        }
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
        if (s.nibbles != null) {
            if (paletteIndex >= 0) {
                if (s.shared) {
                    byte[] nib = s.nibbles.clone();
                    nibblePut(nib, cellIndex, paletteIndex);
                    state = new State(s.palette, null, null, nib);
                } else {
                    nibblePut(s.nibbles, cellIndex, paletteIndex);
                }
            } else if (s.palette.length < MAX_NIBBLE_PALETTE) {
                IBlockType[] palette = Arrays.copyOf(s.palette, s.palette.length + 1);
                palette[s.palette.length] = block;
                byte[] nib = s.nibbles.clone();
                nibblePut(nib, cellIndex, s.palette.length);
                state = new State(palette, null, null, nib);
            } else {
                // Promote nibble → byte tier (17th palette entry).
                IBlockType[] palette = Arrays.copyOf(s.palette, s.palette.length + 1);
                palette[s.palette.length] = block;
                byte[] indices = unpackNibbles(s.nibbles, volume);
                indices[cellIndex] = (byte) s.palette.length;
                state = new State(palette, indices, null);
            }
        } else if (s.indices != null) {
            if (paletteIndex >= 0) {
                if (s.shared) {
                    byte[] indices = s.indices.clone();
                    indices[cellIndex] = (byte) paletteIndex;
                    state = new State(s.palette, indices, null);
                } else {
                    s.indices[cellIndex] = (byte) paletteIndex;
                }
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
                if (s.shared) {
                    short[] wide = s.wideIndices.clone();
                    wide[cellIndex] = (short) paletteIndex;
                    state = new State(s.palette, null, wide);
                } else {
                    s.wideIndices[cellIndex] = (short) paletteIndex;
                }
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
            if (NIBBLE_TIER) {
                byte[] nib = new byte[(volume + 1) >> 1]; // zero-filled = uniform block
                nibblePut(nib, cellIndex, 1);
                state = new State(new IBlockType[]{s.palette[0], block}, null, null, nib);
            } else {
                byte[] indices = new byte[volume]; // zero-filled = uniform block
                indices[cellIndex] = 1;
                state = new State(new IBlockType[]{s.palette[0], block}, indices, null);
            }
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
        if (s.uniform()) {
            java.util.Arrays.fill(dst, dstOffset, dstOffset + volume, (short) s.palette[0].getId());
            return;
        }
        short[] paletteIds = new short[s.palette.length];
        for (int i = 0; i < s.palette.length; i++) {
            paletteIds[i] = (short) s.palette[i].getId();
        }
        if (s.nibbles != null) {
            byte[] nib = s.nibbles;
            for (int i = 0; i < volume; i += 2) {
                int b = nib[i >> 1];
                dst[dstOffset + i] = paletteIds[b & 0xF];
                if (i + 1 < volume) {
                    dst[dstOffset + i + 1] = paletteIds[(b >> 4) & 0xF];
                }
            }
        } else if (s.indices != null) {
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
        return state.uniform();
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
        if (s.uniform()) {
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

    /**
     * Creates an independent copy of this section. O(1): the current state is
     * shared by reference and marked for copy-on-write — whichever section
     * mutates it first (in place) clones the index array privately then.
     */
    public CcoPaletteSection copy() {
        synchronized (this) {
            State s = state;
            s.shared = true;
            return new CcoPaletteSection(cellsPerLayer, s, nonAirCount);
        }
    }

    /**
     * Replaces this section's contents with another section's. O(1): shares
     * the other section's state copy-on-write, exactly like {@link #copy()}.
     */
    public void copyFrom(CcoPaletteSection other) {
        if (other.cellsPerLayer != this.cellsPerLayer) {
            throw new IllegalArgumentException("Section dimensions differ");
        }
        State snap;
        int count;
        synchronized (other) {
            snap = other.state;
            snap.shared = true;
            count = other.nonAirCount;
        }
        synchronized (this) {
            this.state = snap;
            this.nonAirCount = count;
        }
    }

    /**
     * Snapshots this section's palette-compressed contents for serialization,
     * without allocating: the caller provides reusable output arrays.
     *
     * @param paletteIds receives palette block ids (length >= 256)
     * @param indices    receives the per-cell palette indices (length >= volume)
     * @return 0 if uniform ({@code paletteIds[0]} = fill block id, indices
     *         untouched); a positive palette size for a byte-indexed section
     *         (ids in {@code paletteIds[0..n)}, cell indices copied); or -1
     *         for the short-indexed overflow tier (caller falls back to
     *         {@link #writeBlockIdsInto} dense encoding)
     */
    public int snapshotPaletteData(short[] paletteIds, byte[] indices) {
        State s = state;
        if (s.uniform()) {
            paletteIds[0] = (short) s.palette[0].getId();
            return 0;
        }
        if (s.wideIndices != null) {
            return -1;
        }
        if (paletteIds.length < s.palette.length || indices.length < volume) {
            throw new IllegalArgumentException("Snapshot output arrays too small");
        }
        for (int i = 0; i < s.palette.length; i++) {
            paletteIds[i] = (short) s.palette[i].getId();
        }
        if (s.nibbles != null) {
            for (int i = 0; i < volume; i++) {
                indices[i] = (byte) nibbleAt(s.nibbles, i);
            }
        } else {
            System.arraycopy(s.indices, 0, indices, 0, volume);
        }
        return s.palette.length;
    }

    /** True when this section is on the 4-bit nibble tier (palette ≤ 16). */
    public boolean isNibbleTier() {
        return state.nibbles != null;
    }

    private static IBlockType readFrom(State s, int cellIndex) {
        if (s.nibbles != null) {
            return s.palette[nibbleAt(s.nibbles, cellIndex)];
        }
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
        String tier = s.nibbles != null ? "nibble"
            : s.indices != null ? "byte" : (s.wideIndices != null ? "short" : "uniform");
        return String.format("CcoPaletteSection{tier=%s, palette=%d, nonAir=%d}",
                tier, s.palette.length, nonAirCount);
    }
}
