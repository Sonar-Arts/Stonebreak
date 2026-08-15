package com.openmason.main.systems.scene;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the Scene Viewer's keybindable actions.
 *
 * <p>Same shape as {@code ViewportKeybindActions}: the "scene" context (derived from the
 * id prefix) scopes these away from the viewport's and texture editor's identical default
 * chords, and going through the registry makes scene undo rebindable and visible in
 * Preferences like every other undo in the tool.
 */
public final class SceneKeybindActions {

    private static final String EDITING = "Editing";

    private SceneKeybindActions() {
    }

    /**
     * Registers all scene keybind actions with the registry.
     *
     * @param registry the keybind registry
     * @param actions  the scene actions executor
     */
    public static void registerAll(KeybindRegistry registry, SceneViewerActions actions) {
        // Ctrl+Z: Undo
        registry.registerAction(new KeybindAction(
                "scene.undo",
                "Undo",
                EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Z),
                actions::undo
        ));

        // Ctrl+Y: Redo
        registry.registerAction(new KeybindAction(
                "scene.redo",
                "Redo",
                EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Y),
                actions::redo
        ));
    }
}
