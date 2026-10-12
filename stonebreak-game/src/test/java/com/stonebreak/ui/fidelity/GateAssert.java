package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.MigrationGate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts a migration gate passed; on failure writes each failing case's diff image to
 * {@code target/ui-fidelity/<screen>/} first, so the report names files to look at. Not a test class.
 */
public final class GateAssert {

    private GateAssert() {
    }

    public static void passed(MigrationGate.Report r) {
        if (!r.passed()) {
            Path dir = Path.of("target", "ui-fidelity", r.screen());
            r.writeDiffs(dir);
            assertTrue(false, r.table() + "\ndiffs: " + dir.toAbsolutePath());
        }
    }
}
