package com.openmason.main.boundary;

import com.openmason.main.mainOpenMason;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet on the tool's dependency on the game (#378, epic #390). Only the top-level classes
 * listed in {@code boundary/stonebreak-references.txt} may refer to {@code com.stonebreak}, and
 * the list may only shrink: a new referrer fails, and so does a listed class that no longer
 * refers to the game (delete its line). Scans bytecode, so fully-qualified names count and
 * comments do not. P4 (#382) takes the list to zero.
 */
class ToolGameReferenceRatchetTest {

    private static final String ALLOWLIST = "/boundary/stonebreak-references.txt";

    @Test
    void onlyAllowlistedClassesReferenceTheGame() throws Exception {
        Set<String> actual = referrers();
        Set<String> allowed = allowlist();

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(allowed);
        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(actual);

        assertAll(
                () -> assertTrue(added.isEmpty(), "new tool -> game references (route them through the engine "
                        + "or the planned Stonebreak plugin instead; see #390): " + added),
                () -> assertTrue(stale.isEmpty(), "these classes no longer reference the game; delete their "
                        + "lines from " + ALLOWLIST + " so the ratchet tightens: " + stale));
    }

    /** Top-level tool classes whose bytecode (including nested classes) names a game type. */
    private static Set<String> referrers() throws Exception {
        Path classes = Path.of(mainOpenMason.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected the tool's compiled classes directory, got " + classes);
        try (Stream<Path> files = Files.walk(classes)) {
            return files.filter(p -> p.toString().endsWith(".class"))
                    .filter(ToolGameReferenceRatchetTest::referencesGame)
                    .map(p -> topLevelName(classes.relativize(p)))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static boolean referencesGame(Path classFile) {
        try {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return bytes.contains("com/stonebreak/") || bytes.contains("com.stonebreak.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String topLevelName(Path relative) {
        String name = relative.toString().replace(relative.getFileSystem().getSeparator(), ".");
        name = name.substring(0, name.length() - ".class".length());
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static Set<String> allowlist() throws IOException {
        try (InputStream in = ToolGameReferenceRatchetTest.class.getResourceAsStream(ALLOWLIST)) {
            assertNotNull(in, ALLOWLIST + " is missing from the test resources");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().map(String::strip)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .collect(Collectors.toCollection(TreeSet::new));
            }
        }
    }
}
