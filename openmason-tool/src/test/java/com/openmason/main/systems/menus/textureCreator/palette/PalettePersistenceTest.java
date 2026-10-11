package com.openmason.main.systems.menus.textureCreator.palette;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palette library persistence against an in-memory preferences store: JSON
 * round trip, legacy single-palette migration, and recovery from bad data.
 */
class PalettePersistenceTest {

    /** In-memory stand-in for the two preference properties. */
    private static final class MemoryStore implements PalettePersistence.Store {
        String json;
        List<Integer> legacy = new ArrayList<>();

        @Override
        public String palettesJson() {
            return json;
        }

        @Override
        public void setPalettesJson(String json) {
            this.json = json;
        }

        @Override
        public List<Integer> legacyPalette() {
            return legacy;
        }
    }

    @Test
    @Tag("regression")
    void saveThenLoadReproducesEveryPaletteTheOrderAndTheActiveName() {
        MemoryStore store = new MemoryStore();
        PalettePersistence persistence = new PalettePersistence(store);
        Map<String, List<Integer>> palettes = new LinkedHashMap<>();
        palettes.put("Zeta", List.of(0xFF0000FF, 0x00000000, 0x80FFFFFF));
        palettes.put("Alpha", List.of(0xFFFFFFFF));
        palettes.put("Empty", List.of());
        PaletteLibrary library = new PaletteLibrary();
        library.setAll(palettes, "Alpha");

        persistence.save(library);
        PaletteLibrary loaded = persistence.load();

        assertEquals(List.of("Zeta", "Alpha", "Empty"), loaded.getNames(), "creation order, not sorted");
        assertEquals("Alpha", loaded.getActiveName());
        assertEquals(palettes, loaded.getAll());
    }

    @Test
    void colorsAreStoredAsUpperCaseEightDigitHex() {
        MemoryStore store = new MemoryStore();
        PaletteLibrary library = new PaletteLibrary();
        library.setAll(Map.of("P", List.of(0xFF0000FF, 0x0000000A)), "P");

        new PalettePersistence(store).save(library);

        assertEquals("{\"active\":\"P\",\"palettes\":{\"P\":[\"FF0000FF\",\"0000000A\"]}}", store.json);
    }

    @Test
    void legacySinglePaletteIsMigratedIntoDefault() {
        MemoryStore store = new MemoryStore();
        store.legacy = List.of(0xFF112233, 0xFF445566);

        PaletteLibrary loaded = new PalettePersistence(store).load();

        assertEquals(List.of(PaletteLibrary.DEFAULT_PALETTE_NAME), loaded.getNames());
        assertEquals(List.of(0xFF112233, 0xFF445566), loaded.getActiveModel().getSwatches());
    }

    @Test
    void savedLibraryWinsOverTheLegacyProperty() {
        MemoryStore store = new MemoryStore();
        store.legacy = List.of(0xFF112233);
        store.json = "{\"active\":\"Mine\",\"palettes\":{\"Mine\":[\"FF00FF00\"]}}";

        PaletteLibrary loaded = new PalettePersistence(store).load();

        assertEquals(List.of("Mine"), loaded.getNames());
        assertEquals(List.of(0xFF00FF00), loaded.getActiveModel().getSwatches());
    }

    @Test
    void nothingPersistedGivesTheDefaultPalette() {
        PaletteLibrary loaded = new PalettePersistence(new MemoryStore()).load();

        assertEquals(PaletteLibrary.DEFAULT_PALETTE_NAME, loaded.getActiveName());
        assertEquals(32, loaded.getActiveModel().size());
    }

    @Test
    void corruptJsonFallsBackToLegacyThenDefault() {
        MemoryStore store = new MemoryStore();
        store.json = "{\"palettes\": [this is not json";
        store.legacy = List.of(0xFF010203);

        PaletteLibrary loaded = new PalettePersistence(store).load();

        assertEquals(List.of(0xFF010203), loaded.getActiveModel().getSwatches());
    }

    @Test
    void invalidColorEntriesAreDroppedButTheRestOfThePaletteSurvives() {
        MemoryStore store = new MemoryStore();
        store.json = "{\"palettes\":{\"P\":[\"FF0000FF\",\"not-hex\",\"FFFFFFFF\"]}}"; // no "active"

        PaletteLibrary loaded = new PalettePersistence(store).load();

        assertEquals("P", loaded.getActiveName(), "a missing active name selects the first palette");
        assertEquals(List.of(0xFF0000FF, 0xFFFFFFFF), loaded.getActiveModel().getSwatches());
    }

    @Test
    void blankJsonIsTreatedAsNothingSaved() {
        MemoryStore store = new MemoryStore();
        store.json = "   ";

        assertTrue(new PalettePersistence(store).load().getNames().contains(PaletteLibrary.DEFAULT_PALETTE_NAME));
    }
}
