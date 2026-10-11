package com.openmason.main.systems.menus.textureCreator.palette;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named palettes behind one shared {@link PaletteModel}: switching swaps the
 * model's contents in place and never loses unsaved swatch edits.
 */
class PaletteLibraryTest {

    @Test
    void emptyLoadFallsBackToTheDefaultPalette() {
        PaletteLibrary library = new PaletteLibrary();
        library.setAll(Map.of(), "whatever");

        assertEquals(List.of(PaletteLibrary.DEFAULT_PALETTE_NAME), library.getNames());
        assertEquals(PaletteLibrary.DEFAULT_PALETTE_NAME, library.getActiveName());
        assertEquals(32, library.getActiveModel().size());
    }

    @Test
    void unknownActiveNameFallsBackToTheFirstPalette() {
        PaletteLibrary library = libraryOf("Warm", List.of(1, 2), "Cool", List.of(3));
        library.setAll(library.getAll(), "Missing");

        assertEquals("Warm", library.getActiveName());
        assertEquals(List.of(1, 2), library.getActiveModel().getSwatches());
    }

    @Test
    void switchingKeepsEditsMadeToThePreviousPalette() {
        PaletteLibrary library = libraryOf("Warm", List.of(1, 2), "Cool", List.of(3));
        PaletteModel model = library.getActiveModel();

        model.add(99); // edit "Warm" through the shared model
        library.switchTo("Cool");

        assertSame(model, library.getActiveModel(), "views stay bound to one model instance");
        assertEquals(List.of(3), model.getSwatches());

        library.switchTo("Warm");
        assertEquals(List.of(1, 2, 99), model.getSwatches());
    }

    @Test
    void switchingToAnUnknownOrTheCurrentPaletteIsANoOp() {
        PaletteLibrary library = libraryOf("Warm", List.of(1), "Cool", List.of(3));
        AtomicInteger changes = new AtomicInteger();
        library.setChangeListener(changes::incrementAndGet);

        library.switchTo("Nope");
        library.switchTo("Warm");

        assertEquals("Warm", library.getActiveName());
        assertEquals(0, changes.get());
    }

    @Test
    void createTrimsTheNameRefusesDuplicatesAndSwitchesToTheNewPalette() {
        PaletteLibrary library = libraryOf("Warm", List.of(1), "Cool", List.of(3));
        AtomicInteger changes = new AtomicInteger();
        library.setChangeListener(changes::incrementAndGet);

        assertTrue(library.createPalette("  Night  "));
        assertEquals("Night", library.getActiveName());
        assertEquals(32, library.getActiveModel().size(), "new palettes start from the default swatches");
        assertEquals(List.of("Warm", "Cool", "Night"), library.getNames());

        assertFalse(library.createPalette("Night"));
        assertFalse(library.createPalette(" Night"));
        assertFalse(library.createPalette("   "));
        assertFalse(library.createPalette(null));
        assertEquals(1, changes.get());
    }

    @Test
    void deleteRemovesTheActivePaletteButNeverTheLastOne() {
        PaletteLibrary library = libraryOf("Warm", List.of(1), "Cool", List.of(3));
        library.switchTo("Cool");

        assertTrue(library.deleteActivePalette());
        assertEquals(List.of("Warm"), library.getNames());
        assertEquals("Warm", library.getActiveName());
        assertEquals(List.of(1), library.getActiveModel().getSwatches());

        assertFalse(library.deleteActivePalette());
        assertEquals(1, library.size());
    }

    @Test
    void getAllIncludesUnsnapshottedEditsAndReturnsACopy() {
        PaletteLibrary library = libraryOf("Warm", List.of(1), "Cool", List.of(3));
        library.getActiveModel().replace(0, 7);

        Map<String, List<Integer>> all = library.getAll();
        assertEquals(List.of(7), all.get("Warm"));

        all.get("Cool").add(42);
        assertEquals(List.of(3), library.getAll().get("Cool"));
    }

    private static PaletteLibrary libraryOf(String n1, List<Integer> c1, String n2, List<Integer> c2) {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        map.put(n1, c1);
        map.put(n2, c2);
        PaletteLibrary library = new PaletteLibrary();
        library.setAll(map, n1);
        return library;
    }
}
