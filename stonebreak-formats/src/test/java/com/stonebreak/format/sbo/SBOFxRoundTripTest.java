package com.stonebreak.format.sbo;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.format.fx.FxParamValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trip of the SBO 1.12 {@code fx[]} section (#103): export, parse, editor-style re-save, and files without
 * the section stay without it.
 */
@Tag("regression")
class SBOFxRoundTripTest {

    @TempDir
    Path dir;

    static final FxData FX = new FxData(List.of(
            new FxDef("break", "debris", 0f, 0.5f, 0f, Map.of("count", FxParamValue.scalar(12f)), true),
            FxDef.of("ambient", "mygame:campfire")));

    private SBOFormat.ExportParameters params() {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId("test:stone");
        params.setObjectName("Stone");
        params.setObjectType(SBOFormat.ObjectType.BLOCK);
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    private Path omo() throws IOException {
        return Files.write(dir.resolve("model.omo"), "fake-omo".getBytes(StandardCharsets.UTF_8));
    }

    static String manifestText(Path sbo) throws IOException {
        try (ZipFile zip = new ZipFile(sbo.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(SBOFormat.MANIFEST_FILENAME)).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    @Test
    void exportParseAndResaveKeepTheBindings() throws IOException {
        SBOFormat.ExportParameters params = params();
        params.setFx(FX);
        Path out = dir.resolve("stone.sbo");
        assertTrue(new SBOSerializer().export(params, omo(), out.toString()));

        SBOParser.RawParse raw = new SBOParser().parseRaw(out);
        assertEquals("1.12", raw.manifest().version());
        assertTrue(raw.manifest().hasFx());
        assertEquals(FX, raw.manifest().fx());
        assertTrue(raw.manifest().extensions().isEmpty(), "fx is a core field, never kept as a foreign one");

        Path resaved = dir.resolve("stone2.sbo");
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(), raw.stateBytes(),
                raw.stateClipBytes(), raw.soundBytes(), resaved.toString()));
        assertEquals(FX, new SBOParser().parseRaw(resaved).manifest().fx());
    }

    @Test
    void anSboWithoutFxIsWrittenWithoutTheKey() throws IOException {
        Path out = dir.resolve("plain.sbo");
        assertTrue(new SBOSerializer().export(params(), omo(), out.toString()));
        SBOParser.RawParse raw = new SBOParser().parseRaw(out);
        assertNull(raw.manifest().fx());
        assertFalse(manifestText(out).contains("\"fx\""));

        Path resaved = dir.resolve("plain2.sbo");
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(), raw.stateBytes(),
                raw.stateClipBytes(), raw.soundBytes(), resaved.toString()));
        assertFalse(manifestText(resaved).contains("\"fx\""));
        assertEquals(raw.manifest().withFx(null).extensions(),
                new SBOParser().parseRaw(resaved).manifest().extensions());
    }

    @Test
    void documentCopiesCarryTheBindings() {
        SBOFormat.Document doc = new SBOFormat.Document("1.12", "test:x", "X", "block", "p", "c", "a", null,
                "now", "model.omo", null, null, List.of(), null, null, FX, null);
        assertEquals(FX, doc.withRendering(SBOFormat.Rendering.DEFAULT).fx());
        assertEquals(FX, doc.withExtensions(null).fx());
        assertNull(doc.withFx(null).fx());
        // The pre-1.12 constructor declares none.
        assertNull(new SBOFormat.Document("1.11", "test:x", "X", "block", "p", "c", "a", null, "now",
                "model.omo", null, null, List.of(), null, null, null).fx());
    }
}
