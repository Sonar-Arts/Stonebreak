package com.openmason.main.boundary;

import com.openmason.main.mainOpenMason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool knows nothing about Stonebreak (#378 guardrail, closed by #382): no compiled tool class
 * names a {@code com.stonebreak} type and the module does not read the game. Stonebreak's tooling
 * is the {@code openmason-plugin-stonebreak} module, which depends on the tool, never the reverse.
 * Scans bytecode, so fully-qualified names and inferred types count and comments do not.
 */
class ToolHasNoGameReferencesTest {

    private static Path classes() throws Exception {
        Path classes = Path.of(mainOpenMason.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected the tool's compiled classes directory, got " + classes);
        return classes;
    }

    @Test
    void noToolClassReferencesTheGame() throws Exception {
        Path classes = classes();
        Set<String> referrers;
        try (Stream<Path> files = Files.walk(classes)) {
            referrers = files.filter(p -> p.toString().endsWith(".class"))
                    .filter(ToolHasNoGameReferencesTest::referencesGame)
                    .map(p -> topLevelName(classes.relativize(p)))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(Set.of(), referrers, "tool classes that reference the game: move Stonebreak-specific code "
                + "into openmason-plugin-stonebreak, or route it through the plugin API / engine (epic #390)");
    }

    @Test
    void theModuleDoesNotReadTheGame() throws Exception {
        Path descriptor = classes().resolve("module-info.class");
        assertTrue(Files.isRegularFile(descriptor), "the tool is a named module");
        ModuleDescriptor module;
        try (InputStream in = Files.newInputStream(descriptor)) {
            module = ModuleDescriptor.read(in);
        }
        assertFalse(module.requires().stream().anyMatch(r -> r.name().startsWith("stonebreak")),
                "module " + module.name() + " requires " + module.requires());
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
}
