package com.openmason.main.systems.uiEditor.document;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.command.UiHistory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One open UI document in the editor: the authoritative source ({@link OmuiArchive}, immutable),
 * its file identity, its undo history and the selection. The only way to change the source is
 * {@link #execute}; preview state lives in the canvas and never reaches here, so it can never
 * dirty the document.
 *
 * <p>Not thread-safe: UI thread only.
 */
public final class UiEditorDocument {

    /** How the document came to be open. */
    public enum Origin {
        /** Created in this session; has no file until saved. */
        NEW,
        /** Opened from an {@code .omui} file. */
        FILE,
        /** An editable copy of an exported {@code .sbui}; the export itself is never written to. */
        SBUI_COPY,
        /** Restored from crash recovery; unsaved until the author saves. */
        RECOVERED
    }

    private OmuiArchive archive;
    private Path file;
    private final Origin origin;
    private final Path importedFrom;
    private final UiHistory history = new UiHistory();
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();
    private final List<Consumer<UiEditorDocument>> listeners = new CopyOnWriteArrayList<>();
    private long revision;
    private String lastMessage;
    private ProjectFolder project;
    /** Fingerprint of {@link #file} as last read or written by the editor (null = unknown). */
    private String diskStamp;

    public UiEditorDocument(OmuiArchive archive, Path file, Origin origin, Path importedFrom) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.file = file;
        this.origin = origin;
        this.importedFrom = importedFrom;
        if (origin == Origin.SBUI_COPY || origin == Origin.RECOVERED || origin == Origin.NEW && file == null) {
            history.markUnsaved();
        }
    }

    // ── state ───────────────────────────────────────────────────────────────

    public OmuiArchive archive() {
        return archive;
    }

    /** Bumped on every change of the source (execute, undo, redo, replace). */
    public long revision() {
        return revision;
    }

    public Path file() {
        return file;
    }

    public Origin origin() {
        return origin;
    }

    /** The {@code .sbui} an editable copy was made from, or null. */
    public Path importedFrom() {
        return importedFrom;
    }

    public boolean isDirty() {
        return history.isDirty();
    }

    public UiHistory history() {
        return history;
    }

    /** Tab and title text: the display name, else the file name, else the document id. */
    public String title() {
        String name = archive.manifest().displayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (file != null) {
            return file.getFileName().toString();
        }
        return archive.manifest().documentId();
    }

    /** The last command error or info message for the status line (cleared by the next success). */
    public String lastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String message) {
        lastMessage = message;
    }

    public void setProject(ProjectFolder project) {
        this.project = project;
    }

    public ProjectFolder project() {
        return project;
    }

    // ── selection (element keys) ────────────────────────────────────────────

    public List<String> selection() {
        return List.copyOf(selection);
    }

    /** The most recently selected key, or null. */
    public String primary() {
        String last = null;
        for (String s : selection) {
            last = s;
        }
        return last;
    }

    public boolean isSelected(String key) {
        return selection.contains(key);
    }

    public void select(List<String> keys) {
        if (!selection.equals(new LinkedHashSet<>(keys))) {
            selection.clear();
            selection.addAll(keys);
            fire();
        }
    }

    public void toggle(String key) {
        if (!selection.remove(key)) {
            selection.add(key);
        }
        fire();
    }

    public void addToSelection(String key) {
        selection.remove(key);
        selection.add(key);
        fire();
    }

    public void clearSelection() {
        select(List.of());
    }

    /** Drops selected keys the predicate rejects (elements that no longer exist). */
    public void pruneSelection(java.util.function.Predicate<String> exists) {
        if (selection.removeIf(k -> !exists.test(k))) {
            fire();
        }
    }

    // ── edits ───────────────────────────────────────────────────────────────

    /**
     * Runs {@code command} as one undoable step.
     *
     * @return false when it was refused (the reason is in {@link #lastMessage()})
     */
    public boolean execute(UiCommand command) {
        try {
            UiHistory.Outcome o = history.execute(command, archive, selection(), project);
            lastMessage = null;
            if (o != null) {
                apply(o, o.label() != null);
            }
            return true;
        } catch (UiCommandException e) {
            lastMessage = e.getMessage();
            fire();
            return false;
        }
    }

    public boolean undo() {
        try {
            UiHistory.Outcome o = history.undo(project);
            if (o != null) {
                lastMessage = "Undo " + o.label();
                apply(o, true);
            }
            return o != null;
        } catch (UiCommandException e) {
            lastMessage = e.getMessage();
            fire();
            return false;
        }
    }

    public boolean redo() {
        try {
            UiHistory.Outcome o = history.redo(project);
            if (o != null) {
                lastMessage = "Redo " + o.label();
                apply(o, true);
            }
            return o != null;
        } catch (UiCommandException e) {
            lastMessage = e.getMessage();
            fire();
            return false;
        }
    }

    public UiHistory.Checkpoint checkpoint() {
        return history.checkpoint();
    }

    /** Retracts the steps since {@code cp} without leaving redo entries (a run that made them failed). */
    public boolean rollbackTo(UiHistory.Checkpoint cp) {
        try {
            UiHistory.Outcome o = history.rollbackTo(cp, project);
            if (o != null) {
                lastMessage = "Rolled back " + o.label() + " (the run that made it failed)";
                apply(o, true);
            }
            return o != null;
        } catch (UiCommandException e) {
            lastMessage = e.getMessage();
            fire();
            return false;
        }
    }

    /** Ends a drag or typing session so the next mergeable edit starts a new undo step. */
    public void endInteraction() {
        history.endInteraction();
    }

    private void apply(UiHistory.Outcome o, boolean changed) {
        if (changed) {
            archive = o.doc();
            revision++;
        }
        Set<String> valid = new LinkedHashSet<>();
        for (String key : o.selection()) {
            String head = key.indexOf('/') < 0 ? key : key.substring(0, key.indexOf('/'));
            if (UiTree.find(archive.document().root(), head) != null) {
                valid.add(key);
            }
        }
        selection.clear();
        selection.addAll(valid);
        fire();
    }

    // ── file identity ───────────────────────────────────────────────────────

    /** The file's fingerprint when the editor last read or wrote it, or null when unknown. */
    public String diskStamp() {
        return diskStamp;
    }

    public void setDiskStamp(String stamp) {
        diskStamp = stamp;
    }

    /** The document was written to {@code target}; it is now the document's file and clean. */
    public void savedTo(Path target, OmuiArchive written) {
        file = target;
        if (written != archive) {
            archive = written; // the save stamped editor metadata
            revision++;
        }
        history.markSaved();
        fire();
    }

    // ── listeners ───────────────────────────────────────────────────────────

    public void addListener(Consumer<UiEditorDocument> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<UiEditorDocument> l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Consumer<UiEditorDocument> l : new ArrayList<>(listeners)) {
            l.accept(this);
        }
    }
}
