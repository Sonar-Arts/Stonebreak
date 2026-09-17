package com.openmason.main.systems.io;

import java.nio.file.Path;

/**
 * A sandbox-approved write destination.
 *
 * @param kind   what is being written
 * @param path   absolute, canonical target file
 * @param root   the root that contains it
 * @param exists true when a file is already there (the write is an overwrite)
 */
public record WriteTarget(WriteKind kind, Path path, WriteRoot root, boolean exists) {

    /** Writes into the shipped-asset tree always deserve a second look. */
    public boolean insideGame() {
        return root == WriteRoot.GAME_RESOURCES;
    }

    public String fileName() {
        return path.getFileName().toString();
    }
}
