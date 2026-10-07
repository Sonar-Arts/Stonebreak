package com.openmason.main.systems.uiEditor.view;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.lwjgl.glfw.GLFW;

/**
 * The UI editor's rebindable shortcuts (context {@code ui}, from the id prefix), visible in
 * Preferences like every other tool's. They dispatch only while a UI editor panel is focused,
 * no text field is active and a previewed document does not hold the keyboard.
 */
final class UiKeybindActions {

    private static final String EDITING = "Editing";
    private static final String SELECTION = "Selection";
    static final String VIEW = "View";
    static final String FILE = "File";

    private UiKeybindActions() {
    }

    static void registerAll(KeybindRegistry r, UiEditorWorkspace w) {
        UiEditorActions a = w.context().actions;
        r.registerAction(new KeybindAction("ui.undo", "Undo", EDITING, ShortcutKey.ctrl(GLFW.GLFW_KEY_Z), a::undo));
        r.registerAction(new KeybindAction("ui.redo", "Redo", EDITING, ShortcutKey.ctrl(GLFW.GLFW_KEY_Y), a::redo));
        r.registerAction(new KeybindAction("ui.delete", "Delete", EDITING, ShortcutKey.simple(GLFW.GLFW_KEY_DELETE),
            a::deleteSelection));
        r.registerAction(new KeybindAction("ui.duplicate", "Duplicate", EDITING, ShortcutKey.ctrl(GLFW.GLFW_KEY_D),
            a::duplicateSelection));
        r.registerAction(new KeybindAction("ui.copy", "Copy", EDITING, ShortcutKey.ctrl(GLFW.GLFW_KEY_C), a::copySelection));
        r.registerAction(new KeybindAction("ui.cut", "Cut", EDITING, ShortcutKey.ctrl(GLFW.GLFW_KEY_X), a::cutSelection));
        r.registerAction(new KeybindAction("ui.paste", "Paste", EDITING, ShortcutKey.ctrl(GLFW.GLFW_KEY_V), a::paste));
        r.registerAction(new KeybindAction("ui.rename", "Rename", EDITING, ShortcutKey.simple(GLFW.GLFW_KEY_F2), () -> {
            if (w.context().doc() != null && w.context().doc().primary() != null) {
                w.context().renameRequest = w.context().doc().primary();
                w.context().focusWindow = HierarchyPanel.TITLE;
            }
        }));
        r.registerAction(new KeybindAction("ui.forward", "Bring Forward", EDITING,
            ShortcutKey.ctrl(GLFW.GLFW_KEY_RIGHT_BRACKET), () -> a.reorderSelection(1)));
        r.registerAction(new KeybindAction("ui.backward", "Send Backward", EDITING,
            ShortcutKey.ctrl(GLFW.GLFW_KEY_LEFT_BRACKET), () -> a.reorderSelection(-1)));
        r.registerAction(new KeybindAction("ui.select_all", "Select Siblings", SELECTION, ShortcutKey.ctrl(GLFW.GLFW_KEY_A),
            a::selectAll));
        r.registerAction(new KeybindAction("ui.deselect", "Deselect", SELECTION, ShortcutKey.simple(GLFW.GLFW_KEY_ESCAPE),
            () -> a.select(null, false)));
        r.registerAction(new KeybindAction("ui.select_parent", "Select Parent", SELECTION,
            new ShortcutKey(GLFW.GLFW_KEY_ENTER, false, true, false), a::selectParent));
        r.registerAction(new KeybindAction("ui.frame", "Frame Selection", VIEW, ShortcutKey.simple(GLFW.GLFW_KEY_F),
            () -> w.context().frameSelectionRequest = true));
        r.registerAction(new KeybindAction("ui.fit", "Fit Frame", VIEW, ShortcutKey.simple(GLFW.GLFW_KEY_HOME), () -> {
            if (w.context().view() != null) {
                w.context().view().fitPending = true;
            }
        }));
        r.registerAction(new KeybindAction("ui.zoom100", "Zoom 100%", VIEW, ShortcutKey.ctrl(GLFW.GLFW_KEY_1), w::zoomActual));
        r.registerAction(new KeybindAction("ui.preview", "Toggle Preview", VIEW, ShortcutKey.simple(GLFW.GLFW_KEY_F5),
            w::togglePreview));
        r.registerAction(new KeybindAction("ui.save", "Save Document", FILE, ShortcutKey.ctrl(GLFW.GLFW_KEY_S),
            w::saveActive));
        r.registerAction(new KeybindAction("ui.new", "New Document", FILE, ShortcutKey.ctrl(GLFW.GLFW_KEY_N),
            () -> w.dialogs().openNewDocument()));
        r.registerAction(new KeybindAction("ui.open", "Open Document", FILE, ShortcutKey.ctrl(GLFW.GLFW_KEY_O),
            w::openDocument));
        r.registerAction(new KeybindAction("ui.close", "Close Document", FILE, ShortcutKey.ctrl(GLFW.GLFW_KEY_W),
            w::closeActive));
    }
}
