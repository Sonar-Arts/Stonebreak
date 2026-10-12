package com.openmason.engine.format.uiarchive;

import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-directory pack/unpack and the headless command line. */
@Tag("regression")
class UiPackerTest {

    @TempDir
    Path tmp;

    @Test
    void unpackThenPackIsByteIdentical() throws Exception {
        byte[] omui = OmuiWriter.write(UiSamples.pauseMenu());
        Path dir = tmp.resolve("pause_menu");
        UiPacker.unpack(omui, dir, false);
        assertEquals(List.of("animations/open.anim.json", "assets/components/stone_button.omui", "dependencies.json",
                "document.json", "editor/fixtures.json", "editor/workspace.json", "graphs/behaviors.graph.json",
                "manifest.json", "scripts/pause.lua", "styles/pause.uss.json"), files(dir));
        assertArrayEquals(omui, UiPacker.pack(dir));
    }

    @Test
    void packNormalizesHandEditsAndIgnoresDotFiles() throws Exception {
        Path dir = tmp.resolve("button");
        UiPacker.unpack(OmuiWriter.write(UiSamples.stoneButton()), dir, false);
        Path manifest = dir.resolve("manifest.json");
        String compact = Files.readString(manifest).replaceAll("\\s+", " ");
        Files.writeString(manifest, compact);
        Files.writeString(dir.resolve(".DS_Store"), "junk");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/HEAD"), "ref");
        assertArrayEquals(OmuiWriter.write(UiSamples.stoneButton()), UiPacker.pack(dir));
    }

    @Test
    void packValidatesLikeAnyArchive() throws Exception {
        Path dir = tmp.resolve("broken");
        UiPacker.unpack(OmuiWriter.write(UiSamples.pauseMenu()), dir, false);
        Files.delete(dir.resolve("styles/pause.uss.json"));
        assertThrows(UiFormatException.class, () -> UiPacker.pack(dir));
    }

    @Test
    void packRefusesSymlinks() throws Exception {
        Path dir = tmp.resolve("linked");
        UiPacker.unpack(OmuiWriter.write(UiSamples.stoneButton()), dir, false);
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(dir.resolve("assets.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlinks
        }
        assertThrows(UiFormatException.class, () -> UiPacker.pack(dir));
    }

    @Test
    void unpackNeverClobbersWithoutReplace() throws Exception {
        Path dir = tmp.resolve("busy");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("keep.txt"), "mine");
        byte[] omui = OmuiWriter.write(UiSamples.stoneButton());
        assertThrows(IOException.class, () -> UiPacker.unpack(omui, dir, false));
        assertEquals("mine", Files.readString(dir.resolve("keep.txt")));

        UiPacker.unpack(omui, dir, true);
        assertFalse(Files.exists(dir.resolve("keep.txt")));
        assertArrayEquals(omui, UiPacker.pack(dir));
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(List.of("busy"), s.map(p -> p.getFileName().toString()).toList(), "no staging leftovers");
        }
    }

    @Test
    void unpackOfAnInvalidArchiveWritesNothing() {
        Path dir = tmp.resolve("never");
        assertThrows(UiFormatException.class, () -> UiPacker.unpack(UiSamples.draftArchive(), dir, false));
        assertFalse(Files.exists(dir));
    }

    @Test
    void sbuiUnpacksAndPacksToo() throws Exception {
        byte[] sbui = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive());
        Path dir = tmp.resolve("sbui");
        UiPacker.unpack(sbui, dir, false);
        assertEquals(List.of("manifest.json", "source/pause_menu.omui"), files(dir));
        assertArrayEquals(sbui, UiPacker.pack(dir));
    }

    @Test
    void commandLineCoversTheWorkflow() throws Exception {
        Path omui = tmp.resolve("pause.omui");
        Files.write(omui, OmuiWriter.write(UiSamples.pauseMenu()));
        assertEquals(0, cli("validate", omui.toString()));
        assertEquals(0, cli("unpack", omui.toString(), tmp.resolve("src").toString()));
        assertEquals(0, cli("pack", tmp.resolve("src").toString(), tmp.resolve("repacked.omui").toString()));
        assertArrayEquals(Files.readAllBytes(omui), Files.readAllBytes(tmp.resolve("repacked.omui")));

        Path sbui = tmp.resolve("pause.sbui");
        assertEquals(0, cli("export", omui.toString(), sbui.toString(), "--asset-id", "stonebreak:ui/pause"));
        assertEquals("stonebreak:ui/pause", SbuiReader.read(sbui, SbuiReader.Options.RUNTIME).archive().manifest().assetId());
        assertEquals(0, cli("validate", sbui.toString()));
        assertEquals(0, cli("import", sbui.toString(), tmp.resolve("imported").toString()));
        assertTrue(Files.exists(tmp.resolve("imported.omui")));
        OmuiReader.read(tmp.resolve("imported.omui"));

        Path draft = tmp.resolve("draft.omui");
        Files.write(draft, UiSamples.draftArchive());
        assertEquals(1, cli("validate", draft.toString()), "drafts need an explicit upgrade");
        assertEquals(0, cli("upgrade", draft.toString()));
        assertTrue(Files.exists(tmp.resolve("draft.omui.v0.1.bak")));
        assertEquals(0, cli("validate", draft.toString()));

        assertEquals(2, cli());
        assertEquals(2, cli("frobnicate"));
        assertEquals(1, cli("validate", tmp.resolve("missing.omui").toString()));
    }

    private static int cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return UiArchiveTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static List<String> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }
}
