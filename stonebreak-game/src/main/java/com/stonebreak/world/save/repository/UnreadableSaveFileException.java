package com.stonebreak.world.save.repository;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A save file that exists and was read, but whose contents do not parse. Distinct from a plain
 * {@link IOException} (the file could not be read at all — maybe locked, maybe fine): only an
 * unreadable file is ever set aside.
 */
public final class UnreadableSaveFileException extends IOException {

    private final Path file;

    public UnreadableSaveFileException(Path file, Throwable cause) {
        super(file.getFileName() + " is unreadable: " + cause.getMessage(), cause);
        this.file = file;
    }

    public Path file() {
        return file;
    }
}
