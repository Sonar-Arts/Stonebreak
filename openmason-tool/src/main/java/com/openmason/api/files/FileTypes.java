package com.openmason.api.files;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/** Matching paths against a set of {@link FileType}s. */
public final class FileTypes {

    private FileTypes() {
    }

    /**
     * The type of {@code fileName} among {@code types}: the one with the longest matching extension
     * ({@code .uss.json} beats {@code .json}), first registered on a tie.
     */
    public static Optional<FileType> match(Collection<FileType> types, String fileName) {
        FileType best = null;
        int bestLength = -1;
        for (FileType t : types) {
            String ext = t.matchingExtension(fileName);
            if (ext != null && ext.length() > bestLength) {
                best = t;
                bestLength = ext.length();
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<FileType> match(Collection<FileType> types, Path path) {
        if (path == null || path.getFileName() == null) {
            return Optional.empty();
        }
        return match(types, path.getFileName().toString());
    }
}
