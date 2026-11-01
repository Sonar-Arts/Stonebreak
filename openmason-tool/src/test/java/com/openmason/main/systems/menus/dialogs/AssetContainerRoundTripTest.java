package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.main.systems.target.TestGameTargets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without a game plugin the SBO/SBE editors are generic container editors (#431): they show no game
 * data, yet every shipped asset survives open → save → reopen with its game data intact.
 */
@Tag("integration")
class AssetContainerRoundTripTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void noPlugin() {
        AssetSectionSet.setSource(null);
    }

    static List<Path> shipped(String folder, String ext) throws IOException {
        Path tree = TestGameTargets.checkoutTree();
        assertNotNull(tree, "the game checkout's resource tree");
        try (Stream<Path> files = Files.walk(tree.resolve(folder))) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(ext)).sorted().toList();
        }
    }

    /** The manifest minus what a save rewrites (format version, checksum). */
    static SBOFormat.Document comparable(SBOFormat.Document d) {
        return new SBOFormat.Document("-", d.objectId(), d.objectName(), d.objectType(), d.objectPack(),
                "-", d.author(), d.description(), d.createdAt(), d.omoFilename(), d.textureFilename(),
                d.rendering(), d.states(), d.defaultStateName(), d.sounds(), d.extensions());
    }

    /**
     * Identity, rendering and all the game data the editor cannot see (extensions + the legacy
     * top-level fields of older files). The snapshot leaves embedded-file checksums to the serializer.
     */
    static List<Object> gameData(SBOFormat.Document d) {
        return java.util.Arrays.asList(d.objectId(), d.objectType(), d.rendering(), d.extensions());
    }

    static SBEFormat.Document comparable(SBEFormat.Document d) {
        return new SBEFormat.Document("-", d.objectId(), d.objectName(), d.entityType(), d.objectPack(),
                "-", d.author(), d.description(), d.createdAt(), d.omoFilename(), d.states(), d.variants(),
                d.sounds(), d.extensions());
    }

    @Test
    void everyShippedSboKeepsItsGameData() throws IOException {
        List<Path> files = shipped("sbo", ".sbo");
        assertFalse(files.isEmpty());
        SBOParser parser = new SBOParser();
        int withGameData = 0;
        for (Path file : files) {
            SBOFormat.Document original = parser.parseRaw(file).manifest();
            SBOEditorWindow editor = new SBOEditorWindow(null, null);
            assertTrue(editor.openFile(file.toString()), file.toString());
            assertEquals(gameData(original), gameData(editor.snapshotDocument()), "snapshot of " + file);
            assertEquals(null, editor.validateForWrite(), file.toString());

            Path out = tmp.resolve(file.getFileName());
            assertTrue(editor.writeDocumentTo(out.toString()), file.toString());
            assertEquals(comparable(original), comparable(parser.parseRaw(out).manifest()), "re-saved " + file);
            if (!original.extensions().isEmpty()) {
                withGameData++;
            }
        }
        assertTrue(withGameData > 0, "the shipped assets carry game data to preserve");
    }

    @Test
    void everyShippedSbeKeepsItsEntityType() throws IOException {
        List<Path> files = shipped("sbe", ".sbe");
        assertFalse(files.isEmpty());
        SBEParser parser = new SBEParser();
        for (Path file : files) {
            SBEFormat.Document original = parser.parseRaw(file).manifest();
            SBEEditorWindow editor = new SBEEditorWindow(null, null);
            assertTrue(editor.openFile(file.toString()), file.toString());
            // (the editor's snapshot leaves embedded-asset checksums to the serializer)
            assertEquals(original.entityType(), editor.snapshotDocument().entityType(), "snapshot of " + file);

            Path out = tmp.resolve(file.getFileName());
            assertTrue(editor.writeDocumentTo(out.toString()), file.toString());
            assertEquals(comparable(original), comparable(parser.parseRaw(out).manifest()), "re-saved " + file);
        }
    }
}
