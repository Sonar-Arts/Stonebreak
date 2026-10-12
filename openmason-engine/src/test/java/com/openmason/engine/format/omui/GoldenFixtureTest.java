package com.openmason.engine.format.omui;

import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedKind;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.format.uiarchive.UiPacker;
import com.openmason.engine.ui.graph.GraphCompiler;
import com.openmason.engine.ui.graph.GraphDerived;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the committed golden archives in {@code src/test/resources/ui/omui/} — the
 * language-neutral conformance data for a future C++ reader/writer (wire contract:
 * {@code openmason-engine/docs/ui-program/omui-sbui-wire-contract.md}). Archives are byte-compared: entries
 * are stored uncompressed, so the bytes do not depend on the platform zlib. Regenerate after
 * an intentional format change with {@code -Dui.fixtures.write=true}, then review the
 * unpacked {@code pause_menu/} diff.
 */
@Tag("regression")
class GoldenFixtureTest {

    static final Path DIR = Path.of("src/test/resources/ui/omui");
    static final boolean WRITE = Boolean.getBoolean("ui.fixtures.write");

    static Map<String, byte[]> expected() throws Exception {
        Map<String, byte[]> golden = new LinkedHashMap<>();
        golden.put("stone_button.omui", OmuiWriter.write(UiSamples.stoneButton()));
        golden.put("pause_menu.omui", OmuiWriter.write(UiSamples.pauseMenu()));
        // The graph's Lua as the #291 compiler emits it, against the component and shared module.
        var source = UiDocumentSource.of(Map.of(UiSamples.BUTTON_ID, UiSamples.stoneButton()), Map.of(),
                Map.of(UiSamples.COMMON_LUA_ID, new String(UiSamples.COMMON_LUA_BYTES, StandardCharsets.UTF_8)));
        var options = new SbuiExporter.Options(null, Map.of(), false, Map.of(),
                GraphDerived.compileAll(UiSamples.pauseMenu(), source));
        golden.put("pause_menu.sbui", SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), options).archive()));
        golden.put("pause_draft_v0_1.omui", UiSamples.draftArchive());
        golden.put("pause_draft_v0_1.upgraded.omui",
                OmuiWriter.write(OmuiUpgrader.upgrade(UiSamples.draftArchive()).archive()));
        return golden;
    }

    @Test
    void goldenArchivesMatchTheSamples() throws Exception {
        Map<String, byte[]> golden = expected();
        if (WRITE) {
            Files.createDirectories(DIR);
            for (var e : golden.entrySet()) {
                Files.write(DIR.resolve(e.getKey()), e.getValue());
            }
            UiPacker.unpack(golden.get("pause_menu.omui"), DIR.resolve("pause_menu"), true);
        }
        for (var e : golden.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(DIR.resolve(e.getKey())),
                    e.getKey() + " drifted; if intentional rerun with -Dui.fixtures.write=true");
        }
    }

    @Test
    void goldenArchivesAreFixedPoints() throws Exception {
        for (String name : List.of("stone_button.omui", "pause_menu.omui", "pause_draft_v0_1.upgraded.omui")) {
            byte[] bytes = read(name);
            assertArrayEquals(bytes, OmuiWriter.write(OmuiReader.read(bytes).archive()), name);
        }
        byte[] sbui = read("pause_menu.sbui");
        var result = SbuiReader.read(sbui, new SbuiReader.Options(SbuiReader.StalePolicy.REJECT,
                Map.of(DerivedKind.GRAPH_LUA, GraphCompiler.VERSION), ArchiveLimits.DEFAULT));
        assertTrue(result.staleDerived().isEmpty());
        assertArrayEquals(sbui, SbuiWriter.write(result.archive()));
    }

    @Test
    void reviewDirectoryPacksToTheGoldenArchive() throws Exception {
        assertArrayEquals(read("pause_menu.omui"), UiPacker.pack(DIR.resolve("pause_menu")));
    }

    @Test
    void draftGoldenUpgradesToItsGoldenTarget() throws Exception {
        OmuiUpgrader.Upgrade up = OmuiUpgrader.upgrade(read("pause_draft_v0_1.omui"));
        assertEquals(List.of("0.1->1.0"), up.stages());
        assertArrayEquals(read("pause_draft_v0_1.upgraded.omui"), OmuiWriter.write(up.archive()));
    }

    private static byte[] read(String name) throws IOException {
        return Files.readAllBytes(DIR.resolve(name));
    }
}
