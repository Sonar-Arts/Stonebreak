package com.openmason.engine.format.sbt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SBT is the one shipped format that had no round-trip test: what the serializer writes, the
 * parser must hand back byte-for-byte (the embedded OMT) and field-for-field (the manifest), with
 * a checksum that actually matches the payload. The corruption cases matter just as much — a
 * texture pack from disk is exactly the kind of file that arrives truncated.
 */
class SBTRoundTripTest {

    @TempDir
    Path dir;

    /** Deterministic stand-in for an OMT payload; SBT embeds it opaquely. */
    private static byte[] fakeOmtBytes() {
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    private static SBTFormat.ExportParameters validParams() {
        SBTFormat.ExportParameters params = new SBTFormat.ExportParameters();
        params.setTextureId("stone_bricks");
        params.setTextureName("Stone Bricks");
        params.setTextureType(SBTFormat.TextureType.BLOCK);
        params.setTexturePack("default");
        params.setAuthor("Harness");
        params.setDescription("A round-trip fixture");
        return params;
    }

    private Path writeOmt() throws IOException {
        Path omt = dir.resolve("source.omt");
        Files.write(omt, fakeOmtBytes());
        return omt;
    }

    @Test
    void whatTheSerializerWritesTheParserReadsBack() throws IOException {
        Path out = dir.resolve("stone.sbt");
        assertTrue(new SBTSerializer().export(validParams(), writeOmt(), out.toString()));

        SBTParser.Result result = new SBTParser().read(out);
        SBTFormat.Document manifest = result.manifest();

        assertEquals(SBTFormat.FORMAT_VERSION, manifest.version());
        assertEquals("stone_bricks", manifest.textureId());
        assertEquals("Stone Bricks", manifest.textureName());
        assertEquals(SBTFormat.TextureType.BLOCK.getId(), manifest.textureType());
        assertEquals("default", manifest.texturePack());
        assertEquals("Harness", manifest.author());
        assertEquals("A round-trip fixture", manifest.description());
        assertEquals(SBTFormat.EMBEDDED_OMT_FILENAME, manifest.omtFilename());
        assertArrayEquals(fakeOmtBytes(), result.omtBytes(),
                "the embedded OMT must survive byte-for-byte");
    }

    @Test
    void theManifestChecksumMatchesTheEmbeddedBytes() throws Exception {
        Path out = dir.resolve("checked.sbt");
        assertTrue(new SBTSerializer().export(validParams(), writeOmt(), out.toString()));

        SBTParser.Result result = new SBTParser().read(out);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance(SBTFormat.CHECKSUM_ALGORITHM).digest(result.omtBytes()));
        assertEquals(expected, result.manifest().checksum(),
                "checksum must be the SHA-256 of the bytes actually embedded");
    }

    @Test
    void aBlankDescriptionRoundTripsAsAbsent() throws IOException {
        SBTFormat.ExportParameters params = validParams();
        params.setDescription("");
        Path out = dir.resolve("no_desc.sbt");
        assertTrue(new SBTSerializer().export(params, writeOmt(), out.toString()));

        assertNull(new SBTParser().read(out).manifest().description());
    }

    @Test
    void exportAppendsTheExtensionWhenTheCallerForgotIt() throws IOException {
        String bare = dir.resolve("forgot_extension").toString();
        assertTrue(new SBTSerializer().export(validParams(), writeOmt(), bare));

        assertTrue(Files.exists(Path.of(bare + SBTFormat.FILE_EXTENSION)),
                "output must land at the corrected path");
        assertEquals(bare + SBTFormat.FILE_EXTENSION, SBTFormat.ensureExtension(bare));
    }

    @Test
    void invalidParametersRefuseToExport() throws IOException {
        SBTFormat.ExportParameters params = validParams();
        params.setAuthor("");
        Path out = dir.resolve("rejected.sbt");

        assertFalse(new SBTSerializer().export(params, writeOmt(), out.toString()));
        assertFalse(Files.exists(out), "a refused export must leave no file behind");
    }

    @Test
    void aMissingSourceOmtRefusesToExport() {
        assertFalse(new SBTSerializer().export(
                validParams(), dir.resolve("nowhere.omt"), dir.resolve("out.sbt").toString()));
    }

    @Test
    void anArchiveMissingItsManifestIsRejected() throws IOException {
        byte[] archive = zipWithSingleEntry(SBTFormat.EMBEDDED_OMT_FILENAME, fakeOmtBytes());
        IOException e = assertThrows(IOException.class, () -> new SBTParser().read(archive));
        assertTrue(e.getMessage().contains(SBTFormat.MANIFEST_FILENAME));
    }

    @Test
    void anArchiveMissingItsTextureIsRejected() throws IOException {
        byte[] archive = zipWithSingleEntry(SBTFormat.MANIFEST_FILENAME, "{}".getBytes());
        IOException e = assertThrows(IOException.class, () -> new SBTParser().read(archive));
        assertTrue(e.getMessage().contains(SBTFormat.EMBEDDED_OMT_FILENAME));
    }

    private static byte[] zipWithSingleEntry(String name, byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(data);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
