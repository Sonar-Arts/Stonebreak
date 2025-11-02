package com.openmason.ui.components.textureCreator.keyboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages keyboard shortcuts using Command pattern.
 * Follows SOLID principles: Single Responsibility, Open/Closed.
 *
 * Benefits:
 * - Centralizes shortcut management
 * - Easy to add/remove shortcuts without modifying handler logic
 * - Testable: can verify shortcuts without full UI
 * - Discoverable: can query registered shortcuts
 *
 * Usage:
 * <pre>
 * KeyboardShortcutManager shortcuts = new KeyboardShortcutManager();
 * shortcuts.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_C), () -> controller.copy());
 * shortcuts.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_V), () -> controller.paste());
 *
 * // In render loop:
 * shortcuts.handleInput();
 * </pre>
 *
 * @author Open Mason Team
 */
public class KeyboardShortcutManager {
    private static final Logger logger = LoggerFactory.getLogger(KeyboardShortcutManager.class);

    // LinkedHashMap preserves registration order for predictable execution
    private final Map<ShortcutKey, ShortcutAction> shortcuts = new LinkedHashMap<>();

    /**
     * Register a keyboard shortcut.
     * If the shortcut is already registered, it will be replaced.
     *
     * @param key the shortcut key combination
     * @param action the action to execute when the shortcut is pressed
     */
    public void register(ShortcutKey key, ShortcutAction action) {
        if (key == null || action == null) {
            throw new IllegalArgumentException("Key and action cannot be null");
        }

        shortcuts.put(key, action);
        logger.debug("Registered shortcut: {} -> {}", key.getDisplayName(), action.getClass().getSimpleName());
    }

    /**
     * Unregister a keyboard shortcut.
     *
     * @param key the shortcut key to remove
     * @return true if the shortcut was removed, false if it wasn't registered
     */
    public boolean unregister(ShortcutKey key) {
        boolean removed = shortcuts.remove(key) != null;
        if (removed) {
            logger.debug("Unregistered shortcut: {}", key.getDisplayName());
        }
        return removed;
    }

    /**
     * Handle keyboard input by checking all registered shortcuts.
     * Should be called once per frame in the render loop.
     *
     * Note: Only the first matching shortcut will be executed per frame.
     * This prevents multiple shortcuts from firing simultaneously.
     */
    public void handleInput() {
        for (Map.Entry<ShortcutKey, ShortcutAction> entry : shortcuts.entrySet()) {
            if (entry.getKey().isPressed()) {
                try {
                    logger.trace("Executing shortcut: {}", entry.getKey().getDisplayName());
                    entry.getValue().execute();
                    // Only execute first matching shortcut to avoid conflicts
                    return;
                } catch (Exception e) {
                    logger.error("Error executing shortcut {}: {}",
                        entry.getKey().getDisplayName(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Get all registered shortcuts for display/debugging.
     *
     * @return map of shortcuts (unmodifiable view)
     */
    public Map<ShortcutKey, ShortcutAction> getRegisteredShortcuts() {
        return Map.copyOf(shortcuts);
    }

    /**
     * Check if a shortcut is registered.
     *
     * @param key the shortcut key to check
     * @return true if registered
     */
    public boolean isRegistered(ShortcutKey key) {
        return shortcuts.containsKey(key);
    }

    /**
     * Clear all registered shortcuts.
     * Useful for cleanup or reset.
     */
    public void clear() {
        int count = shortcuts.size();
        shortcuts.clear();
        logger.debug("Cleared {} shortcuts", count);
    }

    /**
     * Get the number of registered shortcuts.
     */
    public int getShortcutCount() {
        return shortcuts.size();
    }
}
