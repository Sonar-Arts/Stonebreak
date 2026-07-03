package com.openmason.main.systems.skeleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Snapshot-based undo/redo history for the attachment point (socket) editor.
 *
 * <p>Mirrors {@link BoneCommandHistory} but operates on
 * {@link AttachmentSnapshot} pairs. Each entry is "before / after /
 * description" — undo restores before, redo restores after.
 */
public final class AttachmentCommandHistory {

    private static final Logger logger = LoggerFactory.getLogger(AttachmentCommandHistory.class);
    private static final int MAX_HISTORY_SIZE = 100;

    private final AttachmentStore store;
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();

    public AttachmentCommandHistory(AttachmentStore store) {
        this.store = store;
    }

    /**
     * Push a "before/after" pair onto the undo stack and clear the redo stack.
     * No-op if the snapshots are equal.
     */
    public void push(AttachmentSnapshot before, AttachmentSnapshot after, String description) {
        if (before == null || after == null) return;
        if (before.equalsSnapshot(after)) return;
        undoStack.push(new Entry(before, after, description));
        trim();
        redoStack.clear();
        logger.debug("Pushed attachment command: {}", description);
    }

    public boolean undo() {
        Entry e = undoStack.poll();
        if (e == null) return false;
        e.before.restore(store);
        redoStack.push(e);
        logger.info("Attachment undo: {}", e.description);
        return true;
    }

    public boolean redo() {
        Entry e = redoStack.poll();
        if (e == null) return false;
        e.after.restore(store);
        undoStack.push(e);
        logger.info("Attachment redo: {}", e.description);
        return true;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public String undoDescription() {
        return undoStack.isEmpty() ? "" : undoStack.peek().description;
    }

    public String redoDescription() {
        return redoStack.isEmpty() ? "" : redoStack.peek().description;
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    private void trim() {
        while (undoStack.size() > MAX_HISTORY_SIZE) {
            undoStack.removeLast();
        }
    }

    private record Entry(AttachmentSnapshot before, AttachmentSnapshot after, String description) {}
}
