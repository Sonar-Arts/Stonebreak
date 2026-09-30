package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The customised keybinds at a point in time. Keybind edits apply (and persist)
 * immediately, so Preferences → Cancel reverts them by restoring the snapshot taken
 * when the window opened (or last applied).
 */
final class KeybindSnapshot {

    /** actionId → custom key, customised actions only (absent = default). */
    private final Map<String, ShortcutKey> custom;

    private KeybindSnapshot(Map<String, ShortcutKey> custom) {
        this.custom = custom;
    }

    static KeybindSnapshot capture(KeybindRegistry registry) {
        Map<String, ShortcutKey> map = new HashMap<>();
        for (KeybindAction action : registry.getAllActions()) {
            if (registry.isCustomized(action.getId())) {
                map.put(action.getId(), registry.getKeybind(action.getId()));
            }
        }
        return new KeybindSnapshot(map);
    }

    /** Action ids whose current binding differs from this snapshot. */
    List<String> changedActions(KeybindRegistry registry) {
        List<String> changed = new ArrayList<>();
        for (KeybindAction action : registry.getAllActions()) {
            String id = action.getId();
            ShortcutKey wanted = custom.get(id);
            ShortcutKey now = registry.isCustomized(id) ? registry.getKeybind(id) : null;
            if (!Objects.equals(wanted, now)) {
                changed.add(id);
            }
        }
        return changed;
    }

    /**
     * Restores the snapshot into the registry and reports each changed action to
     * {@code persist} (key, or null to clear the stored custom binding).
     *
     * @return number of actions reverted
     */
    int restore(KeybindRegistry registry, BiConsumer<String, ShortcutKey> persist) {
        List<String> changed = changedActions(registry);
        if (changed.isEmpty()) {
            return 0;
        }
        registry.resetAllToDefaults();
        custom.forEach(registry::setKeybind);
        for (String id : changed) {
            persist.accept(id, custom.get(id));
        }
        return changed.size();
    }
}
