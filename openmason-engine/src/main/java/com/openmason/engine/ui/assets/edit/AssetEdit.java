package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.assets.ProjectFolder;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * The result of an asset command (embed, refresh, extract, relink, import): the document
 * before and after plus the project file changes, as data. Computing an edit touches nothing;
 * {@link #apply} performs the file writes and {@link #undo} reverts them, so an editor undo
 * stack holds these directly.
 *
 * @param label       short command name for undo menus
 * @param diagnostics what the command decided (collisions resolved, drift, skipped optional
 *                    dependencies); never contains errors — commands that cannot complete throw
 */
public record AssetEdit(String label, OmuiArchive before, OmuiArchive after, List<ProjectWrite> writes,
                        List<UiDiagnostic> diagnostics) {

    public AssetEdit {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        writes = List.copyOf(writes);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean changesDocument() {
        return !before.equals(after);
    }

    /**
     * Performs the project writes in order and returns {@link #after}. A write that fails
     * rolls back the ones already made, so the project never holds half a command.
     */
    public OmuiArchive apply(ProjectFolder folder) throws IOException {
        for (int i = 0; i < writes.size(); i++) {
            try {
                writes.get(i).apply(folder);
            } catch (IOException e) {
                for (int j = i - 1; j >= 0; j--) {
                    try {
                        writes.get(j).revert(folder);
                    } catch (IOException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            }
        }
        return after;
    }

    /** Reverts the project writes in reverse order and returns {@link #before}. */
    public OmuiArchive undo(ProjectFolder folder) throws IOException {
        for (int i = writes.size() - 1; i >= 0; i--) {
            writes.get(i).revert(folder);
        }
        return before;
    }
}
