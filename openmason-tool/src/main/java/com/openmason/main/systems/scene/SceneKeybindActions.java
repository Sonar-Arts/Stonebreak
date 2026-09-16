package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the Scene Viewer's keybindable actions.
 *
 * <p>Same shape as {@code ViewportKeybindActions}: the "scene" context (derived from the
 * id prefix) scopes these away from the viewport's and texture editor's identical default
 * chords, and going through the registry makes every scene shortcut rebindable and
 * visible in Preferences like the rest of the tool.
 *
 * <p>Plain-key chords (Delete, F, W/E/R, Escape) are safe here because the scene view
 * only dispatches while it is focused and no text field is active.
 */
public final class SceneKeybindActions {

    private static final String EDITING = "Editing";
    private static final String SELECTION = "Selection";
    private static final String VIEW = "View";
    private static final String TOOLS = "Tools";

    private SceneKeybindActions() {
    }

    /**
     * Registers all scene keybind actions with the registry.
     *
     * @param registry the keybind registry
     * @param actions  the scene actions executor
     * @param state    the scene view's display state (grid toggle)
     */
    public static void registerAll(KeybindRegistry registry, SceneViewerActions actions,
                                   SceneViewerUIState state) {
        // --- Editing ---
        registry.registerAction(new KeybindAction(
                "scene.undo", "Undo", EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Z), actions::undo));
        registry.registerAction(new KeybindAction(
                "scene.redo", "Redo", EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Y), actions::redo));
        registry.registerAction(new KeybindAction(
                "scene.delete", "Delete Selected", EDITING,
                ShortcutKey.simple(GLFW.GLFW_KEY_DELETE), actions::deleteSelected));
        registry.registerAction(new KeybindAction(
                "scene.duplicate", "Duplicate Selected", EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_D), actions::duplicateSelected));

        // --- Selection ---
        registry.registerAction(new KeybindAction(
                "scene.select_all", "Select All", SELECTION,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_A), actions::selectAll));
        registry.registerAction(new KeybindAction(
                "scene.deselect", "Deselect", SELECTION,
                ShortcutKey.simple(GLFW.GLFW_KEY_ESCAPE), actions::clearSelection));

        // --- View ---
        registry.registerAction(new KeybindAction(
                "scene.focus", "Focus Selection", VIEW,
                ShortcutKey.simple(GLFW.GLFW_KEY_F), actions::focusSelected));
        registry.registerAction(new KeybindAction(
                "scene.frame_all", "Frame Whole Scene", VIEW,
                ShortcutKey.simple(GLFW.GLFW_KEY_HOME), actions::frameAll));
        registry.registerAction(new KeybindAction(
                "scene.toggle_grid", "Toggle Grid", VIEW,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_G),
                () -> state.getGridVisible().set(!state.getGridVisible().get())));

        // --- Tools (gizmo mode) ---
        registry.registerAction(new KeybindAction(
                "scene.gizmo_move", "Gizmo: Move", TOOLS,
                ShortcutKey.simple(GLFW.GLFW_KEY_W),
                () -> actions.setGizmoModeFromShortcut(GizmoState.Mode.TRANSLATE)));
        registry.registerAction(new KeybindAction(
                "scene.gizmo_rotate", "Gizmo: Rotate", TOOLS,
                ShortcutKey.simple(GLFW.GLFW_KEY_E),
                () -> actions.setGizmoModeFromShortcut(GizmoState.Mode.ROTATE)));
        registry.registerAction(new KeybindAction(
                "scene.gizmo_scale", "Gizmo: Scale", TOOLS,
                ShortcutKey.simple(GLFW.GLFW_KEY_R),
                () -> actions.setGizmoModeFromShortcut(GizmoState.Mode.SCALE)));
    }
}
