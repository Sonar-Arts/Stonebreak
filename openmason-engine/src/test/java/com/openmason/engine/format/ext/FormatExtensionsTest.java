package com.openmason.engine.format.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Namespaced manifest extensions (#383): the engine keeps game data it does not understand, and a
 * game reads and moves it through its codec.
 */
@Tag("regression")
class FormatExtensionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An extension of somebody else's, formatted oddly on purpose (spacing, a long decimal, key order). */
    private static final String FOREIGN_EXTENSION =
            "{ \"zeta\":1.50,\n      \"alpha\" : [ 3,2 ,1 ], \"pi\": 3.141592653589793238462643383279 }";
    /** A top-level field an older file wrote that this engine version does not own. */
    private static final String FOREIGN_LEGACY_FIELD = "{\"keep\" :   true,\"n\":0.1000000000000000055511151231257827}";

    @TempDir
    Path dir;

    // ── a toy game codec: "acme:stats" {"speed": n}, legacy top-level "speed" ──

    record Stats(int speed) {
    }

    static final FormatExtensionCodec<Stats> STATS = new FormatExtensionCodec<>() {
        @Override
        public String key() {
            return "acme:stats";
        }

        @Override
        public Stats decode(JsonNode value) {
            if (!value.has("speed")) throw new IllegalArgumentException("no speed");
            return new Stats(value.get("speed").asInt());
        }

        @Override
        public JsonNode encode(Stats value) {
            return JSON.createObjectNode().put("speed", value.speed());
        }

        @Override
        public Set<String> legacyFields() {
            return Set.of("speed");
        }
    };

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path exportSbo(String name) throws IOException {
        Path omo = dir.resolve(name + ".omo");
        Files.write(omo, "fake-omo".getBytes(StandardCharsets.UTF_8));
        SBOFormat.ExportParameters p = new SBOFormat.ExportParameters();
        p.setObjectId("test:" + name);
        p.setObjectName(name);
        p.setAuthor("test");
        Path out = dir.resolve(name + ".sbo");
        assertTrue(new SBOSerializer().export(p, omo, out.toString()));
        return out;
    }

    private static String manifest(Path archive) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                if (e.getName().equals("manifest.json")) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no manifest in " + archive);
    }

    /** Rewrites the archive's manifest text (other entries copied as they are). */
    private static void editManifest(Path archive, UnaryOperator<String> edit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive));
             ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                byte[] data = zis.readAllBytes();
                if (e.getName().equals("manifest.json")) {
                    data = edit.apply(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                }
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(data);
                zos.closeEntry();
            }
        }
        Files.write(archive, bytes.toByteArray());
    }

    /** Adds members before the manifest's closing brace. */
    private static UnaryOperator<String> addMembers(String members) {
        return m -> {
            int end = m.lastIndexOf('}');
            return m.substring(0, end).stripTrailing() + ",\n" + members + "\n}";
        };
    }

    private Path resave(Path sbo, UnaryOperator<SBOFormat.Document> change) throws IOException {
        SBOParser.RawParse raw = new SBOParser().parseRaw(sbo);
        Path out = dir.resolve("resaved-" + sbo.getFileName());
        assertTrue(new SBOSerializer().exportFromDocument(change.apply(raw.manifest()), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), raw.soundBytes(), out.toString()));
        return out;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void anUnknownExtensionAndAForeignFieldSurviveAResaveByteForByte() throws IOException {
        Path sbo = exportSbo("crate");
        editManifest(sbo, addMembers("  \"oldGameField\" : " + FOREIGN_LEGACY_FIELD
                + ",\n  \"extensions\" : {\"other:thing\" : " + FOREIGN_EXTENSION + "}"));

        SBOFormat.Document doc = new SBOParser().parseRaw(sbo).manifest();
        assertEquals(Set.of("other:thing"), doc.extensions().keys());
        assertEquals(Set.of("oldGameField"), doc.extensions().legacyFieldNames());

        // An edit by someone who knows neither (a core field change) keeps both exactly as written.
        String saved = manifest(resave(sbo, d -> d.withRendering(new SBOFormat.Rendering("CUTOUT", true))));
        assertTrue(saved.contains(FOREIGN_EXTENSION), saved);
        assertTrue(saved.contains(FOREIGN_LEGACY_FIELD), saved);

        // ...and a game writing its own extension next to it does too.
        String twice = manifest(resave(resave(sbo, d -> d.withExtension(STATS, new Stats(4))), d -> d));
        assertTrue(twice.contains(FOREIGN_EXTENSION), twice);
        assertTrue(twice.contains(FOREIGN_LEGACY_FIELD), twice);
        assertEquals(new Stats(4), new SBOParser().parseRaw(resave(sbo, d -> d.withExtension(STATS, new Stats(4))))
                .manifest().extension(STATS).orElseThrow());
    }

    @Test
    void aCodecReadsTheLegacyLayoutAndMovesItIntoTheExtensionOnWrite() throws IOException {
        Path sbo = exportSbo("cart");
        editManifest(sbo, addMembers("  \"speed\" : 7"));

        SBOFormat.Document old = new SBOParser().parseRaw(sbo).manifest();
        assertEquals(new Stats(7), old.extension(STATS).orElseThrow(), "old layout reads through the codec");
        assertFalse(old.extensions().has("acme:stats"));

        // Saved by something that does not know the codec: the legacy field stays where it was.
        String untouched = manifest(resave(sbo, d -> d));
        assertTrue(JSON.readTree(untouched).has("speed"), untouched);
        assertFalse(JSON.readTree(untouched).has("extensions"), untouched);

        // Saved through the codec: the data moves into the namespaced extension.
        Path moved = resave(sbo, d -> d.withExtension(STATS, d.extension(STATS).orElseThrow()));
        JsonNode root = JSON.readTree(manifest(moved));
        assertFalse(root.has("speed"), "the legacy field is gone");
        assertEquals(7, root.path("extensions").path("acme:stats").path("speed").asInt());
        assertEquals(new Stats(7), new SBOParser().parseRaw(moved).manifest().extension(STATS).orElseThrow(),
                "the new layout reads the same");

        // Removing it removes both layouts.
        SBOFormat.Document cleared = old.withExtension(STATS, null);
        assertTrue(cleared.extension(STATS).isEmpty());
        assertTrue(cleared.extensions().legacyFieldNames().isEmpty());
    }

    @Test
    void anUnreadableExtensionReadsAsAbsent() {
        FormatExtensions ext = FormatExtensions.ofRaw(Map.of("acme:stats", "{\"nope\":1}"), Map.of());
        assertTrue(ext.get(STATS).isEmpty());
        assertTrue(ext.has("acme:stats"), "but it is still kept");
    }

    @Test
    void renderingIsCoreDataAndOlderFilesSupplyItFromGameProperties() throws IOException {
        Path sbo = exportSbo("glass");
        // An SBO 1.10 file: render layer inside the game's gameProperties block, no top-level rendering.
        editManifest(sbo, m -> {
            try {
                ObjectNode root = (ObjectNode) JSON.readTree(m);
                root.remove("rendering");
                root.putObject("gameProperties").put("numericId", 9).put("renderLayer", "translucent")
                        .put("transparent", true);
                return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        SBOFormat.Document old = new SBOParser().parseRaw(sbo).manifest();
        assertEquals(new SBOFormat.Rendering("TRANSLUCENT", true), old.rendering());
        assertTrue(old.extensions().legacyFieldNames().contains("gameProperties"), "kept for the game");

        // A core edit writes the top-level object, which wins over the stale legacy copy on the next read.
        Path edited = resave(sbo, d -> d.withRendering(new SBOFormat.Rendering("CUTOUT", false)));
        SBOFormat.Document reread = new SBOParser().parseRaw(edited).manifest();
        assertEquals(new SBOFormat.Rendering("CUTOUT", false), reread.rendering());
        assertEquals(9, reread.extensions().legacyField("gameProperties").orElseThrow().path("numericId").asInt());
    }

    @Test
    void sbeKeepsExtensionsAndForeignFieldsToo() throws IOException {
        Path omo = dir.resolve("cow.omo");
        Files.write(omo, "fake-omo".getBytes(StandardCharsets.UTF_8));
        SBEFormat.ExportParameters p = new SBEFormat.ExportParameters();
        p.setObjectId("test:cow");
        p.setObjectName("Cow");
        p.setAuthor("test");
        Path sbe = dir.resolve("cow.sbe");
        assertTrue(new SBESerializer().export(p, omo, sbe.toString()));
        assertEquals(SBEFormat.DEFAULT_ENTITY_TYPE, new SBEParser().parseRaw(sbe).manifest().entityType());

        editManifest(sbe, addMembers("  \"oldGameField\" : " + FOREIGN_LEGACY_FIELD
                + ",\n  \"extensions\" : {\"other:thing\" : " + FOREIGN_EXTENSION + "}"));
        SBEParser.RawParse raw = new SBEParser().parseRaw(sbe);
        Path out = dir.resolve("cow2.sbe");
        assertTrue(new SBESerializer().exportFromDocument(raw.manifest().withEntityType("vehicle"),
                raw.omoBytes(), raw.stateAssetBytes(), out.toString()));
        String saved = manifest(out);
        assertTrue(saved.contains(FOREIGN_EXTENSION), saved);
        assertTrue(saved.contains(FOREIGN_LEGACY_FIELD), saved);
        assertEquals("vehicle", new SBEParser().parseRaw(out).manifest().entityType());
    }

    @Test
    void equalityComparesValuesNotFormattingAndKeysAreNamespaced() {
        FormatExtensions a = FormatExtensions.ofRaw(Map.of("acme:stats", "{ \"speed\" : 1 }"), Map.of());
        FormatExtensions b = FormatExtensions.EMPTY.with(STATS, new Stats(1));
        assertEquals(a, b);
        assertNotEquals(a, FormatExtensions.EMPTY.with(STATS, new Stats(2)));
        assertThrows(IllegalArgumentException.class, () -> FormatExtensions.EMPTY.withTree("nonamespace",
                JSON.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> FormatExtensions.EMPTY.withTree("Acme:Stats",
                JSON.createObjectNode()));
    }

    @Test
    void rawMembersCaptureEveryValueKind() {
        Map<String, String> m = ManifestJson.rawMembers(
                "{\"s\": \"a \\\"q\\\" b\", \"n\" : -1.5e3, \"t\":true, \"z\" :null, \"o\":{\"x\":[1,{}]}, \"a\":[ ]}");
        assertEquals("\"a \\\"q\\\" b\"", m.get("s"));
        assertEquals("-1.5e3", m.get("n"));
        assertEquals("true", m.get("t"));
        assertEquals("null", m.get("z"));
        assertEquals("{\"x\":[1,{}]}", m.get("o"));
        assertEquals("[ ]", m.get("a"));
    }
}
