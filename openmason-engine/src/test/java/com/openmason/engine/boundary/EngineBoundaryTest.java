package com.openmason.engine.boundary;

import com.openmason.engine.util.EngineProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine stays reusable by other games (#378, epic #390): no compiled engine class may refer
 * to the game ({@code com.stonebreak}) or to the Open Mason tool ({@code com.openmason.main}).
 * Scans bytecode, so fully-qualified names and reflection strings count and comments do not.
 */
class EngineBoundaryTest {

    private static final List<String> FORBIDDEN = List.of(
            "com/stonebreak/", "com.stonebreak.",
            "com/openmason/main/", "com.openmason.main.");

    @Test
    void engineClassesNeverReferenceTheGameOrTheTool() throws Exception {
        Path classes = Path.of(EngineProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected the engine's compiled classes directory, got " + classes);
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(p -> p.toString().endsWith(".class"))
                    .filter(EngineBoundaryTest::referencesForbidden)
                    .map(p -> classes.relativize(p).toString())
                    .sorted()
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "engine classes referencing the game or the tool: " + offenders);
    }

    private static boolean referencesForbidden(Path classFile) {
        try {
            // Constant-pool UTF-8 entries are ASCII for these names, so a Latin-1 view is exact.
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return FORBIDDEN.stream().anyMatch(bytes::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
