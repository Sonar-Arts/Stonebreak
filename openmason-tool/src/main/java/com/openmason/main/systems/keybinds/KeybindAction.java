package com.openmason.main.systems.keybinds;

import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;

import java.util.Objects;

/**
 * Immutable descriptor for a keybindable action.
 * <p>
 * Represents a single action that can be bound to a keyboard shortcut,
 * including its unique identifier, display name, category for organization,
 * default key binding, and the actual action to execute.
 * </p>
 * <p>
 * Follows immutable value object pattern for thread safety and predictability.
 * </p>
 *
 * @author Open Mason Team
 */
public final class KeybindAction {

    private final String id;
    private final String displayName;
    private final String category;
    private final String context;
    private final ShortcutKey defaultKey;
    private final Runnable action;

    /**
     * Creates a new keybind action descriptor.
     * <p>
     * The context is derived from the action ID prefix (everything before the first dot).
     * For example, "viewport.undo" gets context "viewport", "texture.undo" gets "texture".
     * Actions in different contexts may share the same default key without conflict.
     * </p>
     *
     * @param id          unique identifier for this action (e.g., "viewport.toggle_grid")
     * @param displayName human-readable name shown in UI (e.g., "Toggle Grid")
     * @param category    category for organization in UI (e.g., "Viewport", "File Operations")
     * @param defaultKey  default keyboard shortcut for this action
     * @param action      the action to execute when triggered (must not be null)
     * @throws IllegalArgumentException if any parameter is null or id/displayName/category is empty
     */
    public KeybindAction(String id, String displayName, String category, ShortcutKey defaultKey, Runnable action) {
        this(id, displayName, category, deriveContext(id), defaultKey, action);
    }

    /**
     * Creates a new keybind action descriptor with an explicit context.
     * <p>
     * Actions in the same context must not share default keys.
     * Actions in different contexts may freely overlap (e.g., both viewport and
     * texture editor can bind Ctrl+Z for their own undo).
     * </p>
     *
     * @param id          unique identifier for this action (e.g., "viewport.toggle_grid")
     * @param displayName human-readable name shown in UI (e.g., "Toggle Grid")
     * @param category    category for organization in UI (e.g., "Viewport", "File Operations")
     * @param context     context name for conflict scoping (e.g., "viewport", "texture")
     * @param defaultKey  default keyboard shortcut for this action
     * @param action      the action to execute when triggered (must not be null)
     * @throws IllegalArgumentException if any parameter is null or id/displayName/category is empty
     */
    public KeybindAction(String id, String displayName, String category, String context,
                         ShortcutKey defaultKey, Runnable action) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Action ID cannot be null or empty");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("Display name cannot be null or empty");
        }
        if (category == null || category.trim().isEmpty()) {
            throw new IllegalArgumentException("Category cannot be null or empty");
        }
        if (context == null || context.trim().isEmpty()) {
            throw new IllegalArgumentException("Context cannot be null or empty");
        }
        if (defaultKey == null) {
            throw new IllegalArgumentException("Default key cannot be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }

        this.id = id;
        this.displayName = displayName;
        this.category = category;
        this.context = context;
        this.defaultKey = defaultKey;
        this.action = action;
    }

    /**
     * Derives a context name from the action ID prefix.
     * Extracts everything before the first dot (e.g., "viewport.undo" -> "viewport").
     *
     * @param id the action ID
     * @return the derived context name
     */
    private static String deriveContext(String id) {
        if (id == null) return "default";
        int dotIndex = id.indexOf('.');
        return dotIndex > 0 ? id.substring(0, dotIndex) : "default";
    }

    /**
     * Gets the unique identifier for this action.
     *
     * @return the action ID (e.g., "viewport.toggle_grid")
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the human-readable display name.
     *
     * @return the display name (e.g., "Toggle Grid")
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the category for UI organization.
     *
     * @return the category name (e.g., "Viewport", "File Operations")
     */
    public String getCategory() {
        return category;
    }

    /**
     * Gets the context name for conflict scoping.
     * <p>
     * Actions in the same context must not share default keys.
     * Actions in different contexts may freely overlap.
     * </p>
     *
     * @return the context name (e.g., "viewport", "texture")
     */
    public String getContext() {
        return context;
    }

    /**
     * Gets the default keyboard shortcut for this action.
     *
     * @return the default shortcut key
     */
    public ShortcutKey getDefaultKey() {
        return defaultKey;
    }

    /**
     * Executes the action.
     * This is called when the bound keyboard shortcut is pressed.
     */
    public void execute() {
        action.run();
    }

    /**
     * Gets the underlying action runnable.
     * Package-private for registry access.
     *
     * @return the action runnable
     */
    Runnable getAction() {
        return action;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeybindAction that = (KeybindAction) o;
        // Compare by ID only (actions may not be comparable)
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "KeybindAction{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", category='" + category + '\'' +
                ", context='" + context + '\'' +
                ", defaultKey=" + defaultKey.getDisplayName() +
                '}';
    }
}
