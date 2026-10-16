package com.openmason.main.systems.io;

import com.openmason.main.systems.target.TestGameTargets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("regression")
class WriteSandboxTest {

    @TempDir
    Path tmp;

    private Path project;
    private Path game;
    private Path exports;
    private WriteSandbox sandbox;

    @BeforeEach
    void roots() throws IOException {
        project = Files.createDirectories(tmp.resolve("proj"));
        game = Files.createDirectories(tmp.resolve("game/src/main/resources"));
        Files.createDirectories(game.resolve("sbo/blocks"));
        exports = Files.createDirectories(tmp.resolve("exports"));
        sandbox = new WriteSandbox(WriteRoots.forTarget(project, TestGameTargets.stonebreakAt(game), exports));
    }

    @Test
    void bareNameLandsInKindDefaultRootAndSubdir() {
        WriteTarget omo = sandbox.resolve(WriteKind.OMO, "Cow");
        assertEquals(real(project), omo.path().getParent());
        assertEquals("Cow.omo", omo.fileName());
        assertEquals(WriteRoot.PROJECT, omo.root());
        assertFalse(omo.exists());

        WriteTarget sbo = sandbox.resolve(WriteKind.SBO, "SB_Cow");
        assertEquals(real(game).resolve("sbo/blocks"), sbo.path().getParent());
        assertTrue(sbo.insideTarget());
        assertTrue(sbo.asksFirst(), "shipped assets ask under the default policy");
    }

    @Test
    void bareRelativeWithFolderSkipsDefaultSubdir() {
        WriteTarget sbo = sandbox.resolve(WriteKind.SBO, "sbo/items/SB_Stick.sbo");
        assertEquals(real(game).resolve("sbo/items/SB_Stick.sbo"), sbo.path());
    }

    @Test
    void prefixedPathsResolveAgainstNamedRoot() {
        assertEquals(real(project).resolve("models/x.omt"),
                sandbox.resolve(WriteKind.OMT, "project:models/x.omt").path());
        assertEquals(real(exports).resolve("shot.png"),
                sandbox.resolve(WriteKind.PNG, "exports:/shot").path());
        assertEquals(real(game).resolve("sbe/Mobs/SB_Goose.sbe"),
                sandbox.resolve(WriteKind.SBE, "GAME:sbe/Mobs/SB_Goose").path());
    }

    @Test
    void absoluteInsideRootIsAcceptedAndExistenceReported() throws IOException {
        Path existing = Files.writeString(project.resolve("old.omo"), "x");
        WriteTarget t = sandbox.resolve(WriteKind.OMO, existing.toString());
        assertTrue(t.exists());
        assertEquals(WriteRoot.PROJECT, t.root());
    }

    @Test
    void outsideEveryRootIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sandbox.resolve(WriteKind.OMO, tmp.resolve("elsewhere/x.omo").toString()));
        assertTrue(e.getMessage().startsWith("path_outside_sandbox"), e.getMessage());
    }

    @Test
    void dotDotCannotEscape() {
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.resolve(WriteKind.OMO, "project:../escape.omo"));
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.resolve(WriteKind.OMO, "../escape.omo"));
    }

    @Test
    void symlinkInsideRootPointingOutIsRefused() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path link = project.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // platform without symlink support — nothing to prove
        }
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.resolve(WriteKind.OMO, "project:link/x.omo"));
    }

    @Test
    void wrongExtensionIsRefusedAndMissingOneAppended() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sandbox.resolve(WriteKind.OMO, "project:x.png"));
        assertTrue(e.getMessage().startsWith("wrong_extension"));
        assertEquals("x.omo", sandbox.resolve(WriteKind.OMO, "project:x").fileName());
        assertEquals("X.OMO", sandbox.resolve(WriteKind.OMO, "project:X.OMO").fileName());
    }

    @Test
    void absentRootIsReportedNotGuessed() {
        WriteSandbox noGame = new WriteSandbox(WriteRoots.of(project, null, exports));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> noGame.resolve(WriteKind.SBO, "target:sbo/blocks/SB_Cow"));
        assertTrue(e.getMessage().startsWith("root_unavailable"), e.getMessage());
        assertTrue(noGame.describeRoots().contains("project:"));
        assertFalse(noGame.describeRoots().contains("target:"));
    }

    @Test
    void withoutATargetTargetKindsLandInTheProject() {
        // A project-only project (#380): no target tree, so a bare .sbo name goes to the project root.
        WriteSandbox projectOnly = new WriteSandbox(WriteRoots.forTarget(project, null, exports));
        WriteTarget sbo = projectOnly.resolve(WriteKind.SBO, "Cow");
        assertEquals(real(project).resolve("Cow.sbo"), sbo.path());
        assertEquals(WriteRoot.PROJECT, sbo.root());
        assertFalse(sbo.asksFirst());
    }

    @Test
    void bareNamesFollowTheTargetsFolders() {
        // The save rule names a folder key; each target maps it to its own folder.
        var other = com.openmason.api.target.GameTarget.builder("other", "Other Game")
                .resources(com.openmason.api.target.ResourceRoot.at(game))
                .folders(com.openmason.api.target.AssetFolders.builder()
                        .subtype("sbo", "block", "assets/blocks").subtype("sbe", "mob", "assets/creatures").build())
                .writes(new com.openmason.api.target.WriteRules(true, false))
                .build();
        WriteSandbox otherSandbox = new WriteSandbox(WriteRoots.forTarget(project, other, exports));
        assertEquals(real(game).resolve("assets/blocks/Cow.sbo"), otherSandbox.resolve(WriteKind.SBO, "Cow").path());
        assertEquals(real(game).resolve("assets/creatures/Cow.sbe"), otherSandbox.resolve(WriteKind.SBE, "Cow").path());
        assertFalse(otherSandbox.resolve(WriteKind.SBO, "Cow").asksFirst(), "this target's writes do not confirm");
    }

    @Test
    void readOnlyTargetOffersNoRoot() {
        var readOnly = TestGameTargets.stonebreakAt(game);
        readOnly = new com.openmason.api.target.GameTarget(readOnly.id(), readOnly.displayName(),
                readOnly.defaultNamespace(), readOnly.resources(), readOnly.folders(), readOnly.assetFilePrefix(),
                com.openmason.api.target.WriteRules.READ_ONLY, readOnly.uiDeploy(), readOnly.uiHost(), null, false);
        WriteRoots roots = WriteRoots.forTarget(project, readOnly, exports);
        assertNull(roots.path(WriteRoot.TARGET));
        assertEquals(WriteRoot.PROJECT, roots.defaultRoot(WriteKind.SBO));
    }

    @Test
    void unknownPrefixTeaches() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sandbox.resolve(WriteKind.OMO, "%bogus:x.omo"));
        assertTrue(e.getMessage().contains("project:"), e.getMessage());
    }

    @Test
    void plainFileNames() {
        assertEquals("a.omo", WriteSandbox.requirePlainFileName(" a.omo "));
        assertThrows(IllegalArgumentException.class, () -> WriteSandbox.requirePlainFileName("a/b"));
        assertThrows(IllegalArgumentException.class, () -> WriteSandbox.requirePlainFileName(".."));
        assertThrows(IllegalArgumentException.class, () -> WriteSandbox.requirePlainFileName(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> WriteSandbox.requirePlainFileName(""));
    }

    @Test
    void kindIdsRoundTrip() {
        for (WriteKind k : WriteKind.builtins()) {
            assertEquals(k, WriteKind.fromId(k.id()));
            assertEquals(k, WriteKind.fromId(k.extension()));
        }
        assertEquals(WriteRoot.TARGET, WriteRoot.fromPrefix("target"));
        // pre-#380 spellings
        assertEquals(WriteRoot.TARGET, WriteRoot.fromPrefix("Game"));
        assertEquals(WriteRoot.TARGET, WriteRoot.fromPrefix("game_resources"));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
