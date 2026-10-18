package com.openmason.main.systems.project;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.api.target.GameTarget;
import com.openmason.main.plugins.builtin.StonebreakGameTarget;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.services.TemplateService;
import com.openmason.main.systems.rendering.model.io.omo.OMODeserializer;
import com.openmason.main.systems.target.TestGameTargets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOFormat;

import com.stonebreak.blocks.BlockType;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The New Biome template writes its starter models headlessly and opens Log.
 */
@Tag("integration")
class TemplateScaffolderTest {

    private static final List<String> BIOME_MODELS = List.of("Log", "Planks", "Stairs", "Leaves", "Sapling", "Grass");

    private static ProjectTemplate template(String id) {
        return switch (id) {
            case "new-biome" -> StonebreakGameTarget.newBiomeTemplate();
            case "blank-template" -> TemplateService.blankTemplate();
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static final GameTarget STONEBREAK = TestGameTargets.stonebreak();

    /** Loads a model and returns its mesh; {@code parts} is the expected part count. */
    private static OMOFormat.MeshData load(Path omo, int parts) {
        OMODeserializer deserializer = new OMODeserializer();
        assertNotNull(deserializer.load(omo.toString()), omo + " must load back");
        List<OMOFormat.PartEntry> entries = deserializer.getLastLoadedPartEntries();
        // A single default-transform part is written partless and synthesised on load.
        assertEquals(parts, entries == null ? 1 : entries.size(), omo + " part count");
        return deserializer.getLastLoadedMeshData();
    }

    @Test
    void newBiomeWritesStarterModelsAndOpensLog(@TempDir Path root) {
        Path open = TemplateScaffolder.scaffold(template("new-biome"), root, STONEBREAK);

        assertEquals(root.resolve("Log.omo"), open);
        for (String name : BIOME_MODELS) {
            load(root.resolve(name + ".omo"), 1);
        }
        // Sapling is the crossed-plane foliage shape, not a cube.
        assertNotEquals(load(root.resolve("Log.omo"), 1).vertices().length,
                load(root.resolve("Sapling.omo"), 1).vertices().length);
    }

    @Test
    void stairsCopyTheGameStairGeometryWithoutItsTexture(@TempDir Path root) throws Exception {
        TemplateScaffolder.scaffold(template("new-biome"), root, STONEBREAK);

        OMOReader.ReadResult game;
        URL folder = BlockType.class.getClassLoader().getResource("sbo/blocks");
        assertNotNull(folder, "the game's block SBOs must be on the tool's classpath");
        try (InputStream sbo = URI.create(folder + "/SB_Oak_Stairs.sbo").toURL().openStream()) {
            ZipInputStream zip = new ZipInputStream(sbo);
            while (!SBOFormat.EMBEDDED_OMO_FILENAME.equals(zip.getNextEntry().getName())) {
                // skip to the embedded model
            }
            game = new OMOReader().read(zip);
        }
        OMOReader.ReadResult stairs;
        try (InputStream in = Files.newInputStream(root.resolve("Stairs.omo"))) {
            stairs = new OMOReader().read(in);
        }

        assertArrayEquals(game.meshData().vertices(), stairs.meshData().vertices());
        assertArrayEquals(game.meshData().indices(), stairs.meshData().indices());
        assertFalse(game.materials().isEmpty(), "sanity: the game stairs are textured");
        assertTrue(stairs.materials().isEmpty(), "the template stairs are blank");
    }

    @Test
    void newBiomeTargetsStonebreak() {
        assertEquals(StonebreakGameTarget.ID, template("new-biome").getTargetId());
        assertNull(template("blank-template").getTargetId());
    }

    @Test
    void withoutItsTargetTheTargetSourcedModelIsSkipped(@TempDir Path root) {
        // The Stairs copy the game's stair mesh; with no target to read it from, only they are missing.
        Path open = TemplateScaffolder.scaffold(template("new-biome"), root, null);

        assertEquals(root.resolve("Log.omo"), open);
        assertFalse(Files.exists(root.resolve("Stairs.omo")));
        assertTrue(Files.exists(root.resolve("Planks.omo")));
    }

    @Test
    void targetSourcedModelsReadFromTheTargetsTree(@TempDir Path root, @TempDir Path tree) throws Exception {
        // A target rooted elsewhere serves its own copy of the source asset (dev tree before class path).
        Path stairs = STONEBREAK.resources().locate().resolve("sbo/blocks/SB_Oak_Stairs.sbo");
        Files.createDirectories(tree.resolve("sbo/blocks"));
        Files.copy(stairs, tree.resolve("sbo/blocks/SB_Oak_Stairs.sbo"));

        TemplateScaffolder.scaffold(template("new-biome"), root, TestGameTargets.stonebreakAt(tree));
        assertTrue(Files.exists(root.resolve("Stairs.omo")));
    }

    @Test
    void existingModelsAreNotOverwritten(@TempDir Path root) throws Exception {
        Path log = root.resolve("Log.omo");
        Files.writeString(log, "user work");

        Path open = TemplateScaffolder.scaffold(template("new-biome"), root, STONEBREAK);

        assertEquals("user work", Files.readString(log));
        assertEquals(log, open);
    }

    @Test
    void blankTemplateWritesNothing(@TempDir Path root) throws Exception {
        assertNull(TemplateScaffolder.scaffold(template("blank-template"), root, null));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }
}
