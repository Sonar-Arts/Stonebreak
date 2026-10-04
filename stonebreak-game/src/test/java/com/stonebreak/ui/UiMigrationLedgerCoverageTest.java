package com.stonebreak.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the #283 UI migration ledger ({@code openmason-engine/docs/ui-program/ui-migration-ledger.md})
 * from going stale: every screen, menu, overlay and HUD renderer class — plus
 * every Focus-battle element — must be named in it. Adding a UI class without
 * a ledger row fails here, before a migration can quietly drop it.
 */
@Tag("regression")
class UiMigrationLedgerCoverageTest {

    private static final Path SOURCES = Path.of("src/main/java/com/stonebreak");
    private static final Pattern UI_CLASS = Pattern.compile(".*(Screen|Menu|Overlay|Renderer)\\.java");
    private static final List<String> WHOLE_PACKAGES = List.of(
        "ui/focusBattle/elements", "ui/focusBattle/timed", "ui/focusBattle/intro");

    @Test
    void everyUiClassIsNamedInTheLedger() throws IOException {
        String ledger = Files.readString(ledgerPath());
        List<String> missing;
        try (Stream<Path> ui = Files.walk(SOURCES.resolve("ui"));
             Stream<Path> components = Files.walk(SOURCES.resolve("rendering/UI/components"))) {
            missing = Stream.concat(ui, components)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> UI_CLASS.matcher(p.getFileName().toString()).matches() || inWholePackage(p))
                .map(p -> p.getFileName().toString().replace(".java", ""))
                .filter(name -> !Pattern.compile("\\b" + name + "\\b").matcher(ledger).find())
                .sorted()
                .toList();
        }
        assertTrue(missing.isEmpty(), "UI classes with no ledger row (add them to "
            + "openmason-engine/docs/ui-program/ui-migration-ledger.md): " + missing);
    }

    private static boolean inWholePackage(Path p) {
        String unix = SOURCES.relativize(p).toString().replace('\\', '/');
        return WHOLE_PACKAGES.stream().anyMatch(pkg -> unix.startsWith(pkg + "/"));
    }

    private static Path ledgerPath() {
        for (Path candidate : List.of(Path.of("../openmason-engine/docs/ui-program/ui-migration-ledger.md"),
            Path.of("openmason-engine/docs/ui-program/ui-migration-ledger.md"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("ledger not found from " + Path.of("").toAbsolutePath());
    }
}
