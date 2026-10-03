package com.openmason.main.systems.viewport;

import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
/**
 * Menus and toolbar tooltips read their shortcut column and label from the
 * registry by the ids in {@link ViewportKeybindActions} (issue #268), so the
 * registered keys, names and rebinds are what those surfaces show.
 */
class ViewportKeybindActionsTest {

    private final KeybindRegistry registry = KeybindRegistry.getInstance();
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicInteger saves = new AtomicInteger();

    @BeforeEach
    void register() {
        registry.clear();
        ViewportUIState state = new ViewportUIState();
        // Registration only binds method references; no action is executed here.
        ViewportKeybindActions.registerAll(registry, new ViewportActions(null, state, null), state);
        ViewportKeybindActions.registerFileActions(registry, opens::incrementAndGet, saves::incrementAndGet);
    }

    @AfterEach
    void clear() {
        registry.clear();
    }

    @Test
    void menuShortcutColumnsMatchDefaults() {
        assertEquals("Ctrl+R", registry.getShortcutDisplayName(ViewportKeybindActions.RESET_VIEW));
        assertEquals("Ctrl+F", registry.getShortcutDisplayName(ViewportKeybindActions.FIT_TO_VIEW));
        assertEquals("Ctrl+G", registry.getShortcutDisplayName(ViewportKeybindActions.TOGGLE_GRID));
        assertEquals("Ctrl+Shift+A", registry.getShortcutDisplayName(ViewportKeybindActions.TOGGLE_AXES));
        assertEquals("Ctrl+W", registry.getShortcutDisplayName(ViewportKeybindActions.TOGGLE_UNRENDERED));
        assertEquals("Ctrl+Z", registry.getShortcutDisplayName(ViewportKeybindActions.UNDO));
        assertEquals("Ctrl+Y", registry.getShortcutDisplayName(ViewportKeybindActions.REDO));
    }

    @Test
    void fileShortcutsAdvertisedByToolbarAreBound() {
        assertEquals("Ctrl+O", registry.getShortcutDisplayName(ViewportKeybindActions.OPEN_MODEL));
        assertEquals("Ctrl+S", registry.getShortcutDisplayName(ViewportKeybindActions.SAVE_MODEL));
        assertEquals("viewport", registry.getAction(ViewportKeybindActions.SAVE_MODEL).getContext());

        registry.getAction(ViewportKeybindActions.OPEN_MODEL).execute();
        registry.getAction(ViewportKeybindActions.SAVE_MODEL).execute();
        assertEquals(1, opens.get());
        assertEquals(1, saves.get());
    }

    @Test
    void rebindIsReflectedInShortcutColumn() {
        registry.setKeybind(ViewportKeybindActions.TOGGLE_GRID, ShortcutKey.ctrlShift(GLFW.GLFW_KEY_H));
        assertEquals("Ctrl+Shift+H", registry.getShortcutDisplayName(ViewportKeybindActions.TOGGLE_GRID));

        registry.resetToDefault(ViewportKeybindActions.TOGGLE_GRID);
        assertEquals("Ctrl+G", registry.getShortcutDisplayName(ViewportKeybindActions.TOGGLE_GRID));
    }

    @Test
    void oneDisplayNamePerOverlayAction() {
        assertEquals("Show Grid", registry.getActionDisplayName(ViewportKeybindActions.TOGGLE_GRID, "?"));
        assertEquals("Show Axes", registry.getActionDisplayName(ViewportKeybindActions.TOGGLE_AXES, "?"));
        assertEquals("Unrendered Mode", registry.getActionDisplayName(ViewportKeybindActions.TOGGLE_UNRENDERED, "?"));
    }

    @Test
    void unregisteredActionsFallBackInsteadOfAdvertisingAKey() {
        assertEquals("", registry.getShortcutDisplayName("viewport.not_a_thing"));
        assertEquals("Fallback", registry.getActionDisplayName("viewport.not_a_thing", "Fallback"));
    }

    @Test
    void numpadKeysHaveNamesThatRoundTrip() {
        ShortcutKey kp5 = ShortcutKey.simple(GLFW.GLFW_KEY_KP_5);
        assertEquals("Numpad 5", kp5.getDisplayName());
        assertEquals(kp5, ShortcutKey.parse(kp5.serialize()));
        assertEquals(ShortcutKey.simple(GLFW.GLFW_KEY_KP_DECIMAL), ShortcutKey.parse("Numpad ."));
        // Keys without a name serialize as "Key <code>" and must still load.
        assertEquals(ShortcutKey.simple(GLFW.GLFW_KEY_F13), ShortcutKey.parse("Key " + GLFW.GLFW_KEY_F13));
    }
}
