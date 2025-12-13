package com.openmason.main.systems.keybinds;

import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry for all keybindable actions in Open Mason.
 * <p>
 * Manages action registration, keybind customization, conflict detection,
 * and provides category-based queries for UI organization.
 * </p>
 * <p>
 * Singleton pattern for global access. Thread-safe for registration and queries.
 * </p>
 *
 * @author Open Mason Team
 */
public class KeybindRegistry {

    private static final Logger logger = LoggerFactory.getLogger(KeybindRegistry.class);
    private static KeybindRegistry instance;

    // Action ID -> Action definition
    private final Map<String, KeybindAction> actions = new LinkedHashMap<>();

    // Current bindings: ShortcutKey -> Action ID (for fast lookup)
    private final Map<ShortcutKey, String> currentBindings = new LinkedHashMap<>();

    // Customized bindings: Action ID -> Custom ShortcutKey (for persistence)
    private final Map<String, ShortcutKey> customBindings = new HashMap<>();

    /**
     * Private constructor for singleton pattern.
     */
    private KeybindRegistry() {
        logger.debug("KeybindRegistry initialized");
    }

    /**
     * Gets the singleton instance of the keybind registry.
     *
     * @return the registry instance
     */
    public static synchronized KeybindRegistry getInstance() {
        if (instance == null) {
            instance = new KeybindRegistry();
        }
        return instance;
    }

    /**
     * Registers a new keybindable action.
     * <p>
     * Validates that the action ID is unique and that the default key
     * doesn't conflict with other registered actions.
     * </p>
     *
     * @param action the action to register
     * @throws IllegalArgumentException if action is null, ID conflicts, or default key conflicts
     */
    public synchronized void registerAction(KeybindAction action) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }

        // Check for duplicate action ID
        if (actions.containsKey(action.getId())) {
            throw new IllegalArgumentException("Action ID already registered: " + action.getId());
        }

        // Check for default key conflicts with other defaults
        for (KeybindAction existing : actions.values()) {
            if (existing.getDefaultKey().equals(action.getDefaultKey())) {
                logger.error("Default keybind conflict detected:");
                logger.error("  Existing: {} ({}) -> {}",
                    existing.getId(), existing.getDisplayName(), existing.getDefaultKey().getDisplayName());
                logger.error("  New: {} ({}) -> {}",
                    action.getId(), action.getDisplayName(), action.getDefaultKey().getDisplayName());
                throw new IllegalArgumentException(
                    "Default keybind conflict: Both '" + existing.getDisplayName() +
                    "' and '" + action.getDisplayName() + "' use " + action.getDefaultKey().getDisplayName()
                );
            }
        }

        // Register the action
        actions.put(action.getId(), action);

        // Add default binding to current bindings
        currentBindings.put(action.getDefaultKey(), action.getId());

        logger.debug("Registered action: {} ({}) -> {}",
            action.getId(), action.getDisplayName(), action.getDefaultKey().getDisplayName());
    }

    /**
     * Sets a custom keybind for an action.
     * <p>
     * Updates both the current bindings and custom bindings maps.
     * If the key is null, clears the custom binding and reverts to default.
     * </p>
     *
     * @param actionId the action ID
     * @param key      the new keybind, or null to revert to default
     * @throws IllegalArgumentException if action ID is not registered
     */
    public synchronized void setKeybind(String actionId, ShortcutKey key) {
        KeybindAction action = actions.get(actionId);
        if (action == null) {
            throw new IllegalArgumentException("Action not registered: " + actionId);
        }

        // Remove old binding from current bindings
        ShortcutKey oldKey = getKeybind(actionId);
        currentBindings.remove(oldKey);

        if (key == null) {
            // Revert to default
            customBindings.remove(actionId);
            currentBindings.put(action.getDefaultKey(), actionId);
            logger.debug("Reverted {} to default: {}", actionId, action.getDefaultKey().getDisplayName());
        } else {
            // Set custom binding
            customBindings.put(actionId, key);
            currentBindings.put(key, actionId);
            logger.debug("Set custom keybind for {}: {}", actionId, key.getDisplayName());
        }
    }

    /**
     * Gets the current keybind for an action (custom or default).
     *
     * @param actionId the action ID
     * @return the current keybind
     * @throws IllegalArgumentException if action ID is not registered
     */
    public ShortcutKey getKeybind(String actionId) {
        KeybindAction action = actions.get(actionId);
        if (action == null) {
            throw new IllegalArgumentException("Action not registered: " + actionId);
        }

        // Return custom binding if set, otherwise default
        return customBindings.getOrDefault(actionId, action.getDefaultKey());
    }

    /**
     * Gets the default keybind for an action.
     *
     * @param actionId the action ID
     * @return the default keybind
     * @throws IllegalArgumentException if action ID is not registered
     */
    public ShortcutKey getDefaultKeybind(String actionId) {
        KeybindAction action = actions.get(actionId);
        if (action == null) {
            throw new IllegalArgumentException("Action not registered: " + actionId);
        }
        return action.getDefaultKey();
    }

    /**
     * Gets an action by its ID.
     *
     * @param actionId the action ID
     * @return the action, or null if not found
     */
    public KeybindAction getAction(String actionId) {
        return actions.get(actionId);
    }

    /**
     * Checks if an action is using a custom keybind (not the default).
     *
     * @param actionId the action ID
     * @return true if using a custom keybind, false if using default
     */
    public boolean isCustomized(String actionId) {
        return customBindings.containsKey(actionId);
    }

    /**
     * Resets an action's keybind to its default.
     *
     * @param actionId the action ID
     */
    public void resetToDefault(String actionId) {
        setKeybind(actionId, null);
    }

    /**
     * Resets all actions to their default keybinds.
     */
    public synchronized void resetAllToDefaults() {
        logger.info("Resetting all keybinds to defaults");

        // Clear all custom bindings
        customBindings.clear();

        // Rebuild current bindings from defaults
        currentBindings.clear();
        for (KeybindAction action : actions.values()) {
            currentBindings.put(action.getDefaultKey(), action.getId());
        }

        logger.debug("All keybinds reset to defaults");
    }

    /**
     * Checks if a new keybind would conflict with an existing binding.
     * <p>
     * A conflict occurs when the proposed key is already bound to a different action.
     * It's not a conflict if the key is already bound to the same action.
     * </p>
     *
     * @param actionId the action ID that wants the new keybind
     * @param newKey   the proposed new keybind
     * @return conflict result indicating if there's a conflict and which action conflicts
     */
    public ConflictResult checkConflict(String actionId, ShortcutKey newKey) {
        String conflictingActionId = currentBindings.get(newKey);

        // No conflict if key is not bound, or if it's bound to the same action
        if (conflictingActionId == null || conflictingActionId.equals(actionId)) {
            return ConflictResult.noConflict();
        }

        // Conflict detected
        KeybindAction conflictingAction = actions.get(conflictingActionId);
        return ConflictResult.conflict(conflictingAction);
    }

    /**
     * Gets all actions in a specific category.
     *
     * @param category the category name
     * @return list of actions in the category, in registration order
     */
    public List<KeybindAction> getActionsByCategory(String category) {
        return actions.values().stream()
                .filter(action -> action.getCategory().equals(category))
                .collect(Collectors.toList());
    }

    /**
     * Gets all unique categories.
     *
     * @return set of category names, in the order they were first registered
     */
    public Set<String> getAllCategories() {
        // Use LinkedHashSet to preserve order
        Set<String> categories = new LinkedHashSet<>();
        for (KeybindAction action : actions.values()) {
            categories.add(action.getCategory());
        }
        return categories;
    }

    /**
     * Gets all registered actions.
     *
     * @return unmodifiable collection of all actions
     */
    public Collection<KeybindAction> getAllActions() {
        return Collections.unmodifiableCollection(actions.values());
    }

    /**
     * Gets the total number of registered actions.
     *
     * @return the action count
     */
    public int getActionCount() {
        return actions.size();
    }

    /**
     * Gets the number of customized keybinds.
     *
     * @return the count of custom bindings
     */
    public int getCustomBindingCount() {
        return customBindings.size();
    }

    /**
     * Clears all registered actions and bindings.
     * <p>
     * WARNING: This is primarily for testing. Use with caution.
     * </p>
     */
    public synchronized void clear() {
        actions.clear();
        currentBindings.clear();
        customBindings.clear();
        logger.warn("KeybindRegistry cleared");
    }
}
