package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMO v1.7 attachment point round trip: tool serializer → tool deserializer,
 * plus a cross-check through the engine {@link OMOReader} so the JSON field
 * names the tool writes are exactly what the runtime reads (the contract test).
 */
class OMOAttachmentRoundTripTest {

    @TempDir
    Path dir;

    /** Uses the pre-scale convenience constructor — must round-trip as unit scale. */
    private static final OMOFormat.AttachmentPointEntry FACE =
            new OMOFormat.AttachmentPointEntry("a1", "face", "part-head", "head",
                    0f, 1.5f, 0.25f, 0f, 180f, 0f);
    /** Carries an explicit non-unit scale for the attached model. */
    private static final OMOFormat.AttachmentPointEntry ROOT_SOCKET =
            new OMOFormat.AttachmentPointEntry("a2", "root_socket", null, null,
                    1f, 2f, 3f, 10f, 20f, 30f, 0.5f, 2f, 0.5f);

    private Path saveModelWithSockets(List<OMOFormat.AttachmentPointEntry> sockets) throws Exception {
        Path texture = dir.resolve("dummy.omt");
        Files.write(texture, new byte[]{1, 2, 3, 4});

        BlockModel model = new BlockModel("SocketTest", new CubeGeometry(), texture);
        Path omoPath = dir.resolve("socket_test.omo");

        OMOSerializer serializer = new OMOSerializer();
        if (sockets != null) {
            serializer.setAttachmentPointEntries(sockets);
        }
        assertTrue(serializer.save(model, omoPath.toString()), "save should succeed");
        return omoPath;
    }

    @Test
    void toolRoundTripPreservesSockets() throws Exception {
        Path omoPath = saveModelWithSockets(List.of(FACE, ROOT_SOCKET));

        OMODeserializer deserializer = new OMODeserializer();
        assertNotNull(deserializer.load(omoPath.toString()), "load should succeed");

        List<OMOFormat.AttachmentPointEntry> loaded =
                deserializer.getLastLoadedAttachmentPointEntries();
        assertNotNull(loaded);
        assertEquals(List.of(FACE, ROOT_SOCKET), loaded);
    }

    @Test
    void engineReaderParsesToolWrittenSockets() throws Exception {
        Path omoPath = saveModelWithSockets(List.of(FACE, ROOT_SOCKET));

        try (InputStream in = Files.newInputStream(omoPath)) {
            OMOReader.ReadResult result = new OMOReader().read(in);
            assertEquals(List.of(FACE, ROOT_SOCKET), result.attachmentPoints());
        }
    }

    @Test
    void modelWithoutSocketsRoundTripsToNull() throws Exception {
        Path omoPath = saveModelWithSockets(null);

        OMODeserializer deserializer = new OMODeserializer();
        assertNotNull(deserializer.load(omoPath.toString()));
        assertNull(deserializer.getLastLoadedAttachmentPointEntries());

        try (InputStream in = Files.newInputStream(omoPath)) {
            assertTrue(new OMOReader().read(in).attachmentPoints().isEmpty());
        }
    }
}
