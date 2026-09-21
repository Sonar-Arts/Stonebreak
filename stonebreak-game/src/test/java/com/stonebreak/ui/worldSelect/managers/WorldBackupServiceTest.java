package com.stonebreak.ui.worldSelect.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that a manual backup produces a complete, self-describing archive.
 *
 * <p>The failure this guards against is a backup that looks like it worked: an archive
 * missing the nested chunk files, one that flattens the directory layout so it cannot be
 * put back, or a {@code .part} file left behind after a failed run and mistaken for a
 * usable backup.
 */
class WorldBackupServiceTest {

    @TempDir
    Path tempDir;

    private Path worldsRoot;
    private Path backupsRoot;
    private WorldBackupService service;

    @BeforeEach
    void setUp() throws IOException {
        worldsRoot = Files.createDirectories(tempDir.resolve("worlds"));
        backupsRoot = tempDir.resolve("backups");
        service = new WorldBackupService(worldsRoot::resolve, () -> backupsRoot);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private Path makeWorld(String name) throws IOException {
        Path dir = Files.createDirectories(worldsRoot.resolve(name));
        Files.writeString(dir.resolve("world.json"), "{\"seed\":42}");
        Files.createDirectories(dir.resolve("chunks/r.0.0"));
        Files.write(dir.resolve("chunks/r.0.0/c.0.0.sbc"), new byte[512]);
        Files.write(dir.resolve("chunks/r.0.0/c.0.1.sbc"), new byte[512]);
        return dir;
    }

    /** Waits for the backup worker to reach a terminal state. */
    private WorldBackupService.Status await(String world) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        WorldBackupService.Status status = service.getStatus(world);
        while (status.state() == WorldBackupService.State.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(10L);
            status = service.getStatus(world);
        }
        return status;
    }

    private static List<String> entryNames(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(file.entries())) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private Path onlyBackup() throws IOException {
        try (var stream = Files.list(backupsRoot)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size(), "expected exactly one file in the backups folder, got " + files);
            return files.get(0);
        }
    }

    @Test
    void backupWritesAZipContainingEveryFile() throws Exception {
        makeWorld("My World");
        service.backup("My World");

        WorldBackupService.Status status = await("My World");
        assertEquals(WorldBackupService.State.DONE, status.state(), status.message());

        Path zip = onlyBackup();
        assertTrue(zip.getFileName().toString().endsWith(".zip"), "backup must be a .zip, got " + zip);
        List<String> names = entryNames(zip);
        assertTrue(names.contains("My World/world.json"), names.toString());
        assertTrue(names.contains("My World/chunks/r.0.0/c.0.0.sbc"), names.toString());
        assertTrue(names.contains("My World/chunks/r.0.0/c.0.1.sbc"), names.toString());
        assertEquals(3, names.size(), "archive should hold exactly the world's files: " + names);
    }

    @Test
    void archiveIsRootedAtTheWorldNameSoItUnpacksIntoItsOwnFolder() throws Exception {
        makeWorld("My World");
        service.backup("My World");
        await("My World");

        for (String name : entryNames(onlyBackup())) {
            assertTrue(name.startsWith("My World/"),
                    "every entry must sit under the world folder, found " + name);
        }
    }

    @Test
    void entryPathsUseForwardSlashesOnEveryPlatform() throws Exception {
        makeWorld("My World");
        service.backup("My World");
        await("My World");

        for (String name : entryNames(onlyBackup())) {
            assertFalse(name.contains("\\"), "zip entries are '/'-separated, found " + name);
        }
    }

    @Test
    void successMessageNamesTheArchive() throws Exception {
        makeWorld("My World");
        service.backup("My World");

        String message = await("My World").message();
        assertTrue(message.contains(onlyBackup().getFileName().toString()),
                "status should tell the player what was written, got: " + message);
    }

    @Test
    void backingUpTwiceProducesTwoDistinctArchives() throws Exception {
        makeWorld("My World");
        service.backup("My World");
        await("My World");
        Path first = onlyBackup();

        // The name is timestamped to the second, so move past it before the second run
        Thread.sleep(1_100L);
        service.backup("My World");
        await("My World");

        try (var stream = Files.list(backupsRoot)) {
            List<Path> files = stream.toList();
            assertEquals(2, files.size(), "a second backup must not overwrite the first: " + files);
            assertTrue(files.contains(first), "the earlier archive must survive");
        }
    }

    @Test
    void missingWorldFailsWithoutLeavingAnArchive() throws Exception {
        service.backup("Never Existed");

        WorldBackupService.Status status = await("Never Existed");
        assertEquals(WorldBackupService.State.FAILED, status.state());
        assertFalse(status.message().isBlank(), "a failure must say something to the player");
        assertFalse(Files.exists(backupsRoot) && Files.list(backupsRoot).findAny().isPresent(),
                "a failed backup must not leave a file behind");
    }

    @Test
    void noPartialFileSurvivesASuccessfulRun() throws Exception {
        makeWorld("My World");
        service.backup("My World");
        await("My World");

        try (var stream = Files.list(backupsRoot)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".part")),
                    "the temporary archive must be renamed, not left next to the real one");
        }
    }

    @Test
    void unknownWorldReportsIdleRatherThanNull() {
        WorldBackupService.Status status = service.getStatus("Never Touched");
        assertEquals(WorldBackupService.State.IDLE, status.state());
        assertTrue(status.message().isEmpty(), "an idle status has nothing to draw");
    }

    @Test
    void nullWorldNameIsIgnored() {
        service.backup(null);
        assertEquals(WorldBackupService.State.IDLE, service.getStatus(null).state());
    }

    @Test
    void emptyWorldBacksUpToAnEmptyArchive() throws Exception {
        Files.createDirectories(worldsRoot.resolve("Fresh"));
        service.backup("Fresh");

        WorldBackupService.Status status = await("Fresh");
        assertEquals(WorldBackupService.State.DONE, status.state(), status.message());
        assertEquals(1f, status.progress(), 0.001f, "a zero-byte world is 100% done, not 0%");
    }
}
