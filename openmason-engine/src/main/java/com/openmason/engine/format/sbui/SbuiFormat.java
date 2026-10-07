package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.SchemaVersion;

/**
 * Constants of the {@code .sbui} game export (issue #284). An SBUI embeds the canonical OMUI
 * verbatim (it is the only editable tree), plus the game-facing identity, the union of
 * runtime requirements, a dependency table saying where each referenced asset resolves, any
 * dependencies collected at export, and derived caches keyed to their source hashes. The
 * normative description is {@code openmason-engine/docs/ui-program/omui-sbui-wire-contract.md}.
 *
 * <p>Version history: 1.0 — first released schema.
 */
public final class SbuiFormat {

    public static final String FORMAT_ID = "sbui";
    public static final String FILE_EXTENSION = ".sbui";
    public static final SchemaVersion SCHEMA_VERSION = new SchemaVersion(1, 0);

    public static final String MANIFEST = "manifest.json";
    public static final String SOURCE_DIR = "source/";
    public static final String ASSETS_DIR = "assets/";
    public static final String DERIVED_DIR = "derived/";

    /** Prefix of a derived cache's {@code source} when it was generated from a graph. */
    public static final String GRAPH_SOURCE = "graph:";
    /** Prefix of a derived cache's {@code source} when it was generated from a dependency. */
    public static final String DEPENDENCY_SOURCE = "dependency:";

    private SbuiFormat() {
    }

    /** {@code source/<last id segment>.omui} for the entry document. */
    public static String sourceEntry(String documentId) {
        String tail = documentId.substring(Math.max(documentId.lastIndexOf('/'), documentId.indexOf(':')) + 1);
        return SOURCE_DIR + tail + ".omui";
    }
}
