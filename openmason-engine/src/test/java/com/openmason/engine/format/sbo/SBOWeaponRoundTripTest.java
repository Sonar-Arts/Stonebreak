package com.openmason.engine.format.sbo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip coverage for the SBO 1.10 {@code weapon} section: export, re-parse,
 * editor-style re-save, manifests without it, and record validation.
 * Mirrors {@link SBOToolRoundTripTest}.
 */
@Tag("regression")
class SBOWeaponRoundTripTest {

    @TempDir
    Path dir;

    private Path omo() throws IOException {
        Path p = dir.resolve("model.omo");
        Files.write(p, "fake-omo".getBytes());
        return p;
    }

    private static SBOFormat.ExportParameters params(String id) {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId(id);
        params.setObjectName(id);
        params.setObjectType(SBOFormat.ObjectType.ITEM);
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    private static SBOFormat.WeaponData sword() {
        return new SBOFormat.WeaponData("one_handed", "slashing", 6f, 0.5f);
    }

    @Test
    void exportReparseAndResaveKeepTheWeaponSection() throws IOException {
        SBOFormat.ExportParameters params = params("test:sword");
        params.setWeapon(sword());
        String out = dir.resolve("sword.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo(), out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertEquals(SBOFormat.FORMAT_VERSION, raw.manifest().version());
        assertTrue(raw.manifest().hasWeapon());
        assertEquals(sword(), raw.manifest().weapon());

        String resaved = dir.resolve("sword2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(sword(), new SBOParser().parseRaw(Path.of(resaved)).manifest().weapon());
    }

    @Test
    void itemWithoutAWeaponSectionIsNotAWeapon() throws IOException {
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params("test:plain"), omo(), out));
        assertFalse(new SBOParser().parseRaw(Path.of(out)).manifest().hasWeapon());
    }

    @Test
    void parserNormalizesNamesAndDropsInvalidSections() throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SBOFormat.WeaponData w = SBOParser.parseWeapon(mapper.readTree("""
                {"weapon": {"group": " Two_Handed ", "damageType": "BLUNT", "damage": 5, "swingSeconds": 0.9}}"""));
        assertEquals(new SBOFormat.WeaponData("two_handed", "blunt", 5f, 0.9f), w);

        assertNull(SBOParser.parseWeapon(mapper.readTree("{}")), "absent section ⇒ null");
        assertNull(SBOParser.parseWeapon(mapper.readTree("{\"weapon\": null}")));
        assertNull(SBOParser.parseWeapon(mapper.readTree(
                "{\"weapon\": {\"group\": \"one_handed\", \"damageType\": \"blunt\", \"damage\": 1}}")),
                "missing swing time ⇒ section ignored");
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.WeaponData(" ", "blunt", 1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.WeaponData("a", "", 1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.WeaponData("a", "b", -1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.WeaponData("a", "b", 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.WeaponData("a", "b", Float.NaN, 1f));
        assertThrows(NullPointerException.class, () -> new SBOFormat.WeaponData(null, "b", 1f, 1f));
    }
}
