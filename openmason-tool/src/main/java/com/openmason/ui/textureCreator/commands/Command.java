package com.openmason.ui.textureCreator.commands;

/**
 * Command interface for undo/redo operations.
 */
public interface Command {

    /**
     * Execute the command.
     * This performs the operation.
     */
    void execute();

    /**
     * Undo the command.
     * This reverses the operation.
     */
    void undo();

    /**
     * Get command description for debugging/UI.
     * @return human-readable command description
     */
    String getDescription();
}
