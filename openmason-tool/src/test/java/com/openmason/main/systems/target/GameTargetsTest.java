package com.openmason.main.systems.target;

import com.openmason.api.target.AssetFolders;
import com.openmason.api.target.GameTarget;
import com.openmason.api.target.ResourceRoot;
import com.openmason.api.target.UiDeployLayout;
import com.openmason.main.plugins.builtin.StonebreakGameTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game target model (#380): which target is active, how its folders route exports, how shipped
 * file names and ids are formed, and where its resource tree is found.
 */
class GameTargetsTest {

    private static GameTarget target(String id, Path tree) {
        return GameTarget.builder(id, id.toUpperCase()).resources(ResourceRoot.at(tree)).build();
    }

    // ------------------------------------------------------------------ selection

    @Test
    void withoutAProjectTheFirstRegisteredTargetIsActive(@TempDir Path tmp) {
        GameTargets targets = new GameTargets(() -> List.of(target("a", tmp), target("b", tmp)));
        assertEquals("a", targets.active().orElseThrow().id());

        assertTrue(new GameTargets(List::of).active().isEmpty(), "nothing registered, nothing active");
    }

    @Test
    void aProjectsTargetWinsAndNoneMeansNone(@TempDir Path tmp) {
        GameTargets targets = new GameTargets(() -> List.of(target("a", tmp), target("b", tmp)));
        AtomicReference<GameTarget> heard = new AtomicReference<>();
        targets.addListener(heard::set);

        targets.projectOpened("b");
        assertEquals("b", targets.active().orElseThrow().id());
        assertEquals("b", heard.get().id());

        targets.projectOpened(null);
        assertTrue(targets.active().isEmpty(), "a project-only project has no target");
        assertNull(heard.get());
        assertEquals(GameTargets.FALLBACK_NAMESPACE, targets.namespace());
        assertNull(targets.resourcesRoot());

        targets.projectClosed();
        assertEquals("a", targets.active().orElseThrow().id());
    }

    @Test
    void anUninstalledTargetIsReportedMissing(@TempDir Path tmp) {
        GameTargets targets = new GameTargets(() -> List.of(target("a", tmp)));
        targets.projectOpened("gone");
        assertTrue(targets.isProjectTargetMissing());
        assertTrue(targets.active().isEmpty());
        assertEquals("gone", targets.projectTargetId());
    }

    @Test
    void theLegacyTargetIsTheOneThatClaimsIt(@TempDir Path tmp) {
        GameTarget legacy = GameTarget.builder("old", "Old").resources(ResourceRoot.at(tmp)).legacyDefault(true).build();
        assertEquals("old", new GameTargets(() -> List.of(target("a", tmp), legacy)).legacyTargetId());
        assertNull(new GameTargets(() -> List.of(target("a", tmp))).legacyTargetId());
    }

    @Test
    void idsMustBeLowercaseWithoutColons(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> target("My Game", tmp));
        assertThrows(IllegalArgumentException.class, () -> target("ns:x", tmp));
    }

    // ------------------------------------------------------------------ naming

    @Test
    void suggestedFileNameFollowsTheTargetsConvention() {
        assertEquals("SB_Oak_Planks.sbo", GameTargets.suggestedFileName("SB_", "Oak Planks", "sbo", "object.sbo"));
        assertEquals("SB_Oak_Planks.sbo", GameTargets.suggestedFileName("SB_", "oak_planks", "sbo", "object.sbo"));
        assertEquals("SB_Tophat.sbe", GameTargets.suggestedFileName("SB_", "tophat", "sbe", "entity.sbe"));
        assertEquals("SB_Red_Sand_Stone.sbo", GameTargets.suggestedFileName("SB_", "  red  sand-stone! ", "sbo", "x.sbo"));
        assertEquals("Oak_Planks.sbo", GameTargets.suggestedFileName("", "oak planks", "sbo", "object.sbo"),
                "no prefix without a target");
    }

    @Test
    void suggestedFileNameFallsBackWhenBlank() {
        assertEquals("object.sbo", GameTargets.suggestedFileName("SB_", "", "sbo", "object.sbo"));
        assertEquals("object.sbo", GameTargets.suggestedFileName("SB_", "!!!", "sbo", "object.sbo"));
        assertEquals("object.sbo", GameTargets.suggestedFileName("SB_", null, "sbo", "object.sbo"));
        assertEquals("object.sbo", GameTargets.suggestedFileName("", "!!!", "sbo", "object.sbo"));
    }

    @Test
    void idsUseTheActiveNamespace(@TempDir Path tmp) {
        GameTarget g = GameTarget.builder("mygame", "My Game").namespace("mg").resources(ResourceRoot.at(tmp)).build();
        GameTargets targets = new GameTargets(() -> List.of(g));
        assertEquals("mg:cow", targets.qualify("cow"));
        assertTrue(targets.ownsId("mg:screen.pause"));
        assertFalse(targets.ownsId("other:screen.pause"));
        targets.projectOpened(null);
        assertEquals("custom:cow", targets.qualify("cow"));
        assertFalse(targets.ownsId("mg:screen.pause"));
    }

    // ------------------------------------------------------------------ folders

    @Test
    void foldersRouteBySubtypeAndFallBackToTheBase() {
        AssetFolders f = AssetFolders.builder()
                .base("sbo", "assets").subtype("sbo", "block", "assets/blocks")
                .base("sbe", "ents").subtype("sbe", "mob", "ents/mobs").build();
        assertEquals("assets/blocks", f.folderFor("sbo", "Block"));
        assertEquals("assets", f.folderFor("sbo", "decoration"));
        assertEquals("assets", f.folderFor("sbo", null));
        assertNull(f.folderFor("png", "x"));
        assertEquals(List.of("assets", "assets/blocks"), f.foldersOf("sbo"));
        assertEquals("mob", f.subtypeOf("sbe", "ents/mobs"));
        assertNull(f.subtypeOf("sbe", "ents/mobs/combat"));
    }

    @Test
    void stonebreakFoldersRouteOnTheRealTree() {
        // Surefire runs with cwd = openmason-tool/; the resources live in a sibling module.
        GameTargets targets = new GameTargets(() -> List.of(StonebreakGameTarget.create()));
        Path root = targets.resourcesRoot();
        assertNotNull(root, "the Stonebreak tree should be discoverable from the tool module");
        assertTrue(Files.isDirectory(root.resolve("sbo/blocks")));

        assertEquals(root.resolve("sbo/blocks"), targets.folderFor("sbo", "Block"));
        assertEquals(root.resolve("sbo/items"), targets.folderFor("sbo", "item"));
        assertTrue(targets.folderFor("sbo", "Decoration").startsWith(root.resolve("sbo")));
        assertEquals(root.resolve("sbe/Mobs"), targets.folderFor("sbe", "Mob"));
        assertEquals(root.resolve("sbe"), targets.folderFor("sbe", "NPC"));

        assertTrue(targets.isInsideResources(root.resolve("sbo/items").toString()));
        assertFalse(targets.isInsideResources(System.getProperty("java.io.tmpdir")));
        assertEquals("SB_Oak_Door.sbo", targets.suggestedFileName("oak door", "sbo", "object.sbo"));
    }

    // ------------------------------------------------------------------ resources

    @Test
    void theTreeIsFoundFromAnyDescendant(@TempDir Path tmp) throws Exception {
        Path tree = Files.createDirectories(tmp.resolve("game/src/main/resources"));
        Path deep = Files.createDirectories(tmp.resolve("tool/run/dir"));
        ResourceRoot root = new ResourceRoot(List.of("game/src/main/resources"), null, null);
        assertEquals(tree.toAbsolutePath().normalize(), root.locateFrom(deep));
        assertEquals(tree.toAbsolutePath().normalize(), root.locateFrom(tree), "from inside the tree too");
        assertNull(new ResourceRoot(List.of("nope"), null, null).locateFrom(deep));
    }

    @Test
    void theOverridePropertyWins(@TempDir Path tmp) {
        String key = "openmason.test.target.resources";
        ResourceRoot root = new ResourceRoot(List.of("does/not/exist"), key, null);
        System.setProperty(key, tmp.toString());
        try {
            assertEquals(tmp.toAbsolutePath().normalize(), root.locate());
        } finally {
            System.clearProperty(key);
        }
        assertNull(root.locate());
    }

    @Test
    void openReadsTheTreeThenTheClassPath(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("sbo/blocks"));
        Files.write(tmp.resolve("sbo/blocks/X.sbo"), new byte[]{1, 2, 3});
        try (InputStream in = ResourceRoot.at(tmp).open("sbo/blocks/X.sbo")) {
            assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes());
        }
        assertThrows(java.io.IOException.class, () -> ResourceRoot.at(tmp).open("sbo/blocks/Missing.sbo"));

        // No tree: the game's class path (the tool's loader sees the game module's resources).
        ResourceRoot packaged = new ResourceRoot(List.of("does/not/exist"), null,
                StonebreakGameTarget.class.getClassLoader());
        try (InputStream in = packaged.open("sbo/blocks/SB_Oak_Stairs.sbo")) {
            assertTrue(in.readAllBytes().length > 0);
        }
    }

    @Test
    void uiDeployLayoutPaths() {
        UiDeployLayout layout = new UiDeployLayout("ui/documents/", "ui\\shared");
        assertEquals("ui/documents/pause.sbui", layout.screenPath("pause"));
        assertEquals("ui/shared/mg/ui/textures/panel.png", layout.sharedPath("mg/ui/textures/panel.png"));
    }

    @Test
    void stonebreakIsTheLegacyTargetAndShipsUi() {
        GameTarget sb = StonebreakGameTarget.create();
        assertTrue(sb.legacyDefault());
        assertEquals("stonebreak", sb.defaultNamespace());
        assertNotNull(sb.uiDeploy());
        assertNotNull(sb.uiHost());
        assertEquals("SB_", sb.assetFilePrefix());
        assertTrue(sb.writes().writable() && sb.writes().confirmWrites());
    }
}
