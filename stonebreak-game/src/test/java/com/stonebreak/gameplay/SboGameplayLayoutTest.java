package com.stonebreak.gameplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped SBOs stay in the pre-1.11 layout on disk (#383, user decision): every one must read
 * the same game data and rendering from that layout as from a copy re-saved in the namespaced
 * {@code stonebreak:gameplay} layout.
 */
@Tag("integration")
class SboGameplayLayoutTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SHIPPED = Path.of("src/main/resources/sbo");

    @TempDir
    Path tmp;

    private static List<Path> shipped() throws IOException {
        try (Stream<Path> files = Files.walk(SHIPPED)) {
            return files.filter(p -> p.toString().endsWith(".sbo")).sorted().toList();
        }
    }

    private static JsonNode manifestJson(Path sbo) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(sbo))) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                if (e.getName().equals(SBOFormat.MANIFEST_FILENAME)) {
                    return JSON.readTree(new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        throw new AssertionError("no manifest in " + sbo);
    }

    @Test
    void shippedAssetsAreStillInTheLegacyLayout() throws IOException {
        int withGameData = 0;
        for (Path sbo : shipped()) {
            JsonNode root = manifestJson(sbo);
            assertFalse(root.has("extensions"), sbo + " was re-saved in the new layout");
            if (root.hasNonNull("gameProperties")) withGameData++;
        }
        assertTrue(withGameData > 50, "most shipped SBOs carry legacy gameProperties: " + withGameData);
    }

    @Test
    void legacyAndNamespacedLayoutsReadTheSame() throws IOException {
        SBOParser parser = new SBOParser();
        SBOSerializer serializer = new SBOSerializer();
        int checked = 0;
        for (Path sbo : shipped()) {
            SBOParser.RawParse raw = parser.parseRaw(sbo);
            SBOFormat.Document legacy = raw.manifest();
            SboGameplay data = SboGameplay.of(legacy);

            Path out = tmp.resolve(sbo.getFileName());
            assertTrue(serializer.exportFromDocument(data.applyTo(legacy), raw.defaultBytes(), raw.stateBytes(),
                    raw.stateClipBytes(), raw.soundBytes(), out.toString()), sbo.toString());

            JsonNode root = manifestJson(out);
            for (String field : SboGameplayCodec.LEGACY_FIELDS) {
                assertFalse(root.has(field), sbo + ": legacy " + field + " left behind");
            }
            assertEquals(!data.isEmpty(), root.path("extensions").has(SboGameplayCodec.KEY), sbo.toString());
            assertFalse(root.path("extensions").path(SboGameplayCodec.KEY).path("gameProperties").has("renderLayer"),
                    sbo + ": render layer is core data now");

            SBOFormat.Document upgraded = parser.parseRaw(out).manifest();
            assertEquals(data, SboGameplay.of(upgraded), "game data of " + sbo);
            assertEquals(legacy.rendering(), upgraded.rendering(), "rendering of " + sbo);
            checked++;
        }
        assertTrue(checked > 50, "checked " + checked);
    }
}
