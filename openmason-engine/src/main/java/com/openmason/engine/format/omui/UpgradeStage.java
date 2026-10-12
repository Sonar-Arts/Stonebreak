package com.openmason.engine.format.omui;

import java.util.Map;

/**
 * One explicit schema migration. Stages work on raw archive entries (JSON decoded per entry),
 * before any record decoding, so a stage can restructure freely. A stage returns new entries
 * and never mutates its input; the original file is never touched by an upgrade.
 */
interface UpgradeStage {

    SchemaVersion from();

    SchemaVersion to();

    /** @return the migrated entries, or {@code null} after recording an error */
    Map<String, byte[]> apply(Map<String, byte[]> entries, UiDiagnostics diagnostics);
}
