package com.openmason.main.systems.services.commands;

/**
 * Command interface for model editor undo/redo operations.
 * Follows the Command pattern — each implementation encapsulates
 * a reversible mutation to the mesh or its texture state.
 *
 * <p>All commands are retrospective: the mutation has already been applied
 * by the time the command is pushed onto the history. {@link #execute()}
 * is only called during redo.
 */
public interface ModelCommand {

    /**
     * Apply (or re-apply) this command's mutation.
     * Called during redo — never called on initial push.
     */
    void execute();

    /**
     * Reverse this command's mutation.
     */
    void undo();

    /**
     * Human-readable description for UI display (e.g. "Move Vertex", "Knife Cut").
     */
    String getDescription();

    /**
     * Whether this command can be merged with another to form a single undo step.
     * Used for consecutive drags of the same selection.
     *
     * @param other The command to potentially merge with
     * @return true if merging is possible
     */
    boolean canMergeWith(ModelCommand other);

    /**
     * Create a new command that combines this command's original state
     * with the other command's final state.
     *
     * @param other The command to merge with (must pass {@link #canMergeWith})
     * @return A merged command
     */
    ModelCommand mergeWith(ModelCommand other);
}
