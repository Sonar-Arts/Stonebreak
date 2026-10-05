package com.openmason.engine.ui.assets.edit;

/**
 * What extract-to-project does when the project already holds the dependency id with
 * different content. There is no silent default: the caller (an editor dialog) chooses.
 */
public enum CollisionPolicy {
    /** Refuse the command and report the collision. */
    FAIL,
    /** Link the document to the project's existing asset and drop the snapshot (undoable). */
    KEEP_PROJECT,
    /** Overwrite the project asset with the snapshot (undoable; the old bytes are kept). */
    REPLACE
}
