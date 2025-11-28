package com.openmason.main.systems.menus.textureCreator.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Stack;

/**
 * Command history manager for undo/redo operations.
 */
public class CommandHistory {

    private static final Logger logger = LoggerFactory.getLogger(CommandHistory.class);
    private static final int MAX_HISTORY_SIZE = 100;

    private final Stack<Command> undoStack;
    private final Stack<Command> redoStack;

    /**
     * Create command history manager.
     */
    public CommandHistory() {
        this.undoStack = new Stack<>();
        this.redoStack = new Stack<>();
        logger.debug("Command history initialized");
    }

    /**
     * Execute a command and add it to history.
     *
     * @param command command to execute
     */
    public void executeCommand(Command command) {
        if (command == null) {
            logger.warn("Cannot execute null command");
            return;
        }

        // Execute the command
        command.execute();

        // Add to undo stack
        undoStack.push(command);

        // Clear redo stack (new action invalidates redo history)
        redoStack.clear();

        // Limit history size
        if (undoStack.size() > MAX_HISTORY_SIZE) {
            undoStack.remove(0); // Remove oldest command
        }

        logger.debug("Executed command: {} (undo stack: {})", command.getDescription(), undoStack.size());
    }

    /**
     * Undo the last command.
     * @return true if undo was performed
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            logger.debug("Nothing to undo");
            return false;
        }

        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);

        logger.debug("Undid command: {} (undo stack: {}, redo stack: {})",
                    command.getDescription(), undoStack.size(), redoStack.size());
        return true;
    }

    /**
     * Redo the last undone command.
     * @return true if redo was performed
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            logger.debug("Nothing to redo");
            return false;
        }

        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);

        logger.debug("Redid command: {} (undo stack: {}, redo stack: {})",
                    command.getDescription(), undoStack.size(), redoStack.size());
        return true;
    }

    /**
     * Check if undo is available.
     * @return true if can undo
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Check if redo is available.
     * @return true if can redo
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Clear all history.
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        logger.debug("Command history cleared");
    }
}
