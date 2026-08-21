package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 4-bit palette tier: sections with ≤ 16 block types pack two cells per
 * byte, read back identically through every accessor, promote to the byte
 * tier on the 17th type, and keep copy-on-write semantics.
 */
class CcoPaletteNibbleTierTest {

    private static final int CELLS = 256;
    private static final int VOLUME = CELLS * CcoSectionIndexing.SECTION_HEIGHT;

    @Test
    void uniformInflatesToNibbleTierAndReadsBack() {
        CcoPaletteSection s = new CcoPaletteSection(CELLS, TestBlocks.air());
        assertTrue(s.isUniform());
        s.set(5, TestBlocks.block(1));
        assertTrue(s.isNibbleTier());
        assertSame(TestBlocks.block(1), s.get(5));
        assertSame(TestBlocks.air(), s.get(4));
        assertSame(TestBlocks.air(), s.get(6));
        assertEquals(1, s.nonAirCount());
        // Odd/even neighbours share a byte — writes must not disturb each other.
        s.set(4, TestBlocks.block(2));
        assertSame(TestBlocks.block(2), s.get(4));
        assertSame(TestBlocks.block(1), s.get(5));
        short[] ids = new short[VOLUME];
        s.writeBlockIdsInto(ids, 0);
        assertEquals(TestBlocks.block(2).getId(), ids[4]);
        assertEquals(TestBlocks.block(1).getId(), ids[5]);
        assertEquals(TestBlocks.air().getId(), ids[6]);
    }

    @Test
    void fromPaletteDataPicksNibbleTierForSmallPalettesAndSnapshotsExpand() {
        IBlockType[] palette = {TestBlocks.air(), TestBlocks.block(1), TestBlocks.block(2)};
        byte[] cells = new byte[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            cells[i] = (byte) (i % 3);
        }
        CcoPaletteSection s = CcoPaletteSection.fromPaletteData(CELLS, palette, cells.clone());
        assertTrue(s.isNibbleTier());
        for (int i = 0; i < VOLUME; i++) {
            assertSame(palette[i % 3], s.get(i), "cell " + i);
        }
        short[] paletteIds = new short[256];
        byte[] out = new byte[VOLUME];
        assertEquals(3, s.snapshotPaletteData(paletteIds, out));
        for (int i = 0; i < VOLUME; i++) {
            assertEquals(i % 3, out[i]);
        }
        assertEquals(s.highestNonAirLocalY(), CcoSectionIndexing.SECTION_HEIGHT - 1);
    }

    @Test
    void seventeenthTypePromotesToByteTier() {
        IBlockType[] types = new IBlockType[17];
        for (int i = 0; i < 17; i++) {
            types[i] = TestBlocks.block(100 + i);
        }
        CcoPaletteSection s = new CcoPaletteSection(CELLS, types[0]);
        for (int i = 1; i < 16; i++) {
            s.set(i, types[i]);
        }
        assertTrue(s.isNibbleTier());
        s.set(16, types[16]);
        assertFalse(s.isNibbleTier());
        for (int i = 0; i < 17; i++) {
            assertSame(types[i], s.get(i));
        }
        assertSame(types[0], s.get(17));
    }

    @Test
    void copyOnWriteIsolatesNibbleSnapshots() {
        CcoPaletteSection a = new CcoPaletteSection(CELLS, TestBlocks.air());
        a.set(9, TestBlocks.block(1));
        CcoPaletteSection b = a.copy();
        a.set(8, TestBlocks.block(2)); // same byte as cell 9
        assertSame(TestBlocks.block(2), a.get(8));
        assertSame(TestBlocks.air(), b.get(8));
        assertSame(TestBlocks.block(1), b.get(9));
    }
}
