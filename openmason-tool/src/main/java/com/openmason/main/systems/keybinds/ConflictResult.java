package com.openmason.main.systems.keybinds;

/**
 * Result object for keybind conflict detection.
 * <p>
 * Indicates whether a keybind assignment would conflict with an existing
 * binding, and provides access to the conflicting action if present.
 * </p>
 * <p>
 * Immutable value object following SOLID principles.
 * </p>
 *
 * @author Open Mason Team
 */
public final class ConflictResult {

    private final boolean hasConflict;
    private final KeybindAction conflictingAction;

    /**
     * Private constructor. Use static factory methods instead.
     *
     * @param hasConflict      whether a conflict exists
     * @param conflictingAction the conflicting action, or null if no conflict
     */
    private ConflictResult(boolean hasConflict, KeybindAction conflictingAction) {
        this.hasConflict = hasConflict;
        this.conflictingAction = conflictingAction;
    }

    /**
     * Creates a result indicating no conflict.
     *
     * @return a no-conflict result
     */
    public static ConflictResult noConflict() {
        return new ConflictResult(false, null);
    }

    /**
     * Creates a result indicating a conflict with the specified action.
     *
     * @param conflictingAction the action that conflicts
     * @return a conflict result
     * @throws IllegalArgumentException if conflictingAction is null
     */
    public static ConflictResult conflict(KeybindAction conflictingAction) {
        if (conflictingAction == null) {
            throw new IllegalArgumentException("Conflicting action cannot be null");
        }
        return new ConflictResult(true, conflictingAction);
    }

    /**
     * Checks if a conflict exists.
     *
     * @return true if there is a conflict, false otherwise
     */
    public boolean hasConflict() {
        return hasConflict;
    }

    /**
     * Gets the conflicting action.
     *
     * @return the conflicting action, or null if no conflict
     */
    public KeybindAction getConflictingAction() {
        return conflictingAction;
    }

    @Override
    public String toString() {
        if (hasConflict) {
            return "ConflictResult{conflict with '" + conflictingAction.getDisplayName() + "'}";
        } else {
            return "ConflictResult{no conflict}";
        }
    }
}
