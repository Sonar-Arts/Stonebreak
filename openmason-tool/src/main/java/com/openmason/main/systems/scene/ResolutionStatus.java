package com.openmason.main.systems.scene;

/**
 * How a scene's model reference was satisfied when the scene was opened.
 *
 * <p>An {@code .omsc} carries every model twice — as a project-relative path and as
 * embedded bytes — so "did it load" is not a yes/no question. This records which channel
 * won, which drives both the outliner's badges and whether "Import to project" is offered.
 */
public enum ResolutionStatus {

    /** Loaded from the referenced file, whose bytes match the embedded copy. */
    REFERENCED,

    /**
     * Loaded from the referenced file, but its bytes differ from the embedded copy —
     * the model was edited since the scene was saved. The on-disk file still wins; that
     * is the entire point of keeping a reference.
     */
    REFERENCED_MODIFIED,

    /**
     * The referenced file is missing (or there is no project root), so the embedded copy
     * was used. This is the normal state for a scene opened in a different project.
     */
    EMBEDDED_FALLBACK,

    /** Neither channel resolved; the instance renders as a placeholder. */
    MISSING;

    /** Whether the model is not currently backed by a file in this project. */
    public boolean needsImport() {
        return this == EMBEDDED_FALLBACK || this == MISSING;
    }
}
