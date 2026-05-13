package com.openmason.main.systems.skeleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Snapshot-based undo/redo history for the skeleton/bone editor.
 *
 * <p>Mirrors the shape of {@code ModelCommandHistory} but operates on
 * {@link BoneSnapshot} pairs since {@link BoneStore} doesn't otherwise
 * have command infrastructure. Each entry is "before / after / description"
 * — undo restores before, redo restores after.
 */
public final class BoneCommandHistory {

    private static final Logger logger = LoggerFactory.getLogger(BoneCommandHistory.class);
    private static final int MAX_HISTORY_SIZE = 100;

    private final BoneStore store;
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();

    public BoneCommandHistory(BoneStore store) {
        this.store = store;
    }

    /**
     * Push a "before/after" pair onto the undo stack and clear the redo stack.
     * No-op if the snapshots are equal.
     */
    public void push(BoneSnapshot before, BoneSnapshot after, String description) {
        if (before == null || after == null) return;
        if (before.equalsSnapshot(after)) return;
        undoStack.push(new Entry(before, after, description));
        trim();
        redoStack.clear();
        logger.debug("Pushed bone command: {}", description);
    }

    public boolean undo() {
        Entry e = undoStack.poll();
        if (e == null) return false;
        e.before.restore(store);
        redoStack.push(e);
        logger.info("Bone undo: {}", e.description);
        return true;
    }

    public boolean redo() {
        Entry e = redoStack.poll();
        if (e == null) return false;
        e.after.restore(store);
        undoStack.push(e);
        logger.info("Bone redo: {}", e.description);
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

    private record Entry(BoneSnapshot before, BoneSnapshot after, String description) {}
}
