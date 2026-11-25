package com.stonebreak.format.sbe;

import com.openmason.engine.entity.model.EntityModelSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SBE 1.7 (Stonebreak #456): the optional {@code character.omchar} entry - written by both export paths with its
 * checksum, verified on parse, kept by the editor's raw round trip, handed to the engine importer; 1.6 files without
 * it load unchanged. The bytes are opaque at this layer (the engine reads the .omchar).
 */
@Tag("regression")
class SBECharacterRoundTripTest {

    @TempDir
    Path dir;

    private static final byte[] OMCHAR = "fake-omchar".getBytes(StandardCharsets.UTF_8);

    private SBEFormat.ExportParameters params() {
        SBEFormat.ExportParameters params = new SBEFormat.ExportParameters();
        params.setObjectId("test:hero");
        params.setObjectName("Hero");
        params.setEntityType("player");
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    private Path omo() throws IOException {
        return Files.write(dir.resolve("model.omo"), "fake-omo".getBytes(StandardCharsets.UTF_8));
    }

    private Path exportWithCharacter(String name) throws IOException {
        SBEFormat.ExportParameters params = params();
        params.setCharacterSource(Files.write(dir.resolve("hero.omchar"), OMCHAR));
        Path out = dir.resolve(name);
        assertTrue(new SBESerializer().export(params, omo(), out.toString()));
        return out;
    }

    @Test
    void exportEmbedsTheCharacterAndParseReadsIt() throws IOException {
        Path out = exportWithCharacter("hero.sbe");
        SBEParser.ParsedSBE parsed = new SBEParser().parse(out);
        assertEquals("1.7", parsed.document().version());
        assertTrue(parsed.document().hasCharacter());
        assertEquals(SBEFormat.CHARACTER_FILENAME, parsed.document().character().filename());
        assertArrayEquals(OMCHAR, parsed.characterBytes());
        assertTrue(parsed.document().extensions().isEmpty(), "character is a core field, never kept as a foreign one");
        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertNotNull(zip.getEntry(SBEFormat.CHARACTER_FILENAME));
        }
    }

    @Test
    void theEditorRoundTripKeepsAndReplacesIt() throws IOException {
        Path out = exportWithCharacter("hero.sbe");
        SBEParser.RawParse raw = new SBEParser().parseRaw(out);
        assertArrayEquals(OMCHAR, raw.stateAssetBytes().get(SBEFormat.CHARACTER_FILENAME));
        Path resaved = dir.resolve("hero2.sbe");
        assertTrue(new SBESerializer().exportFromDocument(raw.manifest(), raw.omoBytes(), raw.stateAssetBytes(),
                resaved.toString()));
        assertArrayEquals(OMCHAR, new SBEParser().parse(resaved).characterBytes());

        // A new definition: the checksum follows the bytes.
        byte[] other = "another-omchar".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> assets = new java.util.LinkedHashMap<>(raw.stateAssetBytes());
        assets.put(SBEFormat.CHARACTER_FILENAME, other);
        Path replaced = dir.resolve("hero3.sbe");
        assertTrue(new SBESerializer().exportFromDocument(raw.manifest(), raw.omoBytes(), assets, replaced.toString()));
        assertArrayEquals(other, new SBEParser().parse(replaced).characterBytes());

        // Removing it: no entry, no manifest key.
        Path removed = dir.resolve("hero4.sbe");
        assertTrue(new SBESerializer().exportFromDocument(raw.manifest().withCharacter(null), raw.omoBytes(),
                raw.stateAssetBytes(), removed.toString()));
        assertFalse(new SBEParser().parse(removed).document().hasCharacter());
        try (ZipFile zip = new ZipFile(removed.toFile())) {
            assertNull(zip.getEntry(SBEFormat.CHARACTER_FILENAME));
            assertFalse(new String(zip.getInputStream(zip.getEntry(SBEFormat.MANIFEST_FILENAME)).readAllBytes(),
                    StandardCharsets.UTF_8).contains("\"character\""));
        }
    }

    @Test
    void aTamperedCharacterIsRefused() throws IOException {
        Path out = exportWithCharacter("hero.sbe");
        Path tampered = dir.resolve("tampered.sbe");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(out));
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tampered))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().equals(SBEFormat.CHARACTER_FILENAME)) data = "evil".getBytes(StandardCharsets.UTF_8);
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(data);
                zos.closeEntry();
            }
        }
        assertThrows(SBEParser.SBEParseException.class, () -> new SBEParser().parse(tampered));
    }

    @Test
    void theImporterHandsTheCharacterToTheEngineAndOlderFilesHaveNone() throws IOException {
        Path out = exportWithCharacter("hero.sbe");
        try (InputStream in = Files.newInputStream(out)) {
            EntityModelSource source = new SBECodec().importEntity("hero.sbe", in);
            assertArrayEquals(OMCHAR, source.character());
        }
        Path plain = dir.resolve("plain.sbe");
        assertTrue(new SBESerializer().export(params(), omo(), plain.toString()));
        try (InputStream in = Files.newInputStream(plain)) {
            assertNull(new SBECodec().importEntity("plain.sbe", in).character());
        }
    }
}
