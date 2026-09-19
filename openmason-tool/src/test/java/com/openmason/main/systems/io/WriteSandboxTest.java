package com.openmason.main.systems.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        sandbox = new WriteSandbox(WriteRoots.of(project, game, exports));
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
        assertTrue(sbo.insideGame());
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
                () -> noGame.resolve(WriteKind.SBO, "SB_Cow"));
        assertTrue(e.getMessage().startsWith("root_unavailable"), e.getMessage());
        assertTrue(noGame.describeRoots().contains("project:"));
        assertFalse(noGame.describeRoots().contains("game:"));
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
        for (WriteKind k : WriteKind.values()) {
            assertEquals(k, WriteKind.fromId(k.id()));
            assertEquals(k, WriteKind.fromId(k.extension()));
        }
        assertEquals(WriteRoot.GAME_RESOURCES, WriteRoot.fromPrefix("Game"));
        assertEquals(WriteRoot.GAME_RESOURCES, WriteRoot.fromPrefix("game_resources"));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
