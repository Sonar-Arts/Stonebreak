package com.openmason.main.systems.menus.textureCreator.keyboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages keyboard shortcuts using Command pattern.
 */
public class KeyboardShortcutManager {
    private static final Logger logger = LoggerFactory.getLogger(KeyboardShortcutManager.class);

    // LinkedHashMap preserves registration order for predictable execution
    private final Map<ShortcutKey, ShortcutAction> shortcuts = new LinkedHashMap<>();

    /**
     * Register a keyboard shortcut.
     */
    public void register(ShortcutKey key, ShortcutAction action) {
        if (key == null || action == null) {
            throw new IllegalArgumentException("Key and action cannot be null");
        }

        shortcuts.put(key, action);
        logger.debug("Registered shortcut: {} -> {}", key.getDisplayName(), action.getClass().getSimpleName());
    }

    /**
     * Handle keyboard input by checking all registered shortcuts.
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
