package com.openmason.engine.format.omo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMO 1.7 attachment points: manifest parsing, model-root vs part-bound
 * sockets, missing-field defaults, and pre-1.7 backward compatibility.
 */
class OMOAttachmentPointReadTest {

    private static final String MANIFEST_HEADER = """
            "objectName":"Test","modelType":"cube","textureFile":"texture.omt",
            "geometry":{"width":1,"height":1,"depth":1,"position":{"x":0,"y":0,"z":0}}
            """;

    /** Minimal OMO ZIP holding just the given manifest JSON. */
    private static byte[] buildOmo(String manifestJson) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(OMOFormat.MANIFEST_FILENAME));
            zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static OMOReader.ReadResult read(String manifestJson) throws IOException {
        return new OMOReader().read(new ByteArrayInputStream(buildOmo(manifestJson)));
    }

    @Test
    void readsPartBoundAndRootAttachmentPoints() throws IOException {
        String manifest = """
                {"version":"1.7",%s,
                 "attachmentPoints":[
                   {"id":"a1","name":"face","parentPartId":"p-head","parentPartName":"head",
                    "posX":0.0,"posY":1.5,"posZ":0.25,"rotX":0.0,"rotY":180.0,"rotZ":0.0,
                    "scaleX":0.5,"scaleY":2.0,"scaleZ":0.5},
                   {"id":"a2","name":"root_socket",
                    "posX":1.0,"posY":2.0,"posZ":3.0,"rotX":10.0,"rotY":20.0,"rotZ":30.0}
                 ]}
                """.formatted(MANIFEST_HEADER);

        OMOReader.ReadResult result = read(manifest);
        assertEquals(2, result.attachmentPoints().size());

        OMOFormat.AttachmentPointEntry face = result.attachmentPoints().get(0);
        assertEquals("a1", face.id());
        assertEquals("face", face.name());
        assertEquals("p-head", face.parentPartId());
        assertEquals("head", face.parentPartName());
        assertEquals(1.5f, face.posY());
        assertEquals(0.25f, face.posZ());
        assertEquals(180f, face.rotY());
        assertEquals(0.5f, face.scaleX());
        assertEquals(2.0f, face.scaleY());
        assertFalse(face.isModelRoot());

        OMOFormat.AttachmentPointEntry root = result.attachmentPoints().get(1);
        assertNull(root.parentPartId());
        assertNull(root.parentPartName());
        assertTrue(root.isModelRoot());
        assertEquals(3f, root.posZ());
        assertEquals(30f, root.rotZ());
        assertEquals(1f, root.scaleX()); // missing scale defaults to unit
    }

    @Test
    void pre17ManifestYieldsEmptyList() throws IOException {
        String manifest = """
                {"version":"1.6",%s}
                """.formatted(MANIFEST_HEADER);

        OMOReader.ReadResult result = read(manifest);
        assertTrue(result.attachmentPoints().isEmpty());
        assertEquals("Test", result.document().objectName());
    }

    @Test
    void partialEntryDefaultsRotationAndParent() throws IOException {
        String manifest = """
                {"version":"1.7",%s,
                 "attachmentPoints":[{"id":"a1","name":"tip","posX":0.5}]}
                """.formatted(MANIFEST_HEADER);

        OMOFormat.AttachmentPointEntry tip = read(manifest).attachmentPoints().get(0);
        assertEquals(0.5f, tip.posX());
        assertEquals(0f, tip.posY());
        assertEquals(0f, tip.rotX());
        assertEquals(0f, tip.rotY());
        assertEquals(0f, tip.rotZ());
        assertEquals(1f, tip.scaleX());
        assertEquals(1f, tip.scaleY());
        assertEquals(1f, tip.scaleZ());
        assertNull(tip.parentPartId());
        assertTrue(tip.isModelRoot());
    }
}
