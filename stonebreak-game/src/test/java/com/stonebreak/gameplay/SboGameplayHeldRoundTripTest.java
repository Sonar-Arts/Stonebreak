package com.stonebreak.gameplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.format.sbo.SBOFormat;
import com.stonebreak.format.sbo.SBOParser;
import com.stonebreak.format.sbo.SBOSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for the {@code held} grip (#458): export, re-parse, editor-style re-save, the legacy top-level
 * layout the shipped SBOs keep, and record validation. Mirrors {@link SboGameplayWeaponRoundTripTest}.
 */
@Tag("regression")
class SboGameplayHeldRoundTripTest {

    @TempDir
    Path dir;

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static SboGameplay.HeldData bowGrip() {
        return new SboGameplay.HeldData("grip_l", new SboGameplay.Vec3(0f, 0f, -0.05f),
                new SboGameplay.Vec3(0f, -90f, 0f), 0.6f);
    }

    @Test
    void exportReparseAndResaveKeepTheHeldGrip() throws IOException {
        SBOFormat.ExportParameters params = params("test:bow");
        SboGameplay.of(params).withHeld(bowGrip()).applyTo(params);
        String out = dir.resolve("bow.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo(), out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertEquals(bowGrip(), SboGameplay.of(raw.manifest()).held());

        String resaved = dir.resolve("bow2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(bowGrip(), SboGameplay.of(new SBOParser().parseRaw(Path.of(resaved)).manifest()).held());
    }

    @Test
    void anItemWithoutOneUsesItsKindsDefault() throws IOException {
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params("test:plain"), omo(), out));
        assertFalse(SboGameplay.of(new SBOParser().parseRaw(Path.of(out)).manifest()).hasHeld());
    }

    @Test
    void theCodecReadsWritesAndDropsInvalidSections() throws IOException {
        SboGameplay.HeldData h = SboGameplayCodec.readHeld(JSON.readTree("""
                {"held": {"socket": " grip_r ", "position": [0, 0.25, -0.25], "rotation": [-45, -90, 0], "scale": 0.7}}"""));
        assertEquals(new SboGameplay.HeldData("grip_r", new SboGameplay.Vec3(0f, 0.25f, -0.25f),
                new SboGameplay.Vec3(-45f, -90f, 0f), 0.7f), h);
        // Encoded the way it was read (legacy layout = the same top-level field name).
        SboGameplay round = SboGameplayCodec.INSTANCE.decode(
                SboGameplayCodec.INSTANCE.encode(SboGameplay.NONE.withHeld(h)));
        assertEquals(h, round.held());
        assertTrue(SboGameplayCodec.LEGACY_FIELDS.contains("held"), "shipped SBOs keep it top level");

        assertNull(SboGameplayCodec.readHeld(JSON.readTree("{}")), "absent section = the kind's default");
        assertNull(SboGameplayCodec.readHeld(JSON.readTree("{\"held\": {\"position\": [0, 0, 0]}}")),
                "no socket = section ignored");
        assertNull(SboGameplayCodec.readHeld(JSON.readTree(
                "{\"held\": {\"socket\": \"grip_r\", \"position\": [0, 0]}}")), "a short vector = section ignored");
        assertNull(SboGameplayCodec.readHeld(JSON.readTree(
                "{\"held\": {\"socket\": \"grip_r\", \"scale\": -1}}")), "a negative scale = section ignored");
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.HeldData(" ", null, null, 1f));
        assertThrows(NullPointerException.class, () -> new SboGameplay.HeldData(null, null, null, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.HeldData("grip_r", null, null, 0f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.Vec3(Float.NaN, 0f, 0f));
        assertEquals(SboGameplay.Vec3.ZERO, new SboGameplay.HeldData("grip_r", null, null, 1f).position());
    }
}
