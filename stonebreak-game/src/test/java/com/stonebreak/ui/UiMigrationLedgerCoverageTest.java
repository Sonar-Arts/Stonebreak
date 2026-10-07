package com.stonebreak.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the #283 UI migration ledger ({@code openmason-engine/docs/ui-program/ui-migration-ledger.md})
 * from going stale: every screen, menu, overlay and HUD renderer class — plus
 * every Focus-battle element — must be named in it. Adding a UI class without
 * a ledger row fails here, before a migration can quietly drop it.
 *
 * <p>It is also the migration gate's bookkeeping (#296): a row may only leave {@code not started}
 * with a {@code **Fidelity:**} line naming committed baselines ({@code ui/fidelity/<screen>/<id>.png}
 * under the game's test resources), and may only be {@code migrated} with a {@code **Gate:**} line
 * naming the test class that runs {@code MigrationGate} for it.
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

    /** Row statuses, in migration order. */
    private static final Set<String> STATUSES = Set.of("not started", "baselined", "in progress", "migrated", "n/a");
    private static final Path BASELINES = Path.of("src/test/resources/ui/fidelity");
    private static final Path TESTS = Path.of("src/test/java");
    private static final Pattern TICKED = Pattern.compile("`([^`]+)`");

    @Test
    void everyRowHasAKnownStatus() throws IOException {
        Map<String, String> status = statuses(Files.readString(ledgerPath()));
        assertTrue(status.size() >= 77, "summary table lost rows: " + status.size());
        List<String> bad = status.entrySet().stream().filter(e -> !STATUSES.contains(e.getValue()))
            .map(e -> e.getKey() + "=" + e.getValue()).toList();
        assertTrue(bad.isEmpty(), "unknown ledger statuses " + bad + "; allowed: " + STATUSES);
    }

    @Test
    void rowsPastNotStartedCiteBaselinesThatExist() throws IOException {
        String ledger = Files.readString(ledgerPath());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : statuses(ledger).entrySet()) {
            String st = e.getValue();
            if (st.equals("not started") || st.equals("n/a")) {
                continue;
            }
            String row = section(ledger, e.getKey());
            List<String> fidelity = ticked(row, "**Fidelity:**");
            if (fidelity.isEmpty()) {
                problems.add(e.getKey() + ": status '" + st + "' needs a **Fidelity:** line naming baselines");
            }
            for (String id : fidelity) {
                if (!id.contains("/") || !Files.isRegularFile(BASELINES.resolve(id + ".png"))) {
                    problems.add(e.getKey() + ": no baseline " + BASELINES.resolve(id + ".png"));
                }
            }
            if (st.equals("migrated")) {
                List<String> gate = ticked(row, "**Gate:**");
                if (gate.isEmpty() || gate.stream().noneMatch(UiMigrationLedgerCoverageTest::testClassExists)) {
                    problems.add(e.getKey() + ": 'migrated' needs a **Gate:** line naming its MigrationGate test class");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** {@code id -> status} from the summary table. */
    private static Map<String, String> statuses(String ledger) {
        Map<String, String> out = new LinkedHashMap<>();
        int start = ledger.indexOf("## Summary table");
        int end = ledger.indexOf("\n## ", start + 1);
        for (String line : ledger.substring(start, end).split("\n")) {
            String[] cells = line.split("\\|");
            if (cells.length < 6 || !line.startsWith("| ") || cells[1].trim().equals("id") || cells[1].contains("---")) {
                continue;
            }
            out.put(cells[1].trim(), cells[5].trim());
        }
        return out;
    }

    /** The {@code ### id} section of the rows part. */
    private static String section(String ledger, String id) {
        int at = ledger.indexOf("\n### " + id + "\n");
        if (at < 0) {
            return "";
        }
        int end = ledger.indexOf("\n### ", at + 1);
        int next = ledger.indexOf("\n## ", at + 1);
        if (end < 0 || (next >= 0 && next < end)) {
            end = next < 0 ? ledger.length() : next;
        }
        return ledger.substring(at, end);
    }

    /** Backticked items on the row's {@code label} line. */
    private static List<String> ticked(String row, String label) {
        List<String> out = new ArrayList<>();
        for (String line : row.split("\n")) {
            if (line.startsWith("- " + label)) {
                Matcher m = TICKED.matcher(line);
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    private static boolean testClassExists(String simpleName) {
        try (Stream<Path> files = Files.walk(TESTS)) {
            return files.anyMatch(p -> p.getFileName().toString().equals(simpleName + ".java"));
        } catch (IOException e) {
            return false;
        }
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
