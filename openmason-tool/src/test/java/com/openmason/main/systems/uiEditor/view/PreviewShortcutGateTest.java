package com.openmason.main.systems.uiEditor.view;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys pressed while the designer runs a Preview belong to the previewed document (#293 review):
 * only view and file shortcuts reach the editor there, never one that edits the source.
 */
class PreviewShortcutGateTest {

    @Test
    void onlyViewAndFileShortcutsActDuringPreview() {
        KeybindRegistry r = KeybindRegistry.getInstance();
        UiKeybindActions.registerAll(r, new UiEditorWorkspace(() -> null));
        int checked = 0;
        for (KeybindAction a : r.getActionsByContext("ui")) {
            boolean edits = a.getId().matches("ui\\.(undo|redo|delete|duplicate|copy|cut|paste|rename|forward|backward"
                + "|select_all|deselect|select_parent)");
            if (edits) {
                assertFalse(UiEditorWorkspace.allowedInPreview(a), a.getId() + " must not fire into a running Preview");
                checked++;
            }
            if (a.getId().equals("ui.preview") || a.getId().equals("ui.save")) {
                assertTrue(UiEditorWorkspace.allowedInPreview(a), a.getId());
                checked++;
            }
        }
        assertTrue(checked >= 10, "the ui shortcut set was registered");
    }
}
