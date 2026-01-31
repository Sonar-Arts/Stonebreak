package com.openmason.main.systems.menus.textureCreator.keyboard;

/**
 * Functional interface representing an action to execute when a keyboard shortcut is triggered.
 * Follows Command pattern for keyboard shortcuts.
 *
 * @author Open Mason Team
 */
@FunctionalInterface
public interface ShortcutAction {
    /**
     * Execute the shortcut action.
     */
    void execute();
}
