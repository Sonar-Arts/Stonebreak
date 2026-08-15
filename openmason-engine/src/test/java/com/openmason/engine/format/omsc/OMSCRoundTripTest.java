package com.openmason.engine.format.omsc;

import com.openmason.engine.format.omo.OMOFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and structural guarantees for the {@code .omsc} container.
 *
 * <p>Uses fake byte payloads rather than real OMOs: everything asserted here is about the
 * container (dedupe, checksums, reference fields, tolerance), and the whole point of the
 * embedding rule is that the bytes are carried verbatim without being understood.
 */
@Tag("regression")
class OMSCRoundTripTest {

    @TempDir
    Path tempDir;

    private static final byte[] CUBE_BYTES = "fake-omo-cube-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STALL_BYTES = "fake-omo-stall-payload".getBytes(StandardCharsets.UTF_8);

    private static OMSCFormat.ModelRef ref(String sessionId, String path, String sourceName) {
        return new OMSCFormat.ModelRef(sessionId, path, sourceName,
                OMSCFormat.modelEntryPath(sessionId), "", 0);
    }

    private static OMSCFormat.InstanceEntry instance(String id, String name, String modelId,
                                                     OMOFormat.ModelTransform transform) {
        return new OMSCFormat.InstanceEntry(id, name, modelId, transform, true, false);
    }

    private static OMSCFormat.Document doc(List<OMSCFormat.ModelRef> models,
                                           List<OMSCFormat.InstanceEntry> instances) {
        return new OMSCFormat.Document("1.0", "Village Square", "chace", "test scene",
                "2026-08-09T14:02:11Z", "2026-08-09T15:40:03Z",
                models, instances,
                new OMSCFormat.CameraState("ARCBALL", 12.5f, 28f, 135f, 60f, 0f, 1f, 0f),
                new OMSCFormat.ViewportState(0, 0, true, true, false, false, true, true, 0.5f));
    }

    private Path save(OMSCFormat.Document document, Map<String, byte[]> bytes) {
        Path out = tempDir.resolve("scene.omsc");
        assertTrue(new OMSCSerializer().save(document, bytes, out.toString()), "save must succeed");
        return out;
    }

    @Test
    @DisplayName("a scene round-trips with its instances, transforms and camera intact")
    void roundTripsFully() throws IOException {
        OMOFormat.ModelTransform placed = new OMOFormat.ModelTransform(
                -3.5f, 0f, 1.25f, 0f, 90f, 0f, 1f, 1f, 1f);

        Path file = save(
                doc(List.of(ref("well", "Well.omo", "Well.omo"), ref("stall", "Stall.omo", "Stall.omo")),
                        List.of(instance("i1", "Well", "well", null),
                                instance("i2", "Stall.001", "stall", placed))),
                Map.of("well", CUBE_BYTES, "stall", STALL_BYTES));

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);

        assertEquals("1.0", parsed.manifest().version());
        assertEquals("Village Square", parsed.manifest().sceneName());
        assertEquals(2, parsed.manifest().models().size());
        assertEquals(2, parsed.manifest().instances().size());

        OMSCFormat.InstanceEntry stall = parsed.manifest().instances().stream()
                .filter(i -> i.name().equals("Stall.001")).findFirst().orElseThrow();
        assertEquals(placed, stall.transform());

        OMSCFormat.InstanceEntry well = parsed.manifest().instances().stream()
                .filter(i -> i.name().equals("Well")).findFirst().orElseThrow();
        assertTrue(well.transform().isIdentity(), "an omitted transform reads back as identity");

        assertEquals(135f, parsed.manifest().camera().yaw(), 1e-4);
        assertEquals(1f, parsed.manifest().camera().targetY(), 1e-4);
        assertEquals(0.5f, parsed.manifest().viewport().gridSnappingIncrement(), 1e-4);
    }

    @Test
    @DisplayName("embedded model bytes survive verbatim")
    void embeddedBytesSurviveVerbatim() throws IOException {
        Path file = save(
                doc(List.of(ref("well", "Well.omo", "Well.omo")),
                        List.of(instance("i1", "Well", "well", null))),
                Map.of("well", CUBE_BYTES));

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);
        byte[] stored = parsed.modelBytesById().values().iterator().next();

        assertArrayEquals(CUBE_BYTES, stored, "embedding must never re-encode");
    }

    @Test
    @DisplayName("five instances of one model produce exactly one embedded entry")
    void sharedModelIsEmbeddedOnce() throws IOException {
        Path file = save(
                doc(List.of(ref("stall", "Stall.omo", "Stall.omo")),
                        List.of(instance("i1", "A", "stall", null),
                                instance("i2", "B", "stall", null),
                                instance("i3", "C", "stall", null),
                                instance("i4", "D", "stall", null),
                                instance("i5", "E", "stall", null))),
                Map.of("stall", STALL_BYTES));

        assertEquals(1, countModelEntries(file));

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);
        assertEquals(1, parsed.manifest().models().size());
        assertEquals(5, parsed.manifest().instances().size());
        String onlyModelId = parsed.manifest().models().getFirst().modelId();
        assertTrue(parsed.manifest().instances().stream().allMatch(i -> i.modelId().equals(onlyModelId)));
    }

    @Test
    @DisplayName("two session models with identical bytes collapse to one entry")
    void identicalContentDedupes() throws IOException {
        // A copied model: different paths, same bytes. Content-hash ids make the merge
        // fall out of the data rather than needing bookkeeping.
        Path file = save(
                doc(List.of(ref("a", "Copy1.omo", "Copy1.omo"), ref("b", "Copy2.omo", "Copy2.omo")),
                        List.of(instance("i1", "A", "a", null), instance("i2", "B", "b", null))),
                Map.of("a", CUBE_BYTES, "b", CUBE_BYTES));

        assertEquals(1, countModelEntries(file));

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);
        assertEquals(1, parsed.manifest().models().size());
        assertNotNull(parsed.manifest().models().getFirst().path(), "a source reference survives the merge");
        assertEquals(2, parsed.manifest().instances().size());
    }

    @Test
    @DisplayName("reference paths survive verbatim, relative or absolute")
    void pathsSurviveVerbatim() throws IOException {
        String absolute = tempDir.resolve("Outside.omo").toAbsolutePath().toString();
        Path file = save(
                doc(List.of(ref("rel", "Well.omo", "Well.omo"), ref("abs", absolute, "Outside.omo")),
                        List.of(instance("i1", "A", "rel", null), instance("i2", "B", "abs", null))),
                Map.of("rel", CUBE_BYTES, "abs", STALL_BYTES));

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);
        List<String> paths = parsed.manifest().models().stream().map(OMSCFormat.ModelRef::path).toList();

        assertTrue(paths.contains("Well.omo"), "the engine normalizes nothing");
        assertTrue(paths.contains(absolute));
    }

    @Test
    @DisplayName("an empty scene is a valid file")
    void emptySceneRoundTrips() throws IOException {
        Path file = save(doc(List.of(), List.of()), Map.of());

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);

        assertTrue(parsed.manifest().models().isEmpty());
        assertFalse(parsed.manifest().hasInstances());
        assertEquals(0, countModelEntries(file));
    }

    @Test
    @DisplayName("a checksum mismatch warns but still parses")
    void checksumMismatchStillParses() throws IOException {
        Path file = tempDir.resolve("tampered.omsc");
        String manifest = """
                {
                  "version": "1.0",
                  "sceneName": "Tampered",
                  "models": [ { "modelId": "abc123", "path": "Well.omo", "sourceName": "Well.omo",
                                "file": "models/abc123/model.omo", "checksum": "deadbeef", "size": 4 } ],
                  "instances": [ { "id": "i1", "name": "Well", "modelId": "abc123" } ]
                }
                """;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry(OMSCFormat.MANIFEST_FILENAME));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("models/abc123/model.omo"));
            zos.write(CUBE_BYTES);
            zos.closeEntry();
        }

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(file);

        assertEquals(1, parsed.manifest().instances().size());
        assertArrayEquals(CUBE_BYTES, parsed.modelBytesById().get("abc123"),
                "the bytes are returned untouched despite the bad checksum");
    }

    @Test
    @DisplayName("a declared model with no embedded entry is tolerated")
    void missingEmbeddedEntryTolerated() throws IOException {
        // Deliberately softer than SBO: an .OMSC can still resolve this model by path.
        Path file = tempDir.resolve("refonly.omsc");
        String manifest = """
                {
                  "version": "1.0",
                  "sceneName": "Reference only",
                  "models": [ { "modelId": "abc123", "path": "Well.omo", "sourceName": "Well.omo",
                                "file": "models/abc123/model.omo" } ],
                  "instances": [ { "id": "i1", "name": "Well", "modelId": "abc123" } ]
                }
                """;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry(OMSCFormat.MANIFEST_FILENAME));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        OMSCParseResult parsed = new OMSCParser().parse(file);

        assertEquals(1, parsed.instances().size());
        assertFalse(parsed.hasEmbeddedCopy("abc123"));
        assertNotNull(parsed.refFor("abc123").path(), "the path reference is still usable");
    }

    @Test
    @DisplayName("an archive without a manifest is rejected")
    void missingManifestRejected() throws IOException {
        Path file = tempDir.resolve("bogus.omsc");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("something.txt"));
            zos.write("nope".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThrows(IOException.class, () -> new OMSCParser().parseRaw(file));
    }

    @Test
    @DisplayName("duplicate ids and dangling model references are rejected at construction")
    void referentialIntegrityEnforced() {
        OMSCFormat.ModelRef well = ref("well", "Well.omo", "Well.omo");

        assertThrows(IllegalArgumentException.class, () -> doc(
                List.of(well, ref("well", "Other.omo", "Other.omo")),
                List.of(instance("i1", "A", "well", null))), "duplicate modelId");

        assertThrows(IllegalArgumentException.class, () -> doc(
                List.of(well),
                List.of(instance("i1", "A", "well", null), instance("i1", "B", "well", null))),
                "duplicate instance id");

        assertThrows(IllegalArgumentException.class, () -> doc(
                List.of(well),
                List.of(instance("i1", "A", "ghost", null))), "unknown modelId");
    }

    @Test
    @DisplayName("save fails cleanly when a declared model has no bytes")
    void saveFailsWithoutBytes() {
        boolean saved = new OMSCSerializer().save(
                doc(List.of(ref("well", "Well.omo", "Well.omo")),
                        List.of(instance("i1", "Well", "well", null))),
                Map.of(),
                tempDir.resolve("incomplete.omsc").toString());

        assertFalse(saved);
    }

    @Test
    @DisplayName("saveFromSources reads the bytes off disk verbatim")
    void saveFromSourcesEmbedsFileBytes() throws IOException {
        Path source = tempDir.resolve("Well.omo");
        Files.write(source, CUBE_BYTES);

        Path out = tempDir.resolve("fromsources.omsc");
        assertTrue(new OMSCSerializer().saveFromSources(
                doc(List.of(ref("well", "Well.omo", "Well.omo")),
                        List.of(instance("i1", "Well", "well", null))),
                Map.of("well", source),
                out.toString()));

        OMSCParser.RawParse parsed = new OMSCParser().parseRaw(out);
        assertArrayEquals(CUBE_BYTES, parsed.modelBytesById().values().iterator().next());
    }

    @Test
    @DisplayName("the extension is appended when missing")
    void extensionIsEnsured() {
        assertEquals("scene.omsc", OMSCFormat.ensureExtension("scene"));
        assertEquals("scene.omsc", OMSCFormat.ensureExtension("scene.omsc"));
        assertTrue(OMSCFormat.hasOMSCExtension("a/b/scene.OMSC"));
        assertFalse(OMSCFormat.hasOMSCExtension("scene.omo"));
    }

    /** Counts {@code models/&#42;/model.omo} entries by walking the raw ZIP. */
    private static int countModelEntries(Path omsc) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(omsc))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith(OMSCFormat.MODELS_DIR_PREFIX)
                        && entry.getName().endsWith(OMSCFormat.EMBEDDED_MODEL_FILENAME)) {
                    count++;
                }
                zis.closeEntry();
            }
        }
        return count;
    }
}
