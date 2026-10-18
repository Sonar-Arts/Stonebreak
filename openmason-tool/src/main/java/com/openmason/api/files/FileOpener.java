package com.openmason.api.files;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What opening a file of one {@link FileType} does (the Project Browser's click, drops, "open file"
 * requests): typically bring the owning workspace forward and load the file into its editor.
 * One opener per file type; registered by the plugin that owns the editor.
 *
 * @param fileTypeId the {@link FileType#id()} it opens (also this contribution's id)
 * @param open       opens the file; runs on the UI thread
 */
public record FileOpener(String fileTypeId, Consumer<Path> open) {

    public FileOpener {
        Objects.requireNonNull(fileTypeId, "fileTypeId");
        Objects.requireNonNull(open, "open");
    }
}
