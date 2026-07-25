package com.openmason.engine.voxel.cco.data.palette;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Copy-on-write contract of {@link CcoPaletteSection#copy()} and
 * {@link CcoPaletteSection#copyFrom}: copies share state until either side
 * writes, and mutations on one side must never bleed into the other —
 * across all three storage tiers.
 */
class CcoPaletteSectionCowTest {

    private static final int CELLS_PER_LAYER = 16 * 16;
    private static final int VOLUME = CELLS_PER_LAYER * CcoSectionIndexing.SECTION_HEIGHT;

    @Test
    void uniformCopyIsIndependent() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.block(5));
        CcoPaletteSection copy = source.copy();

        source.set(0, TestBlocks.block(9));

        assertSame(TestBlocks.block(9), source.get(0));
        assertSame(TestBlocks.block(5), copy.get(0), "copy must keep the pre-mutation block");
        assertEquals(VOLUME, copy.nonAirCount());
    }

    @Test
    void byteTierInPlaceWriteOnSourceDoesNotBleedIntoCopy() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        source.set(0, TestBlocks.block(1));
        source.set(1, TestBlocks.block(2));
        CcoPaletteSection copy = source.copy();

        // block(2) is already in the palette, so without COW this write would
        // mutate the shared index array in place.
        source.set(0, TestBlocks.block(2));

        assertSame(TestBlocks.block(2), source.get(0));
        assertSame(TestBlocks.block(1), copy.get(0), "in-place source write leaked into the copy");
        assertEquals(2, copy.nonAirCount());
        assertEquals(2, source.nonAirCount());
    }

    @Test
    void byteTierWriteOnCopyDoesNotBleedIntoSource() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        source.set(0, TestBlocks.block(1));
        source.set(1, TestBlocks.block(2));
        CcoPaletteSection copy = source.copy();

        copy.set(1, TestBlocks.block(1));

        assertSame(TestBlocks.block(2), source.get(1), "copy write leaked into the source");
        assertSame(TestBlocks.block(1), copy.get(1));
    }

    @Test
    void paletteGrowthAfterCopyLeavesCopyIntact() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        source.set(0, TestBlocks.block(1));
        CcoPaletteSection copy = source.copy();

        // New palette entry: structural transition, always builds fresh arrays.
        source.set(1, TestBlocks.block(7));

        assertSame(TestBlocks.air(), copy.get(1));
        assertSame(TestBlocks.block(7), source.get(1));
    }

    @Test
    void wideTierCopyOnWrite() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        // Force the short-indexed tier with >256 distinct palette entries.
        for (int i = 1; i <= 300; i++) {
            source.set(i, TestBlocks.block(i));
        }
        CcoPaletteSection copy = source.copy();

        // block(1) already in the palette: in-place wide write without COW.
        source.set(2, TestBlocks.block(1));

        assertSame(TestBlocks.block(2), copy.get(2), "wide in-place write leaked into the copy");
        assertSame(TestBlocks.block(1), source.get(2));
    }

    @Test
    void copyFromSharesStateSafely() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        source.set(3, TestBlocks.block(4));
        source.set(4, TestBlocks.block(5));
        CcoPaletteSection target = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());

        target.copyFrom(source);
        assertSame(TestBlocks.block(4), target.get(3));
        assertEquals(source.nonAirCount(), target.nonAirCount());

        // Post-copyFrom mutations stay private on both sides.
        source.set(3, TestBlocks.block(5));
        target.set(4, TestBlocks.block(4));
        assertSame(TestBlocks.block(4), target.get(3));
        assertSame(TestBlocks.block(5), source.get(4));
    }

    @Test
    void chainedCopiesStayIndependent() {
        CcoPaletteSection a = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        a.set(0, TestBlocks.block(1));
        a.set(1, TestBlocks.block(2));
        CcoPaletteSection b = a.copy();
        CcoPaletteSection c = b.copy();

        a.set(0, TestBlocks.block(2));
        b.set(1, TestBlocks.block(1));

        assertSame(TestBlocks.block(1), c.get(0));
        assertSame(TestBlocks.block(2), c.get(1));
        assertSame(TestBlocks.block(2), a.get(0));
        assertSame(TestBlocks.block(1), b.get(1));
    }

    @Test
    void snapshotPaletteDataRoundTripsThroughFromPaletteData() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        source.set(0, TestBlocks.block(1));
        source.set(100, TestBlocks.block(2));
        source.set(VOLUME - 1, TestBlocks.block(1));

        short[] paletteIds = new short[256];
        byte[] indices = new byte[VOLUME];
        int paletteSize = source.snapshotPaletteData(paletteIds, indices);
        assertEquals(3, paletteSize, "air + two blocks");

        com.openmason.engine.voxel.IBlockType[] palette =
            new com.openmason.engine.voxel.IBlockType[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = TestBlocks.block(paletteIds[i]);
        }
        CcoPaletteSection rebuilt =
            CcoPaletteSection.fromPaletteData(CELLS_PER_LAYER, palette, paletteSize, indices.clone());

        for (int i = 0; i < VOLUME; i++) {
            assertSame(source.get(i), rebuilt.get(i), "cell " + i);
        }
        assertEquals(source.nonAirCount(), rebuilt.nonAirCount());
    }

    @Test
    void uniformSnapshotReportsFillId() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.block(8));
        short[] paletteIds = new short[256];
        assertEquals(0, source.snapshotPaletteData(paletteIds, new byte[VOLUME]));
        assertEquals(8, paletteIds[0]);
    }
}
