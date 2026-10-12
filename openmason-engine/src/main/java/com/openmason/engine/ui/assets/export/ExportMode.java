package com.openmason.engine.ui.assets.export;

/** How an SBUI export treats shared dependencies. */
public enum ExportMode {
    /**
     * Shared dependencies stay shared: the plan lists the resources that must be deployed with
     * the SBUI (game resource root or the named pack).
     */
    SHARED,
    /**
     * Every resolvable shared dependency is collected into the SBUI, producing a package that
     * opens in a clean project. A required dependency that cannot be collected blocks the export.
     */
    COLLECT_ALL
}
