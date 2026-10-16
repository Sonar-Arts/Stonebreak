package com.openmason.main.systems.io;

import java.nio.file.Path;

/**
 * A sandbox-approved write destination.
 *
 * @param kind      what is being written
 * @param path      absolute, canonical target file
 * @param root      the root that contains it
 * @param exists    true when a file is already there (the write is an overwrite)
 * @param asksFirst the root's rules want the user to confirm writes into it (a game target's
 *                  shipped assets, {@link com.openmason.api.target.WriteRules#confirmWrites()})
 */
public record WriteTarget(WriteKind kind, Path path, WriteRoot root, boolean exists, boolean asksFirst) {

    /** A target whose confirmation follows the root: writes into the game target ask. */
    public WriteTarget(WriteKind kind, Path path, WriteRoot root, boolean exists) {
        this(kind, path, root, exists, root == WriteRoot.TARGET);
    }

    /** True when the write lands in the game target's resource tree. */
    public boolean insideTarget() {
        return root == WriteRoot.TARGET;
    }

    public String fileName() {
        return path.getFileName().toString();
    }
}
