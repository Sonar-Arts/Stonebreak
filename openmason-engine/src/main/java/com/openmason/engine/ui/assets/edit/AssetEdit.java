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
     * Performs the project writes in order and returns {@link #after}. All-or-nothing: every file
     * is checked before any is touched (a file changed since the command refuses the whole
     * edit), and a write that fails rolls back the ones already made, so the project never holds
     * half a command.
     */
    public OmuiArchive apply(ProjectFolder folder) throws IOException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ProjectWrite w : writes) {
            if (seen.add(w.path())) {
                w.checkApplicable(folder); // a later write to the same path starts from this one's result
            }
        }
        transition(folder, true);
        return after;
    }

    /**
     * Reverts the project writes in reverse order and returns {@link #before}. All-or-nothing
     * like {@link #apply}: when any file was changed after the command (an edit undo must not
     * clobber) nothing is reverted, and a revert that fails part-way re-applies the ones already
     * reverted, so the document and the project never disagree.
     */
    public OmuiArchive undo(ProjectFolder folder) throws IOException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = writes.size() - 1; i >= 0; i--) {
            if (seen.add(writes.get(i).path())) {
                writes.get(i).checkRevertible(folder);
            }
        }
        transition(folder, false);
        return before;
    }

    private void transition(ProjectFolder folder, boolean forward) throws IOException {
        int n = writes.size();
        for (int step = 0; step < n; step++) {
            ProjectWrite w = writes.get(forward ? step : n - 1 - step);
            try {
                if (forward) {
                    w.apply(folder);
                } else {
                    w.revert(folder);
                }
            } catch (IOException e) {
                for (int back = step - 1; back >= 0; back--) {
                    ProjectWrite done = writes.get(forward ? back : n - 1 - back);
                    try {
                        if (forward) {
                            done.revert(folder);
                        } else {
                            done.apply(folder);
                        }
                    } catch (IOException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            }
        }
    }
}
