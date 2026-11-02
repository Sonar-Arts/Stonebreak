package com.stonebreak.gameplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trip coverage for the {@code dictionaries} and {@code fuelTable} sections. */
class SboGameplayDictionaryRoundTripTest {

    @TempDir
    Path dir;

    private static final SboGameplay.FuelTableData TABLE = new SboGameplay.FuelTableData(List.of(
            new SboGameplay.FuelTableEntry("#stonebreak:logs", 300),
            new SboGameplay.FuelTableEntry("stonebreak:stick", 100)));

    @Test
    void exportReparseAndResaveKeepDictionariesAndFuelTable() throws IOException {
        Path omo = dir.resolve("model.omo");
        Files.write(omo, "fake-omo".getBytes());
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId("test:planks");
        params.setObjectName("Planks");
        params.setObjectType(SBOFormat.ObjectType.BLOCK);
        params.setObjectPack("test");
        params.setAuthor("junit");
        SboGameplay.of(params).withDictionaries(List.of("stonebreak:wooden_planks"))
                .withFuelTable(TABLE).applyTo(params);
        String out = dir.resolve("planks.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        SboGameplay g = SboGameplay.of(raw.manifest());
        assertEquals(List.of("stonebreak:wooden_planks"), g.dictionaries());
        assertEquals(TABLE, g.fuelTable());

        String resaved = dir.resolve("planks2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        SboGameplay again = SboGameplay.of(new SBOParser().parseRaw(Path.of(resaved)).manifest());
        assertEquals(g.dictionaries(), again.dictionaries());
        assertEquals(TABLE, again.fuelTable());
    }

    @Test
    void legacyTopLevelFieldsAreRead() throws IOException {
        var root = new ObjectMapper().readTree("""
                {"dictionaries": [" stonebreak:logs ", "", "#stonebreak:logs", "stonebreak:fuel"],
                 "fuelTable": {"entries": [{"ingredient": "#stonebreak:logs", "burnTicks": 300},
                                           {"ingredient": "", "burnTicks": 5},
                                           {"ingredient": "stonebreak:stick", "burnTicks": 0}]}}""");
        SboGameplay g = SboGameplayCodec.INSTANCE.decode(root);
        // trimmed, blank dropped, a stray '#' stripped, duplicates collapsed, order kept
        assertEquals(List.of("stonebreak:logs", "stonebreak:fuel"), g.dictionaries());
        assertEquals(List.of(new SboGameplay.FuelTableEntry("#stonebreak:logs", 300)), g.fuelTable().entries());
    }

    @Test
    void noDictionariesMeansEmptyAndNotWritten() {
        assertTrue(SboGameplay.NONE.dictionaries().isEmpty());
        assertTrue(SboGameplay.NONE.isEmpty());
        assertFalse(SboGameplay.NONE.withDictionaries(List.of("a:b")).isEmpty());
        assertFalse(SboGameplayCodec.INSTANCE.encode(SboGameplay.NONE.withFuel(new SboGameplay.FuelData(5)))
                .has("dictionaries"));
    }
}
