package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Preferences → Cancel reverts immediately-applied keybind edits (#281). */
class KeybindSnapshotTest {

    private static final ShortcutKey KEY_A = ShortcutKey.simple(65);
    private static final ShortcutKey KEY_B = ShortcutKey.simple(66);
    private static final ShortcutKey KEY_C = ShortcutKey.simple(67);

    private final KeybindRegistry registry = KeybindRegistry.getInstance();
    private final Map<String, ShortcutKey> persisted = new HashMap<>();

    @BeforeEach
    void register() {
        registry.clear();
        registry.registerAction(new KeybindAction("test.a", "A", "Test", "test", KEY_A, () -> { }));
        registry.registerAction(new KeybindAction("test.b", "B", "Test", "test", KEY_B, () -> { }));
    }

    @AfterEach
    void clear() {
        registry.clear();
    }

    @Test
    void unchangedRegistryRestoresNothing() {
        KeybindSnapshot snapshot = KeybindSnapshot.capture(registry);

        assertEquals(0, snapshot.restore(registry, persisted::put));
        assertTrue(persisted.isEmpty());
    }

    @Test
    void restoreRevertsEditsMadeAfterCapture() {
        KeybindSnapshot snapshot = KeybindSnapshot.capture(registry);
        registry.setKeybind("test.a", KEY_C);

        assertEquals(1, snapshot.restore(registry, persisted::put));

        assertEquals(KEY_A, registry.getKeybind("test.a"));
        assertFalse(registry.isCustomized("test.a"));
        assertTrue(persisted.containsKey("test.a"));
        assertNull(persisted.get("test.a"));
    }

    @Test
    void restoreBringsBackASwappedPairWithoutLosingEitherBinding() {
        registry.setKeybind("test.a", KEY_B);
        registry.setKeybind("test.b", KEY_A);
        KeybindSnapshot snapshot = KeybindSnapshot.capture(registry);
        registry.resetAllToDefaults();

        assertEquals(2, snapshot.restore(registry, persisted::put));

        assertEquals(KEY_B, registry.getKeybind("test.a"));
        assertEquals(KEY_A, registry.getKeybind("test.b"));
        // Both keys still dispatch to their owner: a third action probing them conflicts with each
        registry.registerAction(new KeybindAction("test.c", "C", "Test", "test", KEY_C, () -> { }));
        assertSame(registry.getAction("test.a"), registry.checkConflict("test.c", KEY_B).getConflictingAction());
        assertSame(registry.getAction("test.b"), registry.checkConflict("test.c", KEY_A).getConflictingAction());
    }
}
