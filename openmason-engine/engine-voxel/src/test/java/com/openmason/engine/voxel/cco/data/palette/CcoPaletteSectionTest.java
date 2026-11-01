package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-transition and round-trip coverage for {@link CcoPaletteSection}:
 * uniform → byte-indexed inflation, palette growth, byte → short widening
 * past 256 entries, and non-air counting.
 */
class CcoPaletteSectionTest {

    private static final int CELLS_PER_LAYER = 16 * 16;
    private static final int VOLUME = CELLS_PER_LAYER * CcoSectionIndexing.SECTION_HEIGHT;

    @Test
    void newSectionIsUniformAir() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        assertTrue(section.isUniform());
        assertSame(TestBlocks.air(), section.uniformBlock());
        assertEquals(0, section.nonAirCount());
        assertEquals(-1, section.highestNonAirLocalY());
        for (int i = 0; i < VOLUME; i++) {
            assertSame(TestBlocks.air(), section.get(i));
        }
    }

    @Test
    void settingSameBlockReturnsFalseAndStaysUniform() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        assertFalse(section.set(100, TestBlocks.air()));
        assertTrue(section.isUniform());
    }

    @Test
    void firstDifferentBlockInflatesToIndexed() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        assertTrue(section.set(7, TestBlocks.block(1)));
        assertFalse(section.isUniform());
        assertSame(TestBlocks.block(1), section.get(7));
        assertSame(TestBlocks.air(), section.get(8));
        assertEquals(1, section.nonAirCount());
    }

    @Test
    void setBackToAirUpdatesNonAirCount() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        section.set(0, TestBlocks.block(5));
        section.set(1, TestBlocks.block(5));
        assertEquals(2, section.nonAirCount());
        section.set(0, TestBlocks.air());
        assertEquals(1, section.nonAirCount());
        assertSame(TestBlocks.air(), section.get(0));
    }

    @Test
    void roundTripAllCellsAcrossManyBlockTypes() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        Random random = new Random(42);
        int[] expected = new int[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            expected[i] = random.nextInt(64);
            section.set(i, TestBlocks.block(expected[i]));
        }
        for (int i = 0; i < VOLUME; i++) {
            assertSame(TestBlocks.block(expected[i]), section.get(i), "cell " + i);
        }
    }

    @Test
    void paletteOverflowWidensToShortIndices() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        // Write 400 distinct block types — more than the 256-entry byte palette.
        for (int i = 0; i < 400; i++) {
            section.set(i, TestBlocks.block(i + 1));
        }
        for (int i = 0; i < 400; i++) {
            assertSame(TestBlocks.block(i + 1), section.get(i), "cell " + i);
        }
        assertEquals(400, section.nonAirCount());
    }

    @Test
    void highestNonAirLocalYScansFromTop() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        // Place a block at local y=5 (cell index = 5 * cellsPerLayer + offset).
        section.set(5 * CELLS_PER_LAYER + 33, TestBlocks.block(2));
        assertEquals(5, section.highestNonAirLocalY());
        section.set(11 * CELLS_PER_LAYER, TestBlocks.block(3));
        assertEquals(11, section.highestNonAirLocalY());
    }

    @Test
    void uniformNonAirSectionReportsTopLayer() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.block(1));
        assertEquals(CcoSectionIndexing.SECTION_HEIGHT - 1, section.highestNonAirLocalY());
        assertEquals(VOLUME, section.nonAirCount());
    }

    @Test
    void copyIsIndependent() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        section.set(10, TestBlocks.block(4));
        CcoPaletteSection copy = section.copy();

        section.set(10, TestBlocks.block(9));
        section.set(11, TestBlocks.block(9));

        assertSame(TestBlocks.block(4), copy.get(10));
        assertSame(TestBlocks.air(), copy.get(11));
        assertEquals(1, copy.nonAirCount());
    }

    @Test
    void copyFromReplacesContents() {
        CcoPaletteSection source = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        source.set(3, TestBlocks.block(7));
        CcoPaletteSection target = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.block(1));

        target.copyFrom(source);

        assertSame(TestBlocks.block(7), target.get(3));
        assertSame(TestBlocks.air(), target.get(4));
        assertEquals(1, target.nonAirCount());
    }

    @Test
    void nullBlockIsStorableAndCountsAsAir() {
        CcoPaletteSection section = new CcoPaletteSection(CELLS_PER_LAYER, TestBlocks.air());
        section.set(0, TestBlocks.block(2));
        assertEquals(1, section.nonAirCount());
        // CcoBlockWriter.remove() stores null — must behave as air.
        assertTrue(section.set(0, null));
        assertNull(section.get(0));
        assertEquals(0, section.nonAirCount());
    }
}
