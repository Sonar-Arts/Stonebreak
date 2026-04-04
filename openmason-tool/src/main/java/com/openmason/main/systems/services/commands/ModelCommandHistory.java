package com.openmason.main.systems.services.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages undo/redo stacks for model editor commands.
 *
 * <p>Uses {@link ArrayDeque} (not {@link java.util.Stack}) for better performance
 * and no synchronization overhead.
 *
 * <p>All commands are pushed via {@link #pushCompleted(ModelCommand)} because
 * mutations are applied before the command is created. {@link #executeCommand}
 * is provided for commands that need to be executed on push (rare).
 */
public final class ModelCommandHistory {

    private static final Logger logger = LoggerFactory.getLogger(ModelCommandHistory.class);
    private static final int MAX_HISTORY_SIZE = 100;

    private final Deque<ModelCommand> undoStack = new ArrayDeque<>();
    private final Deque<ModelCommand> redoStack = new ArrayDeque<>();
    private Runnable onHistoryChange;

    /**
     * Execute a command and push it onto the undo stack.
     * Clears the redo stack. Use this when the command has NOT yet been applied.
     *
     * @param command The command to execute and record
     */
    public void executeCommand(ModelCommand command) {
        command.execute();
        pushToUndo(command);
        redoStack.clear();
        fireHistoryChange();
        logger.debug("Executed and pushed: {}", command.getDescription());
    }

    /**
     * Push a command that has already been applied (retrospective recording).
     * Clears the redo stack. Merges with the top of the undo stack if possible.
     *
     * @param command The already-applied command to record
     */
    public void pushCompleted(ModelCommand command) {
        if (!undoStack.isEmpty() && undoStack.peek().canMergeWith(command)) {
            ModelCommand merged = undoStack.pop().mergeWith(command);
            undoStack.push(merged);
            logger.debug("Merged into: {}", merged.getDescription());
        } else {
            pushToUndo(command);
            logger.debug("Pushed completed: {}", command.getDescription());
        }
        redoStack.clear();
        fireHistoryChange();
    }

    /**
     * Undo the most recent command.
     */
    public void undo() {
        if (undoStack.isEmpty()) {
            logger.debug("Nothing to undo");
            return;
        }
        ModelCommand command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        fireHistoryChange();
        logger.info("Undo: {}", command.getDescription());
    }

    /**
     * Redo the most recently undone command.
     */
    public void redo() {
        if (redoStack.isEmpty()) {
            logger.debug("Nothing to redo");
            return;
        }
        ModelCommand command = redoStack.pop();
        command.execute();
        undoStack.push(command);
        fireHistoryChange();
        logger.info("Redo: {}", command.getDescription());
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Description of the command that would be undone, for UI display.
     */
    public String getUndoDescription() {
        return undoStack.isEmpty() ? "" : undoStack.peek().getDescription();
    }

    /**
     * Description of the command that would be redone, for UI display.
     */
    public String getRedoDescription() {
        return redoStack.isEmpty() ? "" : redoStack.peek().getDescription();
    }

    /**
     * Clear all history (e.g. when loading a new model).
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        fireHistoryChange();
        logger.debug("Command history cleared");
    }

    /**
     * Register a callback that fires whenever the history changes.
     */
    public void setOnHistoryChange(Runnable callback) {
        this.onHistoryChange = callback;
    }

    // ── Internals ────────────────────────────────────────────────────────

    private void pushToUndo(ModelCommand command) {
        undoStack.push(command);
        trimToMaxSize();
    }

    private void trimToMaxSize() {
        while (undoStack.size() > MAX_HISTORY_SIZE) {
            // Remove oldest (bottom of the deque = last element)
            undoStack.removeLast();
        }
    }

    private void fireHistoryChange() {
        if (onHistoryChange != null) {
            onHistoryChange.run();
        }
    }
}
