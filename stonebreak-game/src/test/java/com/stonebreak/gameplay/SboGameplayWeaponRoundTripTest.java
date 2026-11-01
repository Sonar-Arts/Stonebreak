package com.stonebreak.gameplay;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.stonebreak.items.ToolMiningRules;

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
class SboGameplayWeaponRoundTripTest {

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

    private static SboGameplay.WeaponData sword() {
        return new SboGameplay.WeaponData("one_handed", "slashing", 6f, 0.5f);
    }

    @Test
    void exportReparseAndResaveKeepTheWeaponSection() throws IOException {
        SBOFormat.ExportParameters params = params("test:sword");
        SboGameplay.of(params).withWeapon(sword()).applyTo(params);
        String out = dir.resolve("sword.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo(), out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertEquals(SBOFormat.FORMAT_VERSION, raw.manifest().version());
        assertTrue(SboGameplay.of(raw.manifest()).hasWeapon());
        assertEquals(sword(), SboGameplay.of(raw.manifest()).weapon());

        String resaved = dir.resolve("sword2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(sword(), SboGameplay.of(new SBOParser().parseRaw(Path.of(resaved)).manifest()).weapon());
    }

    @Test
    void itemWithoutAWeaponSectionIsNotAWeapon() throws IOException {
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params("test:plain"), omo(), out));
        assertFalse(SboGameplay.of(new SBOParser().parseRaw(Path.of(out)).manifest()).hasWeapon());
    }

    @Test
    void parserNormalizesNamesAndDropsInvalidSections() throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SboGameplay.WeaponData w = SboGameplayCodec.readWeapon(mapper.readTree("""
                {"weapon": {"group": " Two_Handed ", "damageType": "BLUNT", "damage": 5, "swingSeconds": 0.9}}"""));
        assertEquals(new SboGameplay.WeaponData("two_handed", "blunt", 5f, 0.9f), w);

        assertNull(SboGameplayCodec.readWeapon(mapper.readTree("{}")), "absent section ⇒ null");
        assertNull(SboGameplayCodec.readWeapon(mapper.readTree("{\"weapon\": null}")));
        assertNull(SboGameplayCodec.readWeapon(mapper.readTree(
                "{\"weapon\": {\"group\": \"one_handed\", \"damageType\": \"blunt\", \"damage\": 1}}")),
                "missing swing time ⇒ section ignored");
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.WeaponData(" ", "blunt", 1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.WeaponData("a", "", 1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.WeaponData("a", "b", -1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.WeaponData("a", "b", 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.WeaponData("a", "b", Float.NaN, 1f));
        assertThrows(NullPointerException.class, () -> new SboGameplay.WeaponData(null, "b", 1f, 1f));
    }
}
