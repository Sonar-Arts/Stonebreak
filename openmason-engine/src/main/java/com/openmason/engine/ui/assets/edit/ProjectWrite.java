package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.ui.assets.ProjectFolder;

import java.io.IOException;
import java.util.Objects;

/**
 * One reversible project file change made by an asset command.
 *
 * @param path     project-relative path
 * @param previous bytes before the change, {@code null} when the file did not exist
 * @param next     bytes after the change, {@code null} when the change deletes the file
 */
public record ProjectWrite(String path, UiBytes previous, UiBytes next) {

    public ProjectWrite {
        Objects.requireNonNull(path, "path");
    }

    /** @throws IOException when the file no longer holds {@link #previous} (applying would clobber) */
    void checkApplicable(ProjectFolder folder) throws IOException {
        expect(folder, previous);
    }

    /** @throws IOException when the file no longer holds {@link #next} (reverting would clobber) */
    void checkRevertible(ProjectFolder folder) throws IOException {
        expect(folder, next);
    }

    private void expect(ProjectFolder folder, UiBytes from) throws IOException {
        if (!Objects.equals(folder.read(path), from)) {
            throw new IOException("'" + path + "' changed outside this command; refusing to overwrite it");
        }
    }

    void apply(ProjectFolder folder) throws IOException {
        transition(folder, previous, next);
    }

    void revert(ProjectFolder folder) throws IOException {
        transition(folder, next, previous);
    }

    /**
     * Moves the file from {@code from} to {@code to}, refusing when someone changed it in
     * between: undo never clobbers an edit made after the command.
     */
    private void transition(ProjectFolder folder, UiBytes from, UiBytes to) throws IOException {
        expect(folder, from);
        if (to == null) {
            folder.delete(path);
        } else {
            folder.write(path, to);
        }
    }
}
