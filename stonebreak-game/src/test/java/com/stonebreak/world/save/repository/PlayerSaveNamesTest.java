package com.stonebreak.world.save.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #335: remote players' saves were keyed by a lossy sanitized name — {@code Bob!} and
 * {@code Bob_} shared {@code players/Bob_.json}, and {@code Bob}/{@code bob} shared a file on a
 * case-insensitive file system. The new stem is a reversible, collision-free, case-safe
 * encoding; saves under the old name still load (and move to the new one).
 */
class PlayerSaveNamesTest {

    @TempDir
    Path tempDir;

    private static final List<String> NAMES = List.of(
            "Bob", "bob", "BOB", "Bob_", "Bob!", "Bob__", "_bob", "a-b", "x.y", "Player", "ÄÖü",
            "Bob/../x", "CON", "", "_", "__", "-", "名前", "\u0000");

    @Test
    void encodingRoundTripsAndNeverCollidesEvenIgnoringCase() {
        Set<String> folded = new HashSet<>();
        for (String name : NAMES) {
            String encoded = PlayerSaveNames.encode(name);
            assertEquals(name, PlayerSaveNames.decode(encoded), "round trip of '" + name + "'");
            assertEquals(encoded.toLowerCase(Locale.ROOT), encoded, "case-safe: '" + encoded + "'");
            assertTrue(encoded.matches("[a-z0-9._-]*"), "portable file name: '" + encoded + "'");
            assertTrue(folded.add(encoded.toLowerCase(Locale.ROOT)), "collision on '" + name + "'");
        }
    }

    @Test
    void stemsCarryAPrefixTheOldNamesNeverHad() {
        assertEquals("@_bob", PlayerSaveNames.stem("Bob"));
        assertEquals("@bob__", PlayerSaveNames.stem("bob_"));
        for (String name : NAMES) {
            assertFalse(PlayerSaveNames.legacyStem(name).contains("@"));
        }
    }

    @Test
    void malformedEncodingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlayerSaveNames.decode("bob_"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSaveNames.decode("_A"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSaveNames.decode("_.12"));
    }

    @Test
    void namesThatOnceSharedAFileNoLongerDo() throws IOException {
        FileSaveRepository repo = new FileSaveRepository(tempDir.resolve("world").toString());
        repo.saveNamedPlayerBytes("Bob!", bytes("bang"));
        repo.saveNamedPlayerBytes("Bob_", bytes("underscore"));
        repo.saveNamedPlayerBytes("bob", bytes("lower"));

        assertArrayEquals(bytes("bang"), repo.loadNamedPlayerBytes("Bob!").orElseThrow());
        assertArrayEquals(bytes("underscore"), repo.loadNamedPlayerBytes("Bob_").orElseThrow());
        assertArrayEquals(bytes("lower"), repo.loadNamedPlayerBytes("bob").orElseThrow());
        assertTrue(repo.loadNamedPlayerBytes("Bob").isEmpty());
    }

    @Test
    void aSaveUnderTheOldNameStillLoadsAndMovesToTheNewOne() throws IOException {
        Path world = tempDir.resolve("world");
        Path players = Files.createDirectories(world.resolve("players"));
        Files.write(players.resolve("Alice.json"), bytes("legacy"));
        FileSaveRepository repo = new FileSaveRepository(world.toString());

        Optional<byte[]> loaded = repo.loadNamedPlayerBytes("Alice");
        assertArrayEquals(bytes("legacy"), loaded.orElseThrow());
        assertTrue(Files.exists(players.resolve("@_alice.json")), "migrated to the new name");
        assertFalse(Files.exists(players.resolve("Alice.json")),
                "no old copy is left to come back after a later set-aside");

        assertTrue(repo.loadNamedPlayerBytes("alice").isEmpty(),
                "another name never reads the old file, whatever the file system's case rule");
    }

    @Test
    void anOldSaveUnderASpacedOrLongNameStillLoads() throws IOException {
        // The old sanitizer turned spaces into '_'; such names are valid again, so their
        // players must reach the file the old server wrote.
        Path world = tempDir.resolve("world");
        Path players = Files.createDirectories(world.resolve("players"));
        Files.write(players.resolve("Bob_Smith.json"), bytes("spaced"));
        Files.write(players.resolve("TheLongestNameAllowed_24.json"), bytes("long"));
        FileSaveRepository repo = new FileSaveRepository(world.toString());

        assertArrayEquals(bytes("spaced"), repo.loadNamedPlayerBytes("Bob Smith").orElseThrow());
        assertArrayEquals(bytes("long"), repo.loadNamedPlayerBytes("TheLongestNameAllowed_24").orElseThrow());
        assertArrayEquals(bytes("spaced"), repo.loadNamedPlayerBytes("Bob Smith").orElseThrow(),
                "and again from its migrated file");
    }

    @Test
    void anOldSaveIsMatchedWithItsExactCase() throws IOException {
        Path world = tempDir.resolve("world");
        Path players = Files.createDirectories(world.resolve("players"));
        Files.write(players.resolve("Carol.json"), bytes("carol"));
        FileSaveRepository repo = new FileSaveRepository(world.toString());

        assertTrue(repo.loadNamedPlayerBytes("carol").isEmpty());
        assertTrue(repo.loadNamedPlayerBytes("CAROL").isEmpty());
        assertTrue(Files.exists(players.resolve("Carol.json")), "left alone for its owner");
    }

    @Test
    void anOldSaveCanBeSetAside() throws IOException {
        Path world = tempDir.resolve("world");
        Path players = Files.createDirectories(world.resolve("players"));
        Files.write(players.resolve("Dave.json"), bytes("garbage"));
        FileSaveRepository repo = new FileSaveRepository(world.toString());

        Path aside = repo.setNamedPlayerFileAside("Dave");
        assertEquals("Dave.json.corrupt", aside.getFileName().toString());
        assertTrue(repo.loadNamedPlayerBytes("Dave").isEmpty());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
