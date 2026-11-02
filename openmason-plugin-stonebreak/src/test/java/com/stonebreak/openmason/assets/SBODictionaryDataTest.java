package com.stonebreak.openmason.assets;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.gameplay.SboGameplay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Data side of dictionary editing: the index, {@code #} refs, and the Fuel Table form. */
@Tag("regression")
class SBODictionaryDataTest {

    private static SBOFormat.Document doc(String id, String... dictionaries) {
        SBOFormat.Document d = new SBOFormat.Document("1.11", id, id, "block", "default", "-", "me", null, "now",
                "model.omo", null, null, List.of(), null, null, null);
        return SboGameplay.NONE.withDictionaries(List.of(dictionaries)).applyTo(d);
    }

    @Test
    void indexAggregatesMembersPerDictionary() {
        SBODictionaryIndex idx = SBODictionaryIndex.of(List.of(
                doc("t:oak", "t:planks"), doc("t:pine", "t:planks", "t:fuel"), doc("t:stone")));
        assertEquals(List.of("t:fuel", "t:planks"), idx.ids());
        assertEquals(List.of("t:oak", "t:pine"), idx.members("#t:planks"));
        assertEquals(List.of("t:oak", "t:pine"), idx.members("t:planks"));
        assertEquals(List.of(), idx.members("#t:nope"));
    }

    @Test
    void dictionaryIconsCycleThroughMembers() {
        SBODictionaryIndex idx = SBODictionaryIndex.of(List.of(doc("t:oak", "t:planks"), doc("t:pine", "t:planks")));
        assertEquals("t:oak", idx.displayObjectId("#t:planks", 0));
        assertEquals("t:pine", idx.displayObjectId("#t:planks", 1500));
        assertEquals("t:oak", idx.displayObjectId("#t:planks", 2000));
        assertEquals("t:stone", idx.displayObjectId("t:stone", 1500), "plain ids are themselves");
        assertNull(idx.displayObjectId("#t:nope", 0));
        assertTrue(idx.describe("#t:planks").contains("2 members"));
        assertTrue(idx.describe("#t:nope").contains("unknown dictionary"));
    }

    @Test
    void refHelpers() {
        assertTrue(SBODictionaryIndex.isRef(" #a:b"));
        assertFalse(SBODictionaryIndex.isRef("a:b"));
        assertEquals("#a:b", SBODictionaryIndex.ref("a:b"));
        assertEquals("#a:b", SBODictionaryIndex.ref("#a:b"));
        assertEquals("a:b", SBODictionaryIndex.strip("# a:b"));
    }

    @Test
    void fuelTableRoundTripsThroughTheForm() {
        SboGameplay.FuelTableData table = new SboGameplay.FuelTableData(List.of(
                new SboGameplay.FuelTableEntry("#stonebreak:logs", 300),
                new SboGameplay.FuelTableEntry("stonebreak:stick", 100)));
        SBOFuelTableSection form = new SBOFuelTableSection(null);
        form.setFrom(table);
        assertEquals(table, form.toFuelTableData());

        form.add("", 50); // a row with no ingredient yet is dropped, not saved
        assertEquals(table, form.toFuelTableData());

        form.setFrom(null);
        assertNull(form.toFuelTableData(), "no entries = no fuelTable field");
    }

    @Test
    void recipeSlotsKeepDictionaryRefs() {
        SboGameplay.RecipeData data = new SboGameplay.RecipeData(List.of(new SboGameplay.ShapedRecipe(2, 1,
                List.of("#stonebreak:wooden_planks", "stonebreak:stick"), 1)));
        SBORecipeSection form = new SBORecipeSection(() -> {});
        form.setFromRecipeData(data);
        assertEquals(data, form.toRecipeData());
    }
}
