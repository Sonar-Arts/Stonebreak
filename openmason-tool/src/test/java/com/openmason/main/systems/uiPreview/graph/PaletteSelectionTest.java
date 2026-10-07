package com.openmason.main.systems.uiPreview.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteSelectionTest {

    @Test
    void arrowsClampAtBothEnds() {
        PaletteSelection s = new PaletteSelection();
        s.setCount(3);
        s.move(-1);
        assertEquals(0, s.index());
        s.move(1);
        s.move(1);
        s.move(1);
        assertEquals(2, s.index());
        assertTrue(s.isSelected(2));
        assertFalse(s.isSelected(0));
    }

    @Test
    void aNewResultListStartsAtTheTop() {
        PaletteSelection s = new PaletteSelection();
        s.setCount(10);
        s.set(7);
        assertFalse(s.setCount(10), "same size keeps the highlight");
        assertEquals(7, s.index());
        assertTrue(s.setCount(4));
        assertEquals(0, s.index());
    }

    @Test
    void shrinkingNeverLeavesTheIndexOutOfRange() {
        PaletteSelection s = new PaletteSelection();
        s.setCount(10);
        s.set(9);
        s.setCount(3);
        assertTrue(s.index() < 3);
    }

    @Test
    void emptyListSelectsNothing() {
        PaletteSelection s = new PaletteSelection();
        s.setCount(0);
        s.move(1);
        assertEquals(0, s.index());
        assertNull(s.selected(List.of()));
        assertFalse(s.isSelected(0));
    }

    @Test
    void selectedReturnsTheHighlightedItem() {
        PaletteSelection s = new PaletteSelection();
        s.setCount(3);
        s.move(1);
        assertEquals("b", s.selected(List.of("a", "b", "c")));
    }
}
