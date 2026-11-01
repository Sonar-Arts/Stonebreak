package com.openmason.engine.boundary;

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
 * The engine stays reusable by other games (#378, epic #390), and since the split (#387) the core stands
 * alone: no compiled {@code openmason.engine.core} class may refer to the game ({@code com.stonebreak}), the
 * Open Mason tool ({@code com.openmason.main}), the UI module or the voxel module.
 * Scans bytecode, so fully-qualified names and reflection strings count and comments do not. JPMS already
 * stops a source import across these lines; this also catches {@code Class.forName} strings.
 */
class EngineBoundaryTest {

    /** Never: the game, the Open Mason tool. */
    private static final List<String> NOT_ENGINE = List.of(
            "com/stonebreak/", "com.stonebreak.",
            "com/openmason/main/", "com.openmason.main.");

    /** Package prefixes owned by the UI module ({@code openmason.engine.ui}). */
    static final List<String> UI = List.of(
            "com/openmason/engine/ui/", "com.openmason.engine.ui.",
            "com/openmason/engine/format/omui/", "com.openmason.engine.format.omui.",
            "com/openmason/engine/format/sbui/", "com.openmason.engine.format.sbui.",
            "com/openmason/engine/format/uiarchive/", "com.openmason.engine.format.uiarchive.");

    /** Package prefixes owned by the voxel module ({@code openmason.engine.voxel}). */
    static final List<String> VOXEL = List.of(
            "com/openmason/engine/voxel/", "com.openmason.engine.voxel.",
            "com/openmason/engine/net/replication/", "com.openmason.engine.net.replication.",
            "com/openmason/engine/wayfind/voxel/", "com.openmason.engine.wayfind.voxel.");

    @Test
    void engineCoreNeverReferencesTheGameTheToolOrTheOtherEngineModules() throws Exception {
        Path classes = Path.of(com.openmason.engine.util.EngineProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected the module's compiled classes directory, got " + classes);
        List<String> forbidden = Stream.of(NOT_ENGINE, UI, VOXEL).flatMap(List::stream).toList();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .filter(p -> references(p, forbidden))
                    .map(p -> classes.relativize(p).toString())
                    .sorted()
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "engine core classes crossing a module boundary: " + offenders);
    }

    private static boolean references(Path classFile, List<String> forbidden) {
        try {
            // Constant-pool UTF-8 entries are ASCII for these names, so a Latin-1 view is exact.
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return forbidden.stream().anyMatch(bytes::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
