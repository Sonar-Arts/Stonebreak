package com.stonebreak.mobs.sbe;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SbeEntityLoader#buildGeometry(OMOReader.ReadResult)} threads OMO
 * attachment points through to {@link SbeModelGeometry}, and
 * {@link SbeEntityLoader#loadAttachable} decodes accessory assets across
 * formats (bare .omo, .sbo with embedded model).
 */
class SbeEntityLoaderAttachmentTest {

    @TempDir
    Path dir;

    /** Minimal valid OMO ZIP: manifest with one-triangle mesh geometry. */
    private static byte[] buildOmoBytes() throws IOException {
        String manifest = """
                {"version":"1.7","objectName":"Accessory","modelType":"cube",
                 "textureFile":"texture.omt",
                 "geometry":{"width":1,"height":1,"depth":1,"position":{"x":0,"y":0,"z":0}},
                 "mesh":{"vertices":[0,0,0,1,0,0,0,1,0],"texCoords":[0,0,1,0,0,1],
                         "indices":[0,1,2],"triangleToFaceId":[0]}}
                """;
        return zip("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(String entryName, byte[] content, Object... moreEntries)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
            for (int i = 0; i < moreEntries.length; i += 2) {
                zos.putNextEntry(new ZipEntry((String) moreEntries[i]));
                zos.write((byte[]) moreEntries[i + 1]);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test
    void loadAttachableDecodesBareOmoFile() throws IOException {
        Path omo = dir.resolve("acc.omo");
        Files.write(omo, buildOmoBytes());

        SbeEntityAsset asset = SbeEntityLoader.loadAttachable(omo);
        assertNotNull(asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT));
        assertEquals(1, asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT).parts().size());
        assertTrue(asset.clips().isEmpty());
    }

    @Test
    void loadAttachableExtractsEmbeddedModelFromSbo() throws IOException {
        // A model-bearing SBO wraps the OMO as its "model.omo" entry (the
        // loader reads only that entry; the manifest is not consulted).
        String sboManifest = "{\"objectId\":\"test:acc\"}";
        Path sbo = dir.resolve("acc.sbo");
        Files.write(sbo, zip("manifest.json", sboManifest.getBytes(StandardCharsets.UTF_8),
                "model.omo", buildOmoBytes()));

        SbeEntityAsset asset = SbeEntityLoader.loadAttachable(sbo);
        SbeModelGeometry geometry = asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
        assertNotNull(geometry);
        assertFalse(geometry.parts().isEmpty());
    }

    @Test
    void loadAttachableRejectsTextureOnlySbo() throws IOException {
        Path sbo = dir.resolve("sprite.sbo");
        Files.write(sbo, zip("manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "texture.omt", new byte[]{1, 2, 3}));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SbeEntityLoader.loadAttachable(sbo));
        assertTrue(e.getCause().getMessage().contains("no embedded model"));
    }

    private static OMOReader.ReadResult readResult(List<OMOFormat.AttachmentPointEntry> sockets) {
        OMOFormat.Document document = new OMOFormat.Document(
                "1.7", "TestModel", "cube",
                new OMOFormat.GeometryData(1, 1, 1, new OMOFormat.Position(0, 0, 0)),
                "texture.omt");
        // One triangle so buildGeometry's mesh guard passes.
        ParsedMeshData mesh = new ParsedMeshData(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1},
                new int[]{0, 1, 2},
                new int[]{0},
                "FLAT");
        return new OMOReader.ReadResult(document, mesh, List.of(), List.of(), null,
                List.of(), sockets);
    }

    @Test
    void attachmentPointsAreThreadedIntoGeometry() throws IOException {
        SbeModelGeometry geometry = SbeEntityLoader.buildGeometry(readResult(List.of(
                new OMOFormat.AttachmentPointEntry("a1", "face", "p-head", "head",
                        0f, 1.5f, 0.25f, 0f, 180f, 0f, 0.5f, 0.5f, 0.5f),
                new OMOFormat.AttachmentPointEntry("a2", "root_socket", null, null,
                        1f, 2f, 3f, 0f, 0f, 0f))));

        assertEquals(2, geometry.attachmentPoints().size());

        SbeAttachmentPoint face = geometry.attachmentPoints().get(0);
        assertEquals("a1", face.id());
        assertEquals("face", face.name());
        assertEquals("p-head", face.parentPartId());
        assertEquals("head", face.parentPartName());
        assertEquals(1.5f, face.localPos().y);
        assertEquals(180f, face.localRotDeg().y);
        assertEquals(0.5f, face.localScale().x);

        SbeAttachmentPoint root = geometry.attachmentPoints().get(1);
        assertNull(root.parentPartId());
        assertTrue(root.isModelRoot());
        assertEquals(3f, root.localPos().z);
        assertEquals(1f, root.localScale().y); // pre-scale constructor defaults to unit scale
    }

    @Test
    void noAttachmentPointsYieldsEmptyList() throws IOException {
        SbeModelGeometry geometry = SbeEntityLoader.buildGeometry(readResult(List.of()));
        assertTrue(geometry.attachmentPoints().isEmpty());
    }
}
