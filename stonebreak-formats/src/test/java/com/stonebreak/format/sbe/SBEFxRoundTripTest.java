package com.stonebreak.format.sbe;

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

/** Round trip of the SBE 1.6 {@code fx[]} section (#103), the twin of {@code SBOFxRoundTripTest}. */
@Tag("regression")
class SBEFxRoundTripTest {

    @TempDir
    Path dir;

    static final FxData FX = new FxData(List.of(
            new FxDef("hurt", "feathers", 0f, 0.6f, 0f, Map.of("tint", FxParamValue.color(1f, 1f, 1f, 1f)), false),
            FxDef.of("death", "poof")));

    private SBEFormat.ExportParameters params() {
        SBEFormat.ExportParameters params = new SBEFormat.ExportParameters();
        params.setObjectId("test:goose");
        params.setObjectName("Goose");
        params.setEntityType("mob");
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    private Path omo() throws IOException {
        return Files.write(dir.resolve("model.omo"), "fake-omo".getBytes(StandardCharsets.UTF_8));
    }

    private static String manifestText(Path sbe) throws IOException {
        try (ZipFile zip = new ZipFile(sbe.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(SBEFormat.MANIFEST_FILENAME)).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    @Test
    void exportParseAndResaveKeepTheBindings() throws IOException {
        SBEFormat.ExportParameters params = params();
        params.setFx(FX);
        Path out = dir.resolve("goose.sbe");
        assertTrue(new SBESerializer().export(params, omo(), out.toString()));

        SBEParser.ParsedSBE parsed = new SBEParser().parse(out);
        assertEquals("1.6", parsed.document().version());
        assertTrue(parsed.document().hasFx());
        assertEquals(FX, parsed.document().fx());
        assertTrue(parsed.document().extensions().isEmpty(), "fx is a core field, never kept as a foreign one");

        SBEParser.RawParse raw = new SBEParser().parseRaw(out);
        Path resaved = dir.resolve("goose2.sbe");
        assertTrue(new SBESerializer().exportFromDocument(raw.manifest(), raw.omoBytes(), raw.stateAssetBytes(),
                resaved.toString()));
        assertEquals(FX, new SBEParser().parse(resaved).document().fx());
    }

    @Test
    void anSbeWithoutFxIsWrittenWithoutTheKey() throws IOException {
        Path out = dir.resolve("plain.sbe");
        assertTrue(new SBESerializer().export(params(), omo(), out.toString()));
        assertNull(new SBEParser().parse(out).document().fx());
        assertFalse(manifestText(out).contains("\"fx\""));

        SBEParser.RawParse raw = new SBEParser().parseRaw(out);
        Path resaved = dir.resolve("plain2.sbe");
        assertTrue(new SBESerializer().exportFromDocument(raw.manifest(), raw.omoBytes(), raw.stateAssetBytes(),
                resaved.toString()));
        assertFalse(manifestText(resaved).contains("\"fx\""));
    }

    @Test
    void documentCopiesCarryTheBindings() {
        SBEFormat.Document doc = new SBEFormat.Document("1.6", "test:x", "X", "mob", "p", "c", "a", null, "now",
                "model.omo", List.of(), List.of(), null, FX, null);
        assertEquals(FX, doc.withEntityType("npc").fx());
        assertEquals(FX, doc.withExtensions(null).fx());
        assertNull(doc.withFx(null).fx());
        assertNull(new SBEFormat.Document("1.5", "test:x", "X", "mob", "p", "c", "a", null, "now", "model.omo",
                List.of(), List.of(), null, null).fx());
    }
}
