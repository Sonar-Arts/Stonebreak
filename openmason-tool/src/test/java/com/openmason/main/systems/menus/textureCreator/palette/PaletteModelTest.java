package com.openmason.main.systems.menus.textureCreator.palette;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Swatch list editing, selection bookkeeping and change notification. */
class PaletteModelTest {

    @Test
    void defaultIsTheOpaqueDawnBringer32Palette() {
        PaletteModel model = PaletteModel.createDefault();

        assertEquals(32, model.size());
        assertEquals(0xFF000000, model.getColor(0), "black");
        assertEquals(0xFFFFFFFF, model.getColor(21), "white");
        // {223, 113, 38} packed A-B-G-R: R in the low byte.
        assertEquals(0xFF2671DF, model.getColor(5));
        for (int color : model.getSwatches()) {
            assertEquals(0xFF, color >>> 24, "every default swatch is opaque");
        }
        assertEquals(-1, model.getSelectedIndex());
    }

    @Test
    void addSelectsTheNewSwatchAndRemoveKeepsTheSelectionInRange() {
        PaletteModel model = new PaletteModel();
        model.add(0xFF0000FF);
        model.add(0xFF00FF00);
        model.add(0xFFFF0000);
        assertEquals(2, model.getSelectedIndex());

        model.remove(2);
        assertEquals(1, model.getSelectedIndex(), "selection clamps to the new last swatch");
        assertEquals(List.of(0xFF0000FF, 0xFF00FF00), model.getSwatches());

        model.remove(0);
        model.remove(0);
        assertEquals(-1, model.getSelectedIndex());
        assertEquals(0, model.size());
    }

    @Test
    void outOfRangeEditsAreIgnoredAndSelectionFallsBackToNone() {
        PaletteModel model = new PaletteModel();
        model.add(0xFF0000FF);
        AtomicInteger changes = new AtomicInteger();
        model.setChangeListener(changes::incrementAndGet);

        model.replace(5, 0xFF00FF00);
        model.remove(-1);
        model.remove(1);
        model.select(7);

        assertEquals(List.of(0xFF0000FF), model.getSwatches());
        assertEquals(-1, model.getSelectedIndex());
        assertEquals(0, changes.get(), "ignored edits must not notify");
    }

    @Test
    void everyStructuralChangeNotifiesButSelectionDoesNot() {
        PaletteModel model = new PaletteModel();
        AtomicInteger changes = new AtomicInteger();
        model.setChangeListener(changes::incrementAndGet);

        model.add(1);
        model.replace(0, 2);
        model.select(0);
        model.setSwatches(List.of(3, 4));
        model.remove(1);

        assertEquals(4, changes.get());
        assertEquals(List.of(3), model.getSwatches());
    }

    @Test
    void setSwatchesCopiesTheInputClearsSelectionAndAcceptsNull() {
        PaletteModel model = new PaletteModel();
        List<Integer> source = new ArrayList<>(List.of(10, 20));
        model.setSwatches(source);
        model.select(1);
        source.add(30);

        assertEquals(2, model.size(), "later edits to the source list must not leak in");
        model.setSwatches(source);
        assertEquals(-1, model.getSelectedIndex());

        model.setSwatches(null);
        assertEquals(0, model.size());
        assertThrows(UnsupportedOperationException.class, () -> model.getSwatches().add(1));
        assertTrue(model.getSwatches().isEmpty());
    }
}
