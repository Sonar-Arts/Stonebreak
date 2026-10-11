package com.openmason.main.systems.menus.textureCreator.keyboard;

import imgui.flag.ImGuiKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keybind value object: display names, the persisted string form (which must
 * parse back to the same key), validation, and GLFW to ImGui key translation.
 * Nothing here polls input, so no ImGui context is needed.
 */
class ShortcutKeyTest {

    @Test
    void displayNamesListModifiersInCtrlShiftAltOrder() {
        assertEquals("Ctrl+S", ShortcutKey.ctrl(GLFW.GLFW_KEY_S).getDisplayName());
        assertEquals("Ctrl+Shift+G", ShortcutKey.ctrlShift(GLFW.GLFW_KEY_G).getDisplayName());
        assertEquals("Ctrl+Shift+Alt+7", new ShortcutKey(GLFW.GLFW_KEY_7, true, true, true).getDisplayName());
        assertEquals("Alt+Esc", new ShortcutKey(GLFW.GLFW_KEY_ESCAPE, false, false, true).getDisplayName());
        assertEquals("Numpad Enter", ShortcutKey.simple(GLFW.GLFW_KEY_KP_ENTER).getDisplayName());
        assertEquals("Key " + GLFW.GLFW_KEY_F5, ShortcutKey.simple(GLFW.GLFW_KEY_F5).getDisplayName());
    }

    /**
     * Saved keybinds are stored with {@link ShortcutKey#serialize()} and read back
     * with {@link ShortcutKey#parse}. "Numpad +" (the default zoom-in binding) used
     * to fail to load because the '+' in the key name was taken as a separator.
     */
    @Test
    @Tag("regression")
    void everyNamedKeySerializesAndParsesBackToItself() {
        List<Integer> keys = new ArrayList<>();
        for (int k = GLFW.GLFW_KEY_A; k <= GLFW.GLFW_KEY_Z; k++) keys.add(k);
        for (int k = GLFW.GLFW_KEY_0; k <= GLFW.GLFW_KEY_9; k++) keys.add(k);
        for (int k = GLFW.GLFW_KEY_KP_0; k <= GLFW.GLFW_KEY_KP_9; k++) keys.add(k);
        keys.addAll(List.of(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_DELETE,
                GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_COMMA,
                GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SLASH, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_MINUS,
                GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_KP_DECIMAL,
                GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_F1, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_LEFT));

        for (int key : keys) {
            for (int mods = 0; mods < 8; mods++) {
                ShortcutKey original = new ShortcutKey(key, (mods & 1) != 0, (mods & 2) != 0, (mods & 4) != 0);
                assertEquals(original, ShortcutKey.parse(original.serialize()), original.serialize());
            }
        }
    }

    @Test
    void parseToleratesSpacesAndAnyModifierOrder() {
        assertEquals(ShortcutKey.ctrlShift(GLFW.GLFW_KEY_S), ShortcutKey.parse("Shift + Ctrl + S"));
        assertEquals(ShortcutKey.ctrl(GLFW.GLFW_KEY_COMMA), ShortcutKey.parse("Ctrl+,"));
        assertEquals(ShortcutKey.simple(GLFW.GLFW_KEY_KP_ADD), ShortcutKey.parse("Numpad +"));
        assertEquals(ShortcutKey.ctrlShift(GLFW.GLFW_KEY_KP_ADD), ShortcutKey.parse(" Ctrl+Shift+Numpad + "));
    }

    @Test
    void parseRejectsMalformedStrings() {
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse("Ctrl+Shift"), "modifiers only");
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse("Ctrl+S+T"), "two keys");
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse("Ctrl+Banana"));
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse("s"), "letters are upper-case");
        assertThrows(IllegalArgumentException.class, () -> ShortcutKey.parse("Key x"));
    }

    @Test
    void equalityCoversKeyAndEveryModifier() {
        assertEquals(ShortcutKey.ctrl(GLFW.GLFW_KEY_Z), new ShortcutKey(GLFW.GLFW_KEY_Z, true, false, false));
        assertEquals(ShortcutKey.ctrl(GLFW.GLFW_KEY_Z).hashCode(),
                new ShortcutKey(GLFW.GLFW_KEY_Z, true, false, false).hashCode());
        assertNotEquals(ShortcutKey.ctrl(GLFW.GLFW_KEY_Z), ShortcutKey.ctrlShift(GLFW.GLFW_KEY_Z));
        assertNotEquals(ShortcutKey.ctrl(GLFW.GLFW_KEY_Z), ShortcutKey.ctrl(GLFW.GLFW_KEY_Y));
        assertNotEquals(ShortcutKey.simple(GLFW.GLFW_KEY_Z), new ShortcutKey(GLFW.GLFW_KEY_Z, false, false, true));
    }

    @Test
    void reservedAndModifierOnlyKeysAreNotBindable() {
        assertFalse(ShortcutKey.isValidKeybind(null));
        for (int key : new int[]{GLFW.GLFW_KEY_F11, GLFW.GLFW_KEY_PRINT_SCREEN, GLFW.GLFW_KEY_LEFT_SUPER,
                GLFW.GLFW_KEY_RIGHT_SUPER, GLFW.GLFW_KEY_PAUSE, GLFW.GLFW_KEY_SCROLL_LOCK,
                GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_LEFT_ALT}) {
            assertFalse(ShortcutKey.isValidKeybind(ShortcutKey.ctrl(key)), "key " + key);
        }
        assertTrue(ShortcutKey.isValidKeybind(ShortcutKey.ctrlShift(GLFW.GLFW_KEY_F12)));
        assertTrue(ShortcutKey.isValidKeybind(ShortcutKey.simple(GLFW.GLFW_KEY_DELETE)));
    }

    @Test
    void glfwKeysTranslateToTheMatchingImGuiKeys() {
        assertEquals(ImGuiKey.A, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_A));
        assertEquals(ImGuiKey.Z, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_Z));
        assertEquals(ImGuiKey._0, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_0));
        assertEquals(ImGuiKey._9, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_9));
        assertEquals(ImGuiKey.Keypad0, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_KP_0));
        assertEquals(ImGuiKey.Keypad9, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_KP_9));
        assertEquals(ImGuiKey.F1, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_F1));
        assertEquals(ImGuiKey.F12, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_F12));
        assertEquals(ImGuiKey.Enter, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_ENTER));
        assertEquals(ImGuiKey.KeypadEnter, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_KP_ENTER));
        assertEquals(ImGuiKey.Escape, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(ImGuiKey.Comma, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_COMMA));
        assertEquals(ImGuiKey.KeypadAdd, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_KP_ADD));
        assertEquals(ImGuiKey.RightAlt, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_RIGHT_ALT));
    }

    @Test
    void unmappedKeysTranslateToNoneSoTheyAreNeverQueried() {
        assertEquals(ImGuiKey.None, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_F13));
        assertEquals(ImGuiKey.None, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_UNKNOWN));
        assertEquals(ImGuiKey.None, KeyCodeTranslator.toImGuiKey(GLFW.GLFW_KEY_HOME));
        // Unmapped keys short-circuit before any ImGui call, so this is safe without a context.
        assertFalse(KeyCodeTranslator.isKeyPressed(GLFW.GLFW_KEY_F13));
        assertFalse(KeyCodeTranslator.isKeyDown(GLFW.GLFW_KEY_UNKNOWN));
    }

    /** "Numpad ." could be bound but was never translated, so the binding never fired. */
    @Test
    @Tag("regression")
    void everyKeyTheEditorCanNameIsTranslatable() {
        // Any key with a display name (other than "Key <n>") must be queryable,
        // otherwise a rebound shortcut would silently never fire.
        for (int key = 0; key <= GLFW.GLFW_KEY_LAST; key++) {
            String name = ShortcutKey.simple(key).getDisplayName();
            if (!name.startsWith("Key ")) {
                assertNotEquals(ImGuiKey.None, KeyCodeTranslator.toImGuiKey(key), name);
            }
        }
    }

    @Test
    void shortcutManagerRejectsNullsAndCountsRegistrations() {
        KeyboardShortcutManager manager = new KeyboardShortcutManager();

        assertThrows(IllegalArgumentException.class, () -> manager.register(null, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> manager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_S), null));

        manager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_S), () -> { });
        manager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_S), () -> { }); // same chord replaces
        manager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_O), () -> { });
        assertEquals(2, manager.getShortcutCount());

        manager.clear();
        assertEquals(0, manager.getShortcutCount());
    }
}
